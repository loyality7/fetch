package com.fetch.core.extract

import org.jsoup.nodes.Element
import org.jsoup.nodes.Document as JsoupDocument

/**
 * Picks the element holding the article, so the index stores the page's point
 * rather than its chrome.
 *
 * Readability-style scoring: text density beats tag names, because every site
 * disagrees about tag names and none of them disagree about where the words are.
 */
public object MainContentFinder {

    private val SEMANTIC_SELECTORS = listOf("article", "main", "[role=main]")

    private val NEGATIVE = Regex(
        "comment|sidebar|footer|header|menu|nav|share|related|promo|advert|banner|cookie",
        RegexOption.IGNORE_CASE,
    )

    public fun find(doc: JsoupDocument): Element {
        val candidate = SEMANTIC_SELECTORS.firstNotNullOfOrNull { doc.selectFirst(it) }
            ?.takeIf { it.text().length > MIN_BLOCK_LENGTH }
            ?: doc.select("div, section, td")
                .filter { it.text().length > MIN_BLOCK_LENGTH }
                .maxByOrNull(::score)
            ?: doc.body()

        return narrow(candidate)
    }

    /**
     * Walk down while a single child still holds nearly all the text.
     *
     * Semantic wrappers are usually a layer or two above the prose, and the
     * layers in between carry the page's own furniture — breadcrumbs, notices,
     * tab strips. Descending sheds those without needing to recognise them.
     */
    private fun narrow(element: Element): Element {
        var current = element
        while (true) {
            val length = current.text().length
            if (length < MIN_BLOCK_LENGTH) return current

            val dominant = current.children()
                .filter { it.text().length >= length * DOMINANT_TEXT_SHARE }
                .takeIf { it.size == 1 }
                ?.first()
                ?: return current

            // Never descend into content itself. A container whose text happens
            // to sit in one long paragraph still owns the headings and lists
            // around it, and stepping inside would discard them.
            if (dominant.tagName() in CONTENT_TAGS) return current

            current = dominant
        }
    }

    /**
     * Text length weighted by paragraph count and penalised for link density —
     * navigation blocks are long but are almost entirely links.
     */
    private fun score(element: Element): Double {
        val text = element.text()
        if (text.isEmpty()) return 0.0

        val idAndClass = "${element.id()} ${element.className()}"
        if (NEGATIVE.containsMatchIn(idAndClass)) return 0.0

        val linkLength = element.select("a").sumOf { it.text().length }
        val linkDensity = linkLength.toDouble() / text.length
        val paragraphs = element.select("p").size

        return text.length * (1 - linkDensity) * (1 + paragraphs * 0.1)
    }

    private const val MIN_BLOCK_LENGTH = 200
    private const val DOMINANT_TEXT_SHARE = 0.9

    private val CONTENT_TAGS = setOf(
        "p", "span", "li", "td", "th", "pre", "code", "blockquote", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )
}
