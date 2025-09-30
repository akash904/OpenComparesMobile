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
        key = "instamart",
        displayName = "Instamart",
        urlTemplate = "https://www.swiggy.com/instamart/search?custom_back=true&query={QUERY}",
        homeUrl = "https://www.swiggy.com/instamart",
        overrideUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        defaultZoom = 100
    ),
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

    // Marketplace additions
    Provider(
        key = "amazon",
        displayName = "Amazon.in",
        urlTemplate = "https://www.amazon.in/s?k={QUERY}",
        homeUrl = "https://www.amazon.in/",
        defaultZoom = 60
    ),
    Provider(
        key = "flipkart",
        displayName = "Flipkart",
        urlTemplate = "https://www.flipkart.com/search?q={QUERY}",
        homeUrl = "https://www.flipkart.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "myntra",
        displayName = "Myntra",
        urlTemplate = "https://www.myntra.com/{QUERY}",
        homeUrl = "https://www.myntra.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "nykaa",
        displayName = "Nykaa",
        urlTemplate = "https://www.nykaa.com/search?q={QUERY}",
        homeUrl = "https://www.nykaa.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "swiggy",
        displayName = "Swiggy",
        urlTemplate = "https://www.swiggy.com/restaurants?query={QUERY}",
        homeUrl = "https://www.swiggy.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "zomato",
        displayName = "Zomato",
        urlTemplate = "https://www.zomato.com/search?q={QUERY}",
        homeUrl = "https://www.zomato.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "croma",
        displayName = "Croma",
        urlTemplate = "https://www.croma.com/search?q={QUERY}",
        homeUrl = "https://www.croma.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "reliance_digital",
        displayName = "Reliance Digital",
        urlTemplate = "https://www.reliancedigital.in/search?q={QUERY}",
        homeUrl = "https://www.reliancedigital.in/",
        defaultZoom = 50
    ),
    Provider(
        key = "ajio",
        displayName = "Ajio",
        urlTemplate = "https://www.ajio.com/search/?query={QUERY}",
        homeUrl = "https://www.ajio.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "bigbasket",
        displayName = "BigBasket",
        urlTemplate = "https://www.bigbasket.com/ps/?q={QUERY}&nc=as",
        homeUrl = "https://www.bigbasket.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "shoppers_stop",
        displayName = "Shoppers Stop",
        urlTemplate = "https://www.shoppersstop.com/search?q={QUERY}",
        homeUrl = "https://www.shoppersstop.com/",
        defaultZoom = 50
    ),
    Provider(
        key = "google_shopping",
        displayName = "Google Shopping",
        urlTemplate = "https://www.google.com/search?tbm=shop&q={QUERY}",
        homeUrl = "https://www.google.com/shopping",
        defaultZoom = 50
    ),
    Provider(
        key = "ebay",
        displayName = "eBay",
        urlTemplate = "https://www.ebay.com/sch/i.html?_nkw={QUERY}",
        homeUrl = "https://www.ebay.com/",
        defaultZoom = 50
    )
)
