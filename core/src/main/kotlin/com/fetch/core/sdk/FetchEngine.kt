package com.fetch.core.sdk

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fetch.core.browser.BrowserBackend
import com.fetch.core.browser.NoBrowserBackend
import com.fetch.core.config.EngineConfig
import com.fetch.core.discovery.Discovery
import com.fetch.core.discovery.sources.SourceCatalogue
import com.fetch.core.engine.WebEngine
import com.fetch.core.engine.WebEngineImpl
import com.fetch.core.extract.Extractor
import com.fetch.core.index.SqliteIndexStore
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
import com.fetch.core.router.FetchRouter
import java.io.File

/**
 * Public SDK factory for creating fully wired [WebEngine] instances on Android.
 */
public object FetchEngine {

    /**
     * Creates and initializes a production-ready [WebEngine] instance.
     *
     * @param context Application context for resolving storage paths.
     * @param config Engine configuration limits and options.
     * @param browser Custom browser backend (defaults to [NoBrowserBackend]).
     * @return A thread-safe, ready-to-use [WebEngine].
     */
    public fun create(
        context: Context,
        config: EngineConfig = EngineConfig(),
        browser: BrowserBackend = NoBrowserBackend,
    ): WebEngine {
        val appContext = context.applicationContext
        val dbFile = File(appContext.filesDir, config.index.databaseName)

        val driver = BundledSQLiteDriver()
        val index = SqliteIndexStore(
            driver = driver,
            databasePath = dbFile.absolutePath,
            config = config.index,
        )

        val ssrfGuard = SsrfGuard(config.security)
        val httpFetcher = HttpFetcher(config.http, ssrfGuard)
        val extractor = Extractor(config.extraction)

        val router = FetchRouter(
            config = config,
            index = index,
            http = httpFetcher,
            extractor = extractor,
            browser = browser,
        )

        val discoverySources = SourceCatalogue.build(
            definitions = config.discovery.sources,
            includeUnofficial = config.discovery.includeUnofficialSources,
            userAgent = config.discovery.userAgent,
        )

        val discovery = if (discoverySources.isNotEmpty()) {
            Discovery(sources = discoverySources, config = config.discovery)
        } else {
            null
        }

        return WebEngineImpl(
            config = config,
            index = index,
            router = router,
            extractor = extractor,
            discovery = discovery,
            browserAvailable = { browser.isAvailable() },
        )
    }
}
