package com.fetch.service.api

import com.fetch.core.engine.WebEngine
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.service.dto.ErrorBody
import com.fetch.service.dto.ErrorResponse
import com.fetch.service.dto.OpenRequest
import com.fetch.service.dto.SearchRequest
import com.fetch.service.dto.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

internal fun Application.installRoutes(engine: WebEngine, token: String) {
    routing {
        route("/v1") {
            post("/search") {
                withGuard(token) { requestId ->
                    val body = call.receive<SearchRequest>()
                    call.respond(engine.search(body.query, body.maxResults).toDto(requestId))
                }
            }

            post("/open") {
                withGuard(token) { requestId ->
                    val body = call.receive<OpenRequest>()
                    call.respond(engine.open(body.url).toDto(requestId))
                }
            }

            get("/health") {
                withGuard(token) { requestId ->
                    call.respond(engine.health().toDto(requestId))
                }
            }
        }
    }
}

/**
 * Auth, request id, and error mapping in one place. Internal failures never
 * cross the boundary as stack traces.
 */
private suspend fun io.ktor.server.routing.RoutingContext.withGuard(
    token: String,
    block: suspend (requestId: String) -> Unit,
) {
    val requestId = call.request.headers["X-Request-ID"] ?: UUID.randomUUID().toString()
    val presented = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

    if (presented != token) {
        call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(ErrorBody(ErrorCode.BLOCKED.name, "Invalid or missing token", requestId, false)),
        )
        return
    }

    try {
        block(requestId)
    } catch (e: EngineException) {
        call.respond(
            statusFor(e.code),
            ErrorResponse(ErrorBody(e.code.name, e.message.orEmpty(), requestId, e.retryable)),
        )
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse(ErrorBody(ErrorCode.INTERNAL.name, "Internal error", requestId, false)),
        )
    }
}

private fun statusFor(code: ErrorCode): HttpStatusCode = when (code) {
    ErrorCode.TIMEOUT -> HttpStatusCode.GatewayTimeout
    ErrorCode.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    ErrorCode.SSRF_BLOCKED, ErrorCode.BLOCKED -> HttpStatusCode.Forbidden
    ErrorCode.INDEX_ONLY -> HttpStatusCode.NotFound
    ErrorCode.RESPONSE_TOO_LARGE -> HttpStatusCode.PayloadTooLarge
    ErrorCode.INTERNAL -> HttpStatusCode.InternalServerError
    else -> HttpStatusCode.BadGateway
}
