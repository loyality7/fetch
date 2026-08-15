package com.fetch.core.discovery

/**
 * Fast, local query intent classifier.
 *
 * Runs without a model (0ms latency requirement) to route queries to specialized
 * verticals (e.g. CODE, DOCS, NEWS, PAPERS) while preserving GENERAL fallback.
 */
public object QueryClassifier {

    private val CODE_KEYWORDS = setOf(
        "function", "class", "method", "import", "def", "var", "const", "let",
        "gradle", "repo", "github", "git", "api", "json", "sdk", "bug", "exception",
        "stacktrace", "error", "interface", "struct", "fun", "return", "throw", "code",
    )

    private val DOCS_KEYWORDS = setOf(
        "docs", "documentation", "guide", "tutorial", "manual", "reference",
        "howto", "how to", "getting started", "readme", "wiki", "api reference",
    )

    private val NEWS_KEYWORDS = setOf(
        "news", "breaking", "today", "latest", "update", "announcement",
        "release", "press", "politic", "event",
    )

    private val PAPERS_KEYWORDS = setOf(
        "arxiv", "paper", "journal", "abstract", "doi", "thesis",
        "preprint", "citation", "dataset", "benchmarks", "benchmark",
    )

    private val CODE_PATTERNS = listOf(
        Regex("[a-zA-Z0-9_]+\\(\\)", RegexOption.IGNORE_CASE), // function calls like foo()
        Regex("^[a-z0-9_-]+\\.[a-z]{1,4}$", RegexOption.IGNORE_CASE), // filenames like main.kt, app.js
        Regex("(?:val|var|fun|class|import|def|let|const)\\s+[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE),
    )

    public fun classify(query: String): Set<Vertical> {
        val normalized = query.lowercase().trim()
        if (normalized.isEmpty()) return setOf(Vertical.GENERAL)

        val detected = mutableSetOf<Vertical>()

        // 1. Code detection
        if (CODE_PATTERNS.any { it.containsMatchIn(query) }) {
            detected += Vertical.CODE
        }
        val words = normalized.split(Regex("[\\s()]+")).map { it.trim(',', '.', ':', ';') }
        if (words.any { it in CODE_KEYWORDS }) {
            detected += Vertical.CODE
        }

        // 2. Documentation detection
        if (words.any { it in DOCS_KEYWORDS } || normalized.contains("how to")) {
            detected += Vertical.DOCS
        }

        // 3. News detection
        if (words.any { it in NEWS_KEYWORDS }) {
            detected += Vertical.NEWS
        }

        // 4. Academic / Papers detection
        if (words.any { it in PAPERS_KEYWORDS }) {
            detected += Vertical.PAPERS
        }

        return detected.ifEmpty { setOf(Vertical.GENERAL) }
    }
}
