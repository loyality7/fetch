package com.fetch.core.extract

import com.fetch.core.model.PageMetadata
import org.jsoup.nodes.Document as JsoupDocument

public object MetadataExtractor {

    public fun extract(doc: JsoupDocument, includeJsonLd: Boolean): PageMetadata {
        val og = doc.select("meta[property^=og:]")
            .associate { it.attr("property").removePrefix("og:") to it.attr("content") }
            .filterValues { it.isNotBlank() }

        return PageMetadata(
            description = meta(doc, "description") ?: og["description"],
            author = meta(doc, "author") ?: doc.selectFirst("[rel=author]")?.text(),
            publishedAt = parseDate(
                doc.selectFirst("meta[property=article:published_time]")?.attr("content")
                    ?: doc.selectFirst("time[datetime]")?.attr("datetime"),
            ),
            siteName = og["site_name"],
            language = doc.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() },
            jsonLd = if (includeJsonLd) {
                doc.select("script[type=application/ld+json]").map { it.data() }
            } else {
                emptyList()
            },
            openGraph = og,
        )
    }

    private fun meta(doc: JsoupDocument, name: String): String? =
        doc.selectFirst("meta[name=$name]")?.attr("content")?.takeIf { it.isNotBlank() }

    private fun parseDate(raw: String?): Long? = raw?.let {
        runCatching { java.time.Instant.parse(it).toEpochMilli() }
            .recoverCatching { java.time.LocalDate.parse(it).toEpochDay() * 86_400_000L }
            .getOrNull()
    }
}
