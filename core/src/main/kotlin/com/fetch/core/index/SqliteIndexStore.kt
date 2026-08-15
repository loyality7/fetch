package com.fetch.core.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import com.fetch.core.config.IndexConfig
import com.fetch.core.model.Document
import com.fetch.core.model.Link
import com.fetch.core.model.PageMetadata
import com.fetch.core.model.SearchResult
import com.fetch.core.model.Source
import com.fetch.core.model.Tier
import com.fetch.core.model.Urls
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The local index, on bundled SQLite with FTS5.
 *
 * Ranking uses bm25() with the title weighted above the body, which is why this
 * talks to SQLite directly: FTS5's ranking surface is invisible to SQL model
 * validators.
 */
public class SqliteIndexStore(
    driver: SQLiteDriver,
    databasePath: String,
    private val config: IndexConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IndexStore {

    private val connection: SQLiteConnection = driver.open(databasePath)

    // SQLite handles one writer at a time; serialising here is simpler and
    // cheaper than a connection pool for a single-process embedded database.
    // ponytail: single mutex, revisit if concurrent reads become a bottleneck.
    private val lock = Mutex()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        connection.execSQL("PRAGMA journal_mode = WAL")
        connection.execSQL("PRAGMA foreign_keys = ON")
        Schema.STATEMENTS.forEach(connection::execSQL)
        connection.execSQL("PRAGMA user_version = ${Schema.VERSION}")
    }

    override suspend fun get(url: String): Document? = withContext(dispatcher) {
        lock.withLock {
            val now = System.currentTimeMillis()
            connection.prepare(
                "SELECT * FROM document WHERE url = ? AND expires_at > ?",
            ).use { statement ->
                statement.bindText(1, url)
                statement.bindLong(2, now)
                if (!statement.step()) return@withContext null
                readDocument(statement)
            }.also {
                connection.prepare("UPDATE document SET accessed_at = ? WHERE url = ?").use { touch ->
                    touch.bindLong(1, now)
                    touch.bindText(2, url)
                    touch.step()
                }
            }
        }
    }

    override suspend fun getRaw(url: String): Document? = withContext(dispatcher) {
        lock.withLock {
            connection.prepare(
                "SELECT * FROM document WHERE url = ?",
            ).use { statement ->
                statement.bindText(1, url)
                if (!statement.step()) return@withContext null
                readDocument(statement)
            }
        }
    }

    override suspend fun getByContentHash(hash: String): Document? = withContext(dispatcher) {
        lock.withLock {
            connection.prepare(
                "SELECT * FROM document WHERE content_hash = ? ORDER BY fetched_at DESC LIMIT 1",
            ).use { statement ->
                statement.bindText(1, hash)
                if (!statement.step()) return@withContext null
                readDocument(statement)
            }
        }
    }

    override suspend fun put(document: Document, ttlMillis: Long): Unit = withContext(dispatcher) {
        lock.withLock {
            val now = System.currentTimeMillis()
            connection.prepare(
                """
                INSERT OR REPLACE INTO document(
                    url, final_url, canonical_url, title, text, markdown, content_hash,
                    fetched_at, expires_at, tier, source, status_code, content_type,
                    etag, last_modified, metadata_json, links_json, size_bytes, accessed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            ).use { statement ->
                statement.bindText(1, document.urls.requested)
                statement.bindText(2, document.urls.final)
                document.urls.canonical?.let { statement.bindText(3, it) } ?: statement.bindNull(3)
                document.title?.let { statement.bindText(4, it) } ?: statement.bindNull(4)
                statement.bindText(5, document.text)
                statement.bindText(6, document.markdown)
                statement.bindText(7, document.contentHash)
                statement.bindLong(8, document.fetchedAt)
                statement.bindLong(9, now + ttlMillis)
                statement.bindText(10, document.tier.name)
                statement.bindText(11, document.source.name)
                document.statusCode?.let { statement.bindLong(12, it.toLong()) } ?: statement.bindNull(12)
                document.contentType?.let { statement.bindText(13, it) } ?: statement.bindNull(13)
                document.etag?.let { statement.bindText(14, it) } ?: statement.bindNull(14)
                document.lastModified?.let { statement.bindText(15, it) } ?: statement.bindNull(15)
                statement.bindText(
                    16,
                    json.encodeToString(MetadataRow.serializer(), MetadataRow.from(document.metadata)),
                )
                statement.bindText(
                    17,
                    json.encodeToString(ListSerializer(LinkRow.serializer()), document.links.map(LinkRow::from)),
                )
                statement.bindLong(18, document.markdown.length.toLong())
                statement.bindLong(19, now)
                statement.step()
            }
        }
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> = withContext(dispatcher) {
        lock.withLock {
            val results = mutableListOf<SearchResult>()
            connection.prepare(
                """
                SELECT d.url, d.title, d.text, bm25(document_fts, 10.0, 1.0) AS score
                FROM document_fts
                JOIN document d ON d.id = document_fts.rowid
                WHERE document_fts MATCH ?
                  AND d.expires_at > ?
                ORDER BY score
                LIMIT ?
                """,
            ).use { statement ->
                statement.bindText(1, escapeFts(query))
                statement.bindLong(2, System.currentTimeMillis())
                statement.bindLong(3, limit.toLong())

                while (statement.step()) {
                    val text = statement.getText(2)
                    results += SearchResult(
                        title = if (statement.isNull(1)) "" else statement.getText(1),
                        url = statement.getText(0),
                        snippet = text.take(SNIPPET_LENGTH),
                        // bm25 returns lower-is-better; invert so higher is better
                        // and the value composes with fused live results.
                        score = -statement.getDouble(3),
                        sources = listOf(INDEX_SOURCE),
                        fromIndex = true,
                    )
                }
            }
            results
        }
    }

    override suspend fun routingFor(domain: String): DomainRouting? = withContext(dispatcher) {
        lock.withLock {
            connection.prepare("SELECT * FROM domain_routing WHERE domain = ?").use { statement ->
                statement.bindText(1, domain)
                if (!statement.step()) return@withContext null
                DomainRouting(
                    domain = statement.getText(0),
                    tier = statement.getText(1),
                    lastSuccessAt = statement.getLong(2),
                    failureCount = statement.getLong(3).toInt(),
                    backoffUntil = if (statement.isNull(4)) null else statement.getLong(4),
                )
            }
        }
    }

    override suspend fun recordRouting(routing: DomainRouting): Unit = withContext(dispatcher) {
        lock.withLock {
            connection.prepare(
                """
                INSERT OR REPLACE INTO domain_routing(domain, tier, last_success_at, failure_count, backoff_until)
                VALUES (?, ?, ?, ?, ?)
                """,
            ).use { statement ->
                statement.bindText(1, routing.domain)
                statement.bindText(2, routing.tier)
                statement.bindLong(3, routing.lastSuccessAt)
                statement.bindLong(4, routing.failureCount.toLong())
                routing.backoffUntil?.let { statement.bindLong(5, it) } ?: statement.bindNull(5)
                statement.step()
            }
        }
    }

    override suspend fun stats(): IndexStats = withContext(dispatcher) {
        lock.withLock {
            connection.prepare("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM document").use { statement ->
                statement.step()
                IndexStats(documentCount = statement.getLong(0), sizeBytes = statement.getLong(1))
            }
        }
    }

    override suspend fun prune(): Unit = withContext(dispatcher) {
        lock.withLock {
            connection.prepare("DELETE FROM document WHERE expires_at < ?").use { statement ->
                statement.bindLong(1, System.currentTimeMillis())
                statement.step()
            }

            val size = connection.prepare("SELECT COALESCE(SUM(size_bytes), 0) FROM document").use {
                it.step()
                it.getLong(0)
            }
            if (size <= config.maxSizeBytes) return@withLock

            // Drop the oldest-accessed tenth rather than computing an exact
            // cut-off; eviction runs often enough that precision buys nothing.
            connection.execSQL(
                "DELETE FROM document WHERE id IN " +
                    "(SELECT id FROM document ORDER BY accessed_at ASC LIMIT " +
                    "(SELECT MAX(1, COUNT(*) / 10) FROM document))",
            )
        }
    }

    override fun close() {
        connection.close()
    }

    private fun readDocument(statement: androidx.sqlite.SQLiteStatement): Document {
        val metadata = json.decodeFromString(MetadataRow.serializer(), statement.getText(16))
        val links = json.decodeFromString(ListSerializer(LinkRow.serializer()), statement.getText(17))

        return Document(
            urls = Urls(
                requested = statement.getText(1),
                final = statement.getText(2),
                canonical = if (statement.isNull(3)) null else statement.getText(3),
            ),
            title = if (statement.isNull(4)) null else statement.getText(4),
            text = statement.getText(5),
            markdown = statement.getText(6),
            contentHash = statement.getText(7),
            fetchedAt = statement.getLong(8),
            tier = Tier.INDEX,
            source = Source.valueOf(statement.getText(11)),
            statusCode = if (statement.isNull(12)) null else statement.getLong(12).toInt(),
            contentType = if (statement.isNull(13)) null else statement.getText(13),
            etag = if (statement.isNull(14)) null else statement.getText(14),
            lastModified = if (statement.isNull(15)) null else statement.getText(15),
            links = links.map { Link(it.url, it.text, it.rel) },
            metadata = metadata.toModel(),
        )
    }

    /**
     * FTS5 treats several characters as query syntax. Quoting each term keeps a
     * user's punctuation from being read as an operator.
     */
    private fun escapeFts(query: String): String =
        query.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"" }

    private companion object {
        const val SNIPPET_LENGTH = 300
        const val INDEX_SOURCE = "index"
    }
}

@kotlinx.serialization.Serializable
private data class LinkRow(val url: String, val text: String, val rel: String? = null) {
    companion object {
        fun from(link: Link) = LinkRow(link.url, link.text, link.rel)
    }
}

@kotlinx.serialization.Serializable
private data class MetadataRow(
    val description: String? = null,
    val author: String? = null,
    val publishedAt: Long? = null,
    val siteName: String? = null,
    val language: String? = null,
    val jsonLd: List<String> = emptyList(),
    val openGraph: Map<String, String> = emptyMap(),
) {
    fun toModel() = PageMetadata(description, author, publishedAt, siteName, language, jsonLd, openGraph)

    companion object {
        fun from(metadata: PageMetadata) = MetadataRow(
            metadata.description,
            metadata.author,
            metadata.publishedAt,
            metadata.siteName,
            metadata.language,
            metadata.jsonLd,
            metadata.openGraph,
        )
    }
}
