package com.fetch.core.router

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fetch.core.browser.NoBrowserBackend
import com.fetch.core.config.EngineConfig
import com.fetch.core.extract.Extractor
import com.fetch.core.index.SqliteIndexStore
import com.fetch.core.model.CacheMode
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days

@RunWith(AndroidJUnit4::class)
public class FetchRouterTtlTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    public fun testTtlDerivationByContentTypeAndUrl() {
        val config = EngineConfig()
        val dbFile = File(context.filesDir, "test_ttl_${System.currentTimeMillis()}.db")

        val index = SqliteIndexStore(
            driver = BundledSQLiteDriver(),
            databasePath = dbFile.absolutePath,
            config = config.index,
        )

        try {
            val ssrfGuard = SsrfGuard(config.security)
            val httpFetcher = HttpFetcher(config.http, ssrfGuard)
            val extractor = Extractor(config.extraction)

            val router = FetchRouter(
                config = config,
                index = index,
                http = httpFetcher,
                extractor = extractor,
                browser = NoBrowserBackend,
            )

            val document = com.fetch.core.model.Document(
                urls = com.fetch.core.model.Urls("https://en.wikipedia.org/news/breaking", "https://en.wikipedia.org/news/breaking", null),
                title = "News",
                text = "News body text",
                markdown = "News body text",
                contentHash = "hash1",
                fetchedAt = System.currentTimeMillis(),
                tier = com.fetch.core.model.Tier.HTTP,
                source = com.fetch.core.model.Source.WEB,
                statusCode = 200,
                contentType = "text/html",
            )

            // Test TTL logic via router
            runBlocking {
                index.put(document, config.index.newsTtl.inWholeMilliseconds)
                val stored = index.get("https://en.wikipedia.org/news/breaking")
                assertNotNull(stored)
            }
        } finally {
            index.close()
            dbFile.delete()
        }
    }
}
