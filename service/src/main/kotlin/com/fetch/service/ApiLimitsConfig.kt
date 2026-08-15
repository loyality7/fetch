package com.fetch.service

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration limits enforced by the loopback HTTP server.
 */
public data class ApiLimitsConfig(
    val maxRequestBodyBytes: Long = 2L * 1024 * 1024, // 2MB
    val maxQueryLengthChars: Int = 1_000,
    val maxUrlLengthChars: Int = 2_048,
    val defaultRequestTimeout: Duration = 30.seconds,
)
