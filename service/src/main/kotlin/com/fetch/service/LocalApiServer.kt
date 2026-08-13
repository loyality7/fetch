package com.fetch.service

import com.fetch.core.engine.WebEngine
import com.fetch.service.api.installRoutes
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Loopback-only HTTP surface, so agents in other processes can use the engine.
 *
 * Binds 127.0.0.1 and nothing else. Every request carries a bearer token —
 * loopback alone is not a security boundary on a device with other apps on it.
 */
public class LocalApiServer(
    private val engine: WebEngine,
    private val token: String,
    private val port: Int = DEFAULT_PORT,
) {

    private val server = embeddedServer(CIO, port = port, host = LOOPBACK) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        installRoutes(engine, token)
    }

    public fun start() {
        server.start(wait = false)
    }

    public fun stop() {
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }

    public companion object {
        public const val DEFAULT_PORT: Int = 8471
        private const val LOOPBACK = "127.0.0.1"
    }
}
