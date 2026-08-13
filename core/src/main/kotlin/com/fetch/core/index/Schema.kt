package com.fetch.core.index

/**
 * Schema DDL, applied on open and versioned by [VERSION].
 *
 * Written as raw SQL rather than through an ORM or a typed-SQL generator: both
 * validate SQL against a model that does not include FTS5 internals such as the
 * implicit `rank` column or `bm25()`, and those are exactly what ranked search
 * needs.
 *
 * Semantic search arrives in a later version as a chunk-and-embedding table
 * alongside these. Keeping the document store on plain SQLite means that
 * addition is a migration rather than a second database to keep in sync.
 */
internal object Schema {

    const val VERSION: Int = 1

    /**
     * One table for fetched pages, user-added content and crawl output alike, so
     * retrieval never has to care where something came from.
     */
    val STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS document (
            id            INTEGER PRIMARY KEY AUTOINCREMENT,
            url           TEXT NOT NULL UNIQUE,
            final_url     TEXT NOT NULL,
            canonical_url TEXT,
            title         TEXT,
            text          TEXT NOT NULL,
            markdown      TEXT NOT NULL,
            content_hash  TEXT NOT NULL,
            fetched_at    INTEGER NOT NULL,
            expires_at    INTEGER NOT NULL,
            tier          TEXT NOT NULL,
            source        TEXT NOT NULL,
            status_code   INTEGER,
            content_type  TEXT,
            etag          TEXT,
            last_modified TEXT,
            metadata_json TEXT,
            links_json    TEXT,
            size_bytes    INTEGER NOT NULL,
            accessed_at   INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS document_hash ON document(content_hash)",
        "CREATE INDEX IF NOT EXISTS document_expiry ON document(expires_at)",
        // LRU eviction scans this.
        "CREATE INDEX IF NOT EXISTS document_accessed ON document(accessed_at)",

        """
        CREATE VIRTUAL TABLE IF NOT EXISTS document_fts USING fts5(
            title,
            text,
            content = 'document',
            content_rowid = 'id',
            tokenize = 'porter unicode61'
        )
        """,

        // External-content FTS5 tables are not updated automatically.
        """
        CREATE TRIGGER IF NOT EXISTS document_fts_insert AFTER INSERT ON document BEGIN
            INSERT INTO document_fts(rowid, title, text) VALUES (new.id, new.title, new.text);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS document_fts_delete AFTER DELETE ON document BEGIN
            INSERT INTO document_fts(document_fts, rowid, title, text)
            VALUES ('delete', old.id, old.title, old.text);
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS document_fts_update AFTER UPDATE ON document BEGIN
            INSERT INTO document_fts(document_fts, rowid, title, text)
            VALUES ('delete', old.id, old.title, old.text);
            INSERT INTO document_fts(rowid, title, text) VALUES (new.id, new.title, new.text);
        END
        """,

        // What the router learned per host. Starting at the tier that worked last
        // time is the difference between launching a browser and not.
        """
        CREATE TABLE IF NOT EXISTS domain_routing (
            domain          TEXT PRIMARY KEY NOT NULL,
            tier            TEXT NOT NULL,
            last_success_at INTEGER NOT NULL,
            failure_count   INTEGER NOT NULL DEFAULT 0,
            backoff_until   INTEGER
        )
        """,

        """
        CREATE TABLE IF NOT EXISTS search_cache (
            query        TEXT PRIMARY KEY NOT NULL,
            results_json TEXT NOT NULL,
            fetched_at   INTEGER NOT NULL,
            expires_at   INTEGER NOT NULL
        )
        """,
    )
}
