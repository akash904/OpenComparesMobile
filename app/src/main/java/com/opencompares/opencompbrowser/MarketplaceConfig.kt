package com.opencompares.opencompbrowser

object MarketplaceConfig {
    val defaultMarketplaces = listOf(
        Marketplace(
            id = "instamart",
            name = "Instamart",
            urlTemplate = "https://www.swiggy.com/instamart/search?q={query}"
        ),
        Marketplace(
            id = "blinkit",
            name = "Blinkit",
            urlTemplate = "https://blinkit.com/s/?q={query}"
        ),
        Marketplace(
            id = "zepto",
            name = "Zepto",
            urlTemplate = "https://www.zeptonow.com/search?query={query}"
        ),
        Marketplace(
            id = "amazon",
            name = "Amazon",
            urlTemplate = "https://www.amazon.in/s?k={query}"
        )
    )
}

