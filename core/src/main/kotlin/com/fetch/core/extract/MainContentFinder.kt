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
        SEMANTIC_SELECTORS.firstNotNullOfOrNull { doc.selectFirst(it) }
            ?.takeIf { it.text().length > 200 }
            ?.let { return it }

        return doc.select("div, section, td")
            .filter { it.text().length > 200 }
            .maxByOrNull(::score)
            ?: doc.body()
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
}
