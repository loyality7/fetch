package com.fetch.core.extract

import com.fetch.core.model.Link
import com.fetch.core.model.PageMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns non-HTML responses into the same document shape as a page.
 *
 * An agent asking for an endpoint wants the data, not a rendering of it, so
 * JSON becomes indented markdown rather than prose, and a feed becomes its
 * entries. Both stay searchable, which a raw blob would not be.
 */
internal object StructuredDecoder {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(type: ContentType, body: String, url: String, maxChars: Int): Extracted = when (type) {
        ContentType.JSON -> decodeJson(body, url, maxChars)
        ContentType.FEED -> decodeFeed(body, url, maxChars)
        ContentType.XML -> decodeXml(body, url, maxChars)
        ContentType.MARKDOWN -> plain(body, url, maxChars, markdown = true)
        else -> plain(body, url, maxChars, markdown = false)
    }

    private fun decodeJson(body: String, url: String, maxChars: Int): Extracted {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: return plain(body, url, maxChars, markdown = false)

        val rendered = buildString { render(element, this, depth = 0) }.take(maxChars)

        return Extracted(
            title = title(element) ?: url.substringAfterLast('/').ifEmpty { url },
            text = rendered,
            // Fenced so the structure survives being shown to a reader or a model.
            markdown = "```json\n$rendered\n```".take(maxChars),
            canonicalUrl = null,
            links = collectLinks(element),
            metadata = PageMetadata(description = summary(element)),
        )
    }

    /** Indented key/value lines: readable, and every value stays searchable. */
    private fun render(element: JsonElement, out: StringBuilder, depth: Int) {
        val pad = "  ".repeat(depth)
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                when (value) {
                    is JsonPrimitive -> out.append(pad).append(key).append(": ").append(value.text()).append('\n')
                    else -> {
                        out.append(pad).append(key).append(":\n")
                        render(value, out, depth + 1)
                    }
                }
            }

            is JsonArray -> element.forEachIndexed { index, value ->
                when (value) {
                    is JsonPrimitive -> out.append(pad).append("- ").append(value.text()).append('\n')
                    else -> {
                        out.append(pad).append("- [").append(index).append("]\n")
                        render(value, out, depth + 1)
                    }
                }
            }

            is JsonPrimitive -> out.append(pad).append(element.text()).append('\n')
        }
    }

    private fun JsonPrimitive.text(): String = if (this is JsonNull) "" else content

    private fun title(element: JsonElement): String? = (element as? JsonObject)
        ?.let { obj -> TITLE_KEYS.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.text() } }
        ?.takeIf { it.isNotBlank() }

    private fun summary(element: JsonElement): String? = (element as? JsonObject)
        ?.let { obj -> SUMMARY_KEYS.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.text() } }
        ?.takeIf { it.isNotBlank() }

    /** Endpoints link onwards; keeping those makes a response navigable. */
    private fun collectLinks(element: JsonElement): List<Link> = buildList {
        fun walk(node: JsonElement, key: String?) {
            when (node) {
                is JsonObject -> node.forEach { (name, value) -> walk(value, name) }
                is JsonArray -> node.forEach { walk(it, key) }
                is JsonPrimitive -> {
                    val value = node.text()
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        add(Link(url = value, text = key.orEmpty()))
                    }
                }
            }
        }
        walk(element, null)
    }.distinctBy { it.url }.take(MAX_LINKS)

    private fun decodeFeed(body: String, url: String, maxChars: Int): Extracted {
        val entries = ENTRY.findAll(body).map { it.groupValues[1] }.toList()

        val rendered = entries.joinToString("\n\n") { entry ->
            val title = tag(entry, "title").orEmpty()
            val link = tag(entry, "link") ?: attribute(entry, "link", "href").orEmpty()
            val summary = tag(entry, "description") ?: tag(entry, "summary") ?: tag(entry, "content").orEmpty()
            buildString {
                append("## ").append(title).append('\n')
                if (link.isNotBlank()) append(link).append('\n')
                if (summary.isNotBlank()) append(summary)
            }
        }.take(maxChars)

        val links = entries.mapNotNull { entry ->
            val href = tag(entry, "link") ?: attribute(entry, "link", "href")
            href?.let { Link(url = it, text = tag(entry, "title").orEmpty()) }
        }

        return Extracted(
            title = tag(body.substringBefore("<item").substringBefore("<entry"), "title") ?: url,
            text = rendered.replace(Regex("^#+ ", RegexOption.MULTILINE), ""),
            markdown = rendered,
            canonicalUrl = null,
            links = links,
            metadata = PageMetadata(),
        )
    }

    private fun decodeXml(body: String, url: String, maxChars: Int): Extracted {
        val text = body
            .replace(Regex("<\\?xml[^>]*\\?>"), "")
            .replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

        return Extracted(url, text, text, null, emptyList(), PageMetadata())
    }

    private fun plain(body: String, url: String, maxChars: Int, markdown: Boolean): Extracted {
        val text = body.take(maxChars)
        val title = if (markdown) {
            HEADING.find(body)?.groupValues?.get(1)?.trim()
        } else {
            body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_LENGTH)
        }
        return Extracted(title, text, text, null, emptyList(), PageMetadata())
    }

    private fun tag(source: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun attribute(source: String, tag: String, attribute: String): String? =
        Regex("<$tag[^>]*$attribute=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(1)

    private val ENTRY = Regex("<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HEADING = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)

    private val TITLE_KEYS = listOf("name", "title", "full_name", "id")
    private val SUMMARY_KEYS = listOf("description", "summary", "about")

    private const val MAX_LINKS = 50
    private const val TITLE_LENGTH = 120
}
