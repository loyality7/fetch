package com.fetch.core.extract

/**
 * What a response actually is, decided before anything tries to read it.
 *
 * An HTML parser will happily accept a JSON document and return the braces as
 * text, so the type has to be settled first. The declared header is trusted
 * when it says something useful and the body is sniffed when it does not, which
 * is common on endpoints that answer `text/plain` for everything.
 */
public enum class ContentType {
    HTML,
    JSON,
    XML,
    FEED,
    MARKDOWN,
    TEXT,
    UNSUPPORTED,
    ;

    public companion object {

        public fun detect(declared: String?, body: String, url: String = ""): ContentType {
            val mime = declared?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val head = body.take(SNIFF_LENGTH).trimStart()

            declaredType(mime, head)?.let { return it }
            return sniff(head, url)
        }

        private fun declaredType(mime: String, head: String): ContentType? = when {
            mime.isEmpty() -> null
            mime.contains("html") -> ContentType.HTML
            mime.contains("json") -> ContentType.JSON
            mime.contains("atom") || mime.contains("rss") -> ContentType.FEED
            mime.contains("xml") -> if (isFeed(head)) ContentType.FEED else ContentType.XML
            mime.contains("markdown") -> ContentType.MARKDOWN
            // Endpoints answer text/plain for anything, so keep sniffing.
            mime == "text/plain" -> null
            mime.startsWith("text/") -> ContentType.TEXT
            mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ->
                ContentType.UNSUPPORTED
            mime == "application/pdf" -> ContentType.UNSUPPORTED
            else -> null
        }

        private fun sniff(head: String, url: String): ContentType = when {
            head.startsWith("<!doctype html", ignoreCase = true) -> ContentType.HTML
            head.startsWith("<html", ignoreCase = true) -> ContentType.HTML
            head.startsWith("{") || head.startsWith("[") -> ContentType.JSON
            head.startsWith("<?xml") || head.startsWith("<") ->
                if (isFeed(head)) ContentType.FEED else ContentType.XML
            url.endsWith(".md") -> ContentType.MARKDOWN
            else -> ContentType.TEXT
        }

        private fun isFeed(head: String) =
            head.contains("<rss", ignoreCase = true) ||
                head.contains("<feed", ignoreCase = true) ||
                head.contains("<channel", ignoreCase = true)

        private const val SNIFF_LENGTH = 2_000
    }
}
