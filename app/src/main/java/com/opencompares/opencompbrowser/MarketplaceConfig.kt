package com.opencompares.opencompbrowser

object MarketplaceConfig {
    val defaultMarketplaces = listOf(
        Marketplace(
            id = "instamart",
            name = "Instamart",
            urlTemplate = "https://www.swiggy.com/instamart/search?custom_back=true&query={QUERY}",
            homepage = "https://www.swiggy.com/instamart",
            defaultZoom = 65
        ),
        Marketplace(
            id = "blinkit",
            name = "Blinkit",
            urlTemplate = "https://blinkit.com/s/?q={query}",
            homepage = "https://blinkit.com",
            defaultZoom = 70
        ),
        Marketplace(
            id = "zepto",
            name = "Zepto",
            urlTemplate = "https://www.zeptonow.com/search?query={query}",
            homepage = "https://www.zeptonow.com",
            defaultZoom = 65
        ),
        Marketplace(
            id = "amazon",
            name = "Amazon",
            urlTemplate = "https://www.amazon.in/s?k={query}",
            homepage = "https://www.amazon.in",
            defaultZoom = 90
        ),
        Marketplace(
            id = "amazon_now",
            name = "Amazon Now",
            urlTemplate = "https://www.amazon.in/tez/browse/search?searchKeyword={query}",
            homepage = "https://www.amazon.in/tez/browse",
            defaultZoom = 70
        ),
        Marketplace(
            id = "flipkart",
            name = "Flipkart",
            urlTemplate = "https://www.flipkart.com/search?q={query}",
            homepage = "https://www.flipkart.com",
            defaultZoom = 65
        ),
        Marketplace(
            id = "flipkart_minutes",
            name = "Flipkart Minutes",
            urlTemplate = "https://www.flipkart.com/search?q={query}&otracker=search&otracker1=search&marketplace=HYPERLOCAL",
            homepage = "https://www.flipkart.com/flipkart-minutes-store?marketplace=HYPERLOCAL",
            defaultZoom = 70
        ),
        Marketplace(
            id = "jiomart",
            name = "JioMart",
            urlTemplate = "https://www.jiomart.com/search?q={query}",
            homepage = "https://www.jiomart.com",
            defaultZoom = 70
        )
    )
}

