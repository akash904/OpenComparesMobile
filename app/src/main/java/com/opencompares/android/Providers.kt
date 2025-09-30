package com.opencompares.android

import java.net.URLEncoder

data class Provider(
    val key: String,
    val displayName: String,
    val urlTemplate: String,
    val overrideUserAgent: String? = null,
    val homeUrl: String? = null,
    val defaultZoom: Int = 50
) {
    fun buildUrl(query: String): String {
        val enc = URLEncoder.encode(query, Charsets.UTF_8.name())
        return urlTemplate
            .replace("{QUERY}", enc, false)
            .replace("{query}", enc, false)
    }

    /** Prefer explicit homeUrl, else fall back to "search home" (empty query). */
    fun home(): String = homeUrl ?: buildUrl("")
}

val MVP_PROVIDERS = listOf(
    Provider(
        key = "blinkit",
        displayName = "Blinkit",
        urlTemplate = "https://blinkit.com/s/?q={QUERY}",
        homeUrl = "https://blinkit.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "zepto",
        displayName = "Zepto",
        urlTemplate = "https://www.zeptonow.com/search?query={QUERY}",
        homeUrl = "https://www.zeptonow.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "instamart",
        displayName = "Instamart",
        urlTemplate = "https://www.swiggy.com/instamart/search?custom_back=true&query={QUERY}",
        homeUrl = "https://www.swiggy.com/instamart",
        overrideUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        defaultZoom = 100
    )
)
