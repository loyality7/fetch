package com.fetch.core.discovery.sources

import com.fetch.core.discovery.SearchSource
import com.fetch.core.discovery.Vertical
import com.fetch.core.model.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Runs any source described by a [SourceDefinition].
 *
 * One implementation covers every JSON endpoint, so adding a source is a matter
 * of describing it rather than writing a class, and an application can register
 * one the engine has never seen.
 */
public class JsonSearchSource(
    private val definition: SourceDefinition,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val client: OkHttpClient = sharedClient,
) : SearchSource {

    override val name: String get() = definition.name
    override val isOfficial: Boolean get() = definition.official
    override val verticals: Set<Vertical> get() = definition.verticals

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String, maxResults: Int): List<SearchHit> =
        withContext(Dispatchers.IO) {
            val url = definition.endpoint
                .replace("{query}", encode(query))
                .replace("{limit}", maxResults.toString())

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .apply { definition.headers.forEach { (key, value) -> header(key, value) } }
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) error("${definition.name} returned ${response.code}")
                parse(json.parseToJsonElement(response.body?.string().orEmpty()), maxResults)
            }
        }

    private fun parse(body: JsonElement, maxResults: Int): List<SearchHit> =
        select(body, definition.resultsPath)
            .filterIsInstance<JsonObject>()
            .take(maxResults)
            .mapIndexedNotNull { index, item ->
                val title = field(item, definition.titlePath) ?: return@mapIndexedNotNull null
                val url = resolveUrl(item) ?: return@mapIndexedNotNull null

                SearchHit(
                    title = clean(title),
                    url = url,
                    snippet = definition.snippetPath?.let { field(item, it) }?.let(::clean).orEmpty().take(SNIPPET),
                    rank = index,
                    sourceName = definition.name,
                    publishedAt = definition.datePath
                        ?.let { field(item, it) }
                        ?.toLongOrNull()
                        ?.times(1_000),
                )
            }

    /**
     * Prefers a link the response supplies. Falls back to the template, which
     * covers interfaces that identify a result by name rather than address.
     */
    private fun resolveUrl(item: JsonObject): String? {
        definition.urlPath?.let { path -> field(item, path)?.takeIf { it.isNotBlank() }?.let { return it } }

        val template = definition.urlTemplate ?: return null

        // A template is only usable when every placeholder resolves; a partial
        // substitution would produce a URL that looks valid and is not.
        var complete = true
        val filled = PLACEHOLDER.replace(template) { match ->
            val value = field(item, match.groupValues[1])
            if (value == null) complete = false
            value?.replace(' ', '_').orEmpty()
        }
        return filled.takeIf { complete }
    }

    /** Walks a dotted path, traversing arrays wherever they appear. */
    private fun select(root: JsonElement, path: String): List<JsonElement> =
        path.split('.').fold(listOf(root)) { level, segment ->
            level.flatMap { element ->
                when (element) {
                    is JsonObject -> listOfNotNull(element[segment])
                    is JsonArray -> element.flatMap { child ->
                        if (child is JsonObject) listOfNotNull(child[segment]) else emptyList()
                    }
                    else -> emptyList()
                }
            }
        }.flatMap { if (it is JsonArray) it.toList() else listOf(it) }

    private fun field(item: JsonElement, path: String): String? =
        select(item, path)
            .filterIsInstance<JsonPrimitive>()
            .firstOrNull { it !is JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val SNIPPET = 300
        const val DEFAULT_USER_AGENT = "fetch/0.1"

        val PLACEHOLDER = Regex("\\{([a-zA-Z0-9_.]+)}")

        /** Shared so a fan-out reuses connections instead of one client per source. */
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        /** Response text arrives as markup often enough to be worth handling here. */
        fun clean(value: String): String =
            value.replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
