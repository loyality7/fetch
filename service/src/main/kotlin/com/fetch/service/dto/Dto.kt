package com.fetch.service.dto

import com.fetch.core.engine.Health
import com.fetch.core.model.Document
import com.fetch.core.model.SearchResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types, kept separate from the core models on purpose: the HTTP contract
 * is versioned and public, the internal model is neither.
 */

@Serializable
public data class SearchRequest(
    val query: String,
    @SerialName("max_results") val maxResults: Int = 10,
)

@Serializable
public data class OpenRequest(
    val url: String,
    @SerialName("cache_mode") val cacheMode: String = "default",
)

@Serializable
public data class SearchResultDto(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Double,
    val sources: List<String>,
)

@Serializable
public data class SearchResponseDto(
    val query: String,
    val results: List<SearchResultDto>,
    @SerialName("duration_ms") val durationMs: Long,
    val cached: Boolean,
    @SerialName("request_id") val requestId: String,
)

@Serializable
public data class DocumentDto(
    val url: String,
    @SerialName("final_url") val finalUrl: String,
    val title: String?,
    val content: String,
    @SerialName("fetched_at") val fetchedAt: Long,
    val cached: Boolean,
    @SerialName("request_id") val requestId: String,
)

@Serializable
public data class HealthDto(
    @SerialName("api_version") val apiVersion: String = "v1",
    @SerialName("engine_version") val engineVersion: String,
    val capabilities: Map<String, Boolean>,
    @SerialName("indexed_documents") val indexedDocuments: Long,
    @SerialName("index_size_bytes") val indexSizeBytes: Long,
    @SerialName("request_id") val requestId: String,
)

@Serializable
public data class ErrorBody(
    val code: String,
    val message: String,
    @SerialName("request_id") val requestId: String,
    val retryable: Boolean,
)

@Serializable
public data class ErrorResponse(val error: ErrorBody)

internal fun SearchResponse.toDto(requestId: String) = SearchResponseDto(
    query = query,
    results = results.map { SearchResultDto(it.title, it.url, it.snippet, it.score, it.sources) },
    durationMs = durationMs,
    cached = fromIndex,
    requestId = requestId,
)

internal fun Document.toDto(requestId: String) = DocumentDto(
    url = urls.requested,
    finalUrl = urls.final,
    title = title,
    content = markdown,
    fetchedAt = fetchedAt,
    cached = tier == com.fetch.core.model.Tier.INDEX,
    requestId = requestId,
)

internal fun Health.toDto(requestId: String) = HealthDto(
    engineVersion = engineVersion,
    capabilities = capabilities,
    indexedDocuments = indexedDocuments,
    indexSizeBytes = indexSizeBytes,
    requestId = requestId,
)
