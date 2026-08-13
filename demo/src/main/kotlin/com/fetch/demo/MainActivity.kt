package com.fetch.demo

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fetch.core.config.EngineConfig
import com.fetch.core.engine.WebEngineImpl
import com.fetch.core.error.EngineException
import com.fetch.core.extract.Extractor
import com.fetch.core.index.SqliteIndexStore
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
import com.fetch.core.router.FetchRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manual harness for the engine while it is built out. Deliberately no UI
 * framework: this exists to exercise the fetch ladder on a real device, not to
 * be an application.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var engine: WebEngineImpl
    private lateinit var input: EditText
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = buildEngine()
        setContentView(buildLayout())
        showIndexStats()
    }

    private fun buildEngine(): WebEngineImpl {
        val config = EngineConfig()
        val extractor = Extractor(config.extraction)
        val guard = SsrfGuard(config.security)
        val index = SqliteIndexStore(
            driver = BundledSQLiteDriver(),
            databasePath = File(filesDir, config.index.databaseName).absolutePath,
            config = config.index,
        )

        return WebEngineImpl(
            config = config,
            index = index,
            router = FetchRouter(
                config = config,
                index = index,
                http = HttpFetcher(config.http, guard),
                extractor = extractor,
                // No browser tier yet: pages needing JavaScript report
                // JS_REQUIRED rather than silently returning an empty shell.
                browser = com.fetch.core.browser.NoBrowserBackend,
            ),
            extractor = extractor,
            discovery = null,
        )
    }

    private fun buildLayout(): LinearLayout {
        input = EditText(this).apply {
            hint = "https://example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }

        val fetchButton = Button(this).apply {
            text = "Fetch"
            setOnClickListener { fetch() }
        }

        val searchButton = Button(this).apply {
            text = "Search index (offline)"
            setOnClickListener { searchIndex() }
        }

        val presets = HorizontalScrollView(this).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    SAMPLES.forEach { (label, url) ->
                        addView(
                            Button(this@MainActivity).apply {
                                text = label
                                textSize = 11f
                                setOnClickListener {
                                    input.setText(url)
                                    fetch()
                                }
                            },
                        )
                    }
                },
            )
        }

        output = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    addView(fetchButton)
                    addView(searchButton)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(presets, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(
                ScrollView(this@MainActivity).apply { addView(output) },
                LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
        }
    }

    private fun fetch() {
        val url = input.text.toString().trim().ifEmpty { return }
        output.text = "Fetching…"

        scope.launch {
            val started = System.currentTimeMillis()
            val result = runCatching { withContext(Dispatchers.IO) { engine.open(url) } }
            val elapsed = System.currentTimeMillis() - started

            output.text = result.fold(
                onSuccess = { document ->
                    buildString {
                        appendLine("tier: ${document.tier}   ${elapsed} ms")
                        appendLine("title: ${document.title}")
                        appendLine("links: ${document.links.size}")
                        appendLine("chars: ${document.markdown.length}")
                        appendLine()
                        append(document.markdown.take(PREVIEW_CHARS))
                    }
                },
                onFailure = ::describe,
            )
        }
    }

    private fun searchIndex() {
        val query = input.text.toString().trim().ifEmpty { return }
        output.text = "Searching…"

        scope.launch {
            val started = System.currentTimeMillis()
            val results = withContext(Dispatchers.IO) { engine.search(query) }
            val elapsed = System.currentTimeMillis() - started

            output.text = if (results.results.isEmpty()) {
                "Nothing indexed for \"$query\" (${elapsed} ms)"
            } else {
                buildString {
                    appendLine("${results.results.size} results in ${elapsed} ms, offline")
                    appendLine()
                    results.results.forEach {
                        appendLine(it.title)
                        appendLine(it.url)
                        appendLine(it.snippet.take(SNIPPET_CHARS))
                        appendLine()
                    }
                }
            }
        }
    }

    private fun showIndexStats() {
        scope.launch {
            val health = withContext(Dispatchers.IO) { engine.health() }
            output.text = "Indexed documents: ${health.indexedDocuments}\n" +
                "Index size: ${health.indexSizeBytes} bytes\n\n" +
                "Enter a URL and fetch, or type words and search the index."
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is EngineException -> "${error.code}: ${error.message}"
        else -> "${error::class.simpleName}: ${error.message}"
    }

    override fun onDestroy() {
        scope.cancel()
        engine.close()
        super.onDestroy()
    }

    private companion object {
        /**
         * One target per fetch behaviour worth exercising by hand: plain
         * article, developer docs, structured data, a JSON endpoint, a
         * link-dense index page, and a client-rendered page that should report
         * JS_REQUIRED while there is no browser tier.
         */
        val SAMPLES = listOf(
            "Wikipedia" to "https://en.wikipedia.org/wiki/Web_scraping",
            "Docs" to "https://developer.android.com/reference/android/webkit/WebView",
            "News" to "https://text.npr.org",
            "JSON" to "https://api.github.com/repos/square/okhttp",
            "Links" to "https://news.ycombinator.com",
            "JSON-LD" to "https://www.bbc.com/news",
            "SPA" to "https://react.dev/learn",
        )

        const val PADDING = 32
        const val PREVIEW_CHARS = 4_000
        const val SNIPPET_CHARS = 160
    }
}
