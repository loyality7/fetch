package com.fetch.core.discovery.sources

import com.fetch.core.discovery.Vertical

/**
 * A search source described as data rather than code.
 *
 * Sources are meant to be disposable: one changes its response shape, another
 * starts rate-limiting, an application wants one the engine has never heard of.
 * Describing them declaratively means adding, editing or removing a source is a
 * configuration change, not a release.
 *
 * Fields are read by dotted path. `query.search` walks two objects; an array
 * anywhere along the path is traversed.
 *
 * @param name reported in results and health
 * @param endpoint request URL, with `{query}` and `{limit}` substituted
 * @param resultsPath path to the array of results in the response
 * @param titlePath path to the title, relative to a result
 * @param urlPath path to the result URL, relative to a result
 * @param urlTemplate builds the URL when the response carries no usable link;
 *   `{field}` is substituted from the result, so `https://x/wiki/{title}` works
 * @param snippetPath optional path to a summary
 * @param datePath optional path to a timestamp in seconds
 * @param official false for sources that parse a page rather than an interface,
 *   which is what keeps them disabled unless an application opts in
 */
public data class SourceDefinition(
    val name: String,
    val endpoint: String,
    val resultsPath: String,
    val titlePath: String,
    val urlPath: String? = null,
    val urlTemplate: String? = null,
    val snippetPath: String? = null,
    val datePath: String? = null,
    val verticals: Set<Vertical> = setOf(Vertical.GENERAL),
    val official: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(urlPath != null || urlTemplate != null) {
            "$name: a source needs either urlPath or urlTemplate"
        }
    }
}
