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

        // Metadata first: JSON-LD lives in script tags, which stripNoise removes.
        val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("abs:href")
        val metadata = MetadataExtractor.extract(doc, config.extractJsonLd)

        stripNoise(doc)

        val main = MainContentFinder.find(doc)
        val text = main.text().take(config.maxExtractedChars)
        val markdown = MarkdownWriter.convert(main).take(config.maxExtractedChars)

        return Extracted(
            title = doc.title().takeIf { it.isNotBlank() },
            text = text,
            markdown = markdown,
            canonicalUrl = canonicalUrl,
            links = if (config.extractLinks) extractLinks(doc) else emptyList(),
            metadata = metadata,
        )
    }

    private fun stripNoise(doc: JsoupDocument) {
        doc.select("script, style, noscript, svg, iframe, nav, header, footer, aside").remove()
        doc.select("[aria-hidden=true], [hidden]").remove()
        doc.select("[role=navigation], [role=banner], [role=complementary], [role=search]").remove()

        // Page furniture that sits inside the article container and therefore
        // survives tag-based stripping: editorial notices, tables of contents,
        // share widgets, related-article rails. Matched on class and id because
        // no markup convention exists for any of it.
        doc.select("*").filter { element ->
            val identity = "${element.id()} ${element.className()}"
            identity.isNotBlank() && BOILERPLATE.containsMatchIn(identity)
        }.forEach { it.remove() }
    }

    private companion object {
        /**
         * Anchored on word boundaries: a bare "nav" substring would take out
         * "navigation-free" prose containers and, worse, class names like
         * "innovation".
         */
        val BOILERPLATE = Regex(
            "\\b(" +
                "hatnote|ambox|navbox|infobox|metadata|mw-editsection|catlinks|" +
                "toc|table-of-contents|breadcrumb|" +
                "cookie|consent|gdpr|newsletter|subscribe|paywall|" +
                "share|social|follow-us|" +
                "related|recommended|more-stories|read-next|trending|" +
                "comment|disqus|" +
                "promo|advert|sponsored|banner|popup|modal|overlay|" +
                "skip-link|screen-reader|visually-hidden|sr-only" +
                ")\\b",
            RegexOption.IGNORE_CASE,
        )
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
