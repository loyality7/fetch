package com.fetch.core.extract

import com.fetch.core.config.ExtractionConfig
import com.fetch.core.model.Link
import com.fetch.core.model.PageMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Document as JsoupDocument

/**
 * HTML in, clean content out.
 *
 * The engine stores markdown, not HTML: it is a fraction of the size, it is
 * what an agent actually consumes, and it survives being chunked for retrieval.
 */
public class Extractor(private val config: ExtractionConfig) {

    public fun extract(html: String, baseUrl: String): Extracted {
        val doc = Jsoup.parse(html, baseUrl)
        stripNoise(doc)

        val main = MainContentFinder.find(doc)
        val text = main.text().take(config.maxExtractedChars)
        val markdown = MarkdownWriter.convert(main).take(config.maxExtractedChars)

        return Extracted(
            title = doc.title().takeIf { it.isNotBlank() },
            text = text,
            markdown = markdown,
            canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("abs:href"),
            links = if (config.extractLinks) extractLinks(doc) else emptyList(),
            metadata = MetadataExtractor.extract(doc, config.extractJsonLd),
        )
    }

    private fun stripNoise(doc: JsoupDocument) {
        doc.select("script, style, noscript, svg, iframe, nav, header, footer, aside").remove()
        doc.select("[aria-hidden=true], [hidden]").remove()
    }

    private fun extractLinks(doc: JsoupDocument): List<Link> =
        doc.select("a[href]")
            .map { Link(url = it.attr("abs:href"), text = it.text().trim(), rel = it.attr("rel").ifEmpty { null }) }
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
}

public data class Extracted(
    val title: String?,
    val text: String,
    val markdown: String,
    val canonicalUrl: String?,
    val links: List<Link>,
    val metadata: PageMetadata,
)
