package com.fetch.core.extract

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Serialises a cleaned element tree to markdown. */
public object MarkdownWriter {

    public fun convert(root: Element): String = buildString {
        root.childNodes().forEach { render(it, this) }
    }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun render(node: Node, out: StringBuilder, depth: Int = 0) {
        if (depth > 50) {
            out.append(node.outerHtml())
            return
        }
        when (node) {
            is TextNode -> out.append(node.text())
            is Element -> renderElement(node, out, depth)
        }
    }

    private fun renderElement(el: Element, out: StringBuilder, depth: Int = 0) {
        if (depth > 50) {
            out.append(el.text())
            return
        }
        when (el.tagName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tagName().substring(1).toInt()
                out.append("\n\n").append("#".repeat(level)).append(' ').append(el.text()).append("\n\n")
            }

            "p" -> out.append("\n\n").append(inline(el, depth + 1)).append("\n\n")
            "br" -> out.append('\n')
            "hr" -> out.append("\n\n---\n\n")

            "ul", "ol" -> {
                out.append('\n')
                el.children().forEachIndexed { i, li ->
                    val marker = if (el.tagName() == "ol") "${i + 1}." else "-"
                    out.append(marker).append(' ').append(inline(li, depth + 1)).append('\n')
                }
                out.append('\n')
            }

            "pre" -> out.append("\n\n```\n").append(el.text()).append("\n```\n\n")
            "code" -> out.append('`').append(el.text()).append('`')
            "blockquote" -> out.append("\n\n> ").append(el.text()).append("\n\n")
            "table" -> out.append('\n').append(table(el)).append('\n')

            else -> el.childNodes().forEach { render(it, out, depth + 1) }
        }
    }

    private fun inline(el: Element, depth: Int = 0): String = buildString {
        if (depth > 50) {
            append(el.text())
            return@buildString
        }
        el.childNodes().forEach { child ->
            when {
                child is TextNode -> append(child.text())
                child is Element && child.tagName() == "a" ->
                    append('[').append(child.text()).append("](").append(child.attr("abs:href")).append(')')

                child is Element && child.tagName() in setOf("strong", "b") ->
                    append("**").append(child.text()).append("**")

                child is Element && child.tagName() in setOf("em", "i") ->
                    append('*').append(child.text()).append('*')

                child is Element && child.tagName() == "code" ->
                    append('`').append(child.text()).append('`')

                child is Element -> append(inline(child, depth + 1))
            }
        }
    }

    private fun table(el: Element): String {
        val rows = el.select("tr")
        if (rows.isEmpty()) return ""

        return buildString {
            rows.forEachIndexed { index, row ->
                val cells = row.select("th, td").map { it.text().replace('|', '\\') }
                append("| ").append(cells.joinToString(" | ")).append(" |\n")
                if (index == 0) {
                    append("| ").append(cells.joinToString(" | ") { "---" }).append(" |\n")
                }
            }
        }
    }
}
