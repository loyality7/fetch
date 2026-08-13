package com.fetch.core.extract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Document as JsoupDocument

/**
 * Recovers readable text from the JSON a client-rendered page ships alongside
 * its empty shell.
 *
 * Frameworks that render on the client still have to send the data somewhere,
 * and they send it as JSON in the markup: hydration payloads, application
 * state, structured data. Reading it turns a page that would otherwise need a
 * browser into one that does not — which on a phone is the difference between
 * a few hundred milliseconds and a browser launch.
 *
 * Only worth trying once ordinary extraction has come back thin. It recovers
 * prose, not layout, so a page with real markup should never come through here.
 */
internal object EmbeddedState {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Keys whose values are prose often enough to be worth trusting. */
    private val CONTENT_KEYS = setOf(
        "articlebody", "body", "bodytext", "content", "contenthtml", "text",
        "description", "summary", "excerpt", "abstract", "caption", "headline",
        "title", "name", "subtitle", "standfirst", "leadparagraph",
    )

    /** Values that are plainly machinery rather than writing. */
    private val NOISE = Regex(
        "^(https?://|/|data:|[0-9a-f]{16,}$|[A-Za-z0-9+/]{40,}={0,2}$)",
    )

    /**
     * Returns recovered text, or null when the page carries no usable payload.
     *
     * Applies no length threshold of its own: whether the result is worth
     * preferring over the markup is the caller's decision, and an article body
     * shorter than the useful-content floor is still better than nothing.
     *
     * Must run before scripts are stripped from [doc].
     */
    fun recover(doc: JsoupDocument): String? {
        val payloads = buildList {
            // Next.js and anything else using a typed JSON script block.
            doc.select("script[type=application/json], script[type=application/ld+json]")
                .forEach { add(it.data()) }

            // Nuxt, Apollo and friends assign to a global instead.
            doc.select("script").forEach { script ->
                val data = script.data()
                ASSIGNMENT.find(data)?.let { match -> add(data.substring(match.range.last + 1)) }
            }
        }

        val sentences = payloads
            .asSequence()
            .mapNotNull { runCatching { json.parseToJsonElement(it.trim().removeSuffix(";")) }.getOrNull() }
            .flatMap { collect(it, key = null).asSequence() }
            .distinct()
            .toList()

        return sentences.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Walks the tree collecting strings that read as prose.
     *
     * A value qualifies either by sitting under a content-ish key or by being
     * long enough that nothing else it could be would matter.
     */
    private fun collect(element: JsonElement, key: String?): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (name, value) -> collect(value, name) }
        is JsonArray -> element.flatMap { collect(it, key) }
        is JsonPrimitive -> {
            val value = element.contentOrNullSafe()?.trim()
            when {
                value == null -> emptyList()
                // Hydration payloads nest JSON inside JSON strings. Descending
                // reaches the prose; keeping the string would index the
                // serialisation itself.
                looksSerialised(value) -> reparse(value, key)
                qualifies(value, key) -> listOf(value)
                else -> emptyList()
            }
        }
    }

    private fun looksSerialised(value: String): Boolean =
        (value.startsWith("{") || value.startsWith("[")) && value.contains("\":")

    private fun reparse(value: String, key: String?): List<String> =
        runCatching { json.parseToJsonElement(value) }
            .map { collect(it, key) }
            .getOrDefault(emptyList())

    private fun qualifies(value: String, key: String?): Boolean {
        val text = value.trim()
        if (text.length < MIN_VALUE_LENGTH) return false
        if (NOISE.containsMatchIn(text)) return false
        if (text.count { it == ' ' } < MIN_SPACES) return false

        // Fragments of markup or serialisation that survived the checks above:
        // prose is mostly letters and spaces, machinery is mostly punctuation.
        val structural = text.count { it in STRUCTURAL_CHARS }
        if (structural.toDouble() / text.length > MAX_STRUCTURAL_SHARE) return false

        val keyed = key?.lowercase()?.let { name -> CONTENT_KEYS.any { name.contains(it) } } == true
        return keyed || text.length >= STANDALONE_LENGTH
    }

    /** Numbers and booleans are never prose; only quoted strings are considered. */
    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    private val ASSIGNMENT = Regex("window\\.(__NUXT__|__NEXT_DATA__|__APOLLO_STATE__|__INITIAL_STATE__)\\s*=")

    private const val MIN_VALUE_LENGTH = 40
    private const val MIN_SPACES = 4
    private const val STANDALONE_LENGTH = 120

    private const val STRUCTURAL_CHARS = "{}[]<>\"\\|$"
    private const val MAX_STRUCTURAL_SHARE = 0.04
}
