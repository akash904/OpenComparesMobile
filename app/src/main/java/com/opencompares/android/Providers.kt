package com.opencompares.android

import java.net.URLEncoder

data class Provider(
    val key: String,
    val displayName: String,
    val urlTemplate: String,
    val overrideUserAgent: String? = null,
    val homeUrl: String? = null,
    val defaultZoom: Int = 50,
    /** The site's cart page, where its bill is shown; null when not known. */
    val cartUrl: String? = null
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

/**
 * Stores user-added providers as strings: key||name||urlTemplate||homeUrl||zoom.
 * This is the format earlier versions already saved, so existing data keeps loading.
 */
object CustomProviderCodec {
    private const val SEP = "||"

    fun encode(p: Provider): String =
        listOf(p.key, p.displayName, p.urlTemplate, p.homeUrl ?: "", p.defaultZoom.toString())
            .joinToString(SEP) { it.replace(SEP, "|") }

    /** Returns null for entries that are missing a key, a name or a URL template. */
    fun decode(raw: String): Provider? {
        val parts = raw.split(SEP)
        val key = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val url = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
        // Older versions saved a missing homepage as "", which then loaded a blank page.
        val home = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
        val zoom = parts.getOrNull(4)?.toIntOrNull()?.coerceIn(5, 100) ?: 50
        return Provider(key = key, displayName = name, urlTemplate = url, homeUrl = home, defaultZoom = zoom)
    }

    /**
     * Decodes a saved set in a stable order and makes every key unique.
     * Older versions gave every custom provider the same literal key, so
     * clashes with a built-in or an earlier custom provider get a numbered suffix.
     */
    fun decodeAll(raw: Set<String>, reservedKeys: Set<String> = MVP_PROVIDERS.map { it.key }.toSet()): List<Provider> {
        val used = reservedKeys.toMutableSet()
        return raw.mapNotNull(::decode)
            .sortedWith(compareBy({ it.key }, { it.displayName }))
            .map { p ->
                var key = p.key
                var n = 2
                while (key in used) key = "${p.key}_${n++}"
                used += key
                p.copy(key = key)
            }
    }
}

/** A key for a new user-added provider that can't clash with existing keys. */
fun newCustomProviderKey(
    name: String,
    existingKeys: Set<String>,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val safe = name.lowercase().replace("[^a-z0-9]+".toRegex(), "_").trim('_').ifEmpty { "site" }
    var key = "custom_${safe}_$nowMillis"
    var n = 2
    while (key in existingKeys) key = "custom_${safe}_${nowMillis}_${n++}"
    return key
}

/** Checks a user-entered template. Returns an error message, or null when it can be used. */
fun validateCustomProvider(name: String, urlTemplate: String): String? = when {
    name.isBlank() || urlTemplate.isBlank() -> "Enter a name and a URL template"
    !urlTemplate.contains("{query}") && !urlTemplate.contains("{QUERY}") -> "The URL must contain {query}"
    !urlTemplate.startsWith("https://") && !urlTemplate.startsWith("http://") -> "The URL must start with https://"
    else -> null
}

val MVP_PROVIDERS = listOf(
    Provider(
        key = "instamart",
        displayName = "Instamart",
        urlTemplate = "https://www.swiggy.com/instamart/search?custom_back=true&query={QUERY}",
        homeUrl = "https://www.swiggy.com/instamart",
        cartUrl = "https://www.swiggy.com/instamart/cart",
        overrideUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        defaultZoom = 100
    ),
    Provider(
        key = "blinkit",
        displayName = "Blinkit",
        urlTemplate = "https://blinkit.com/s/?q={QUERY}",
        homeUrl = "https://blinkit.com/",
        defaultZoom = 70,
        cartUrl = "https://blinkit.com/cart"
    ),
    Provider(
        key = "zepto",
        displayName = "Zepto",
        urlTemplate = "https://www.zeptonow.com/search?query={QUERY}",
        homeUrl = "https://www.zeptonow.com/",
        defaultZoom = 65,
        cartUrl = "https://www.zepto.com/cart"
    ),
    Provider(
        key = "amazon_now",
        displayName = "Amazon Now",
        urlTemplate = "https://www.amazon.in/tez/browse/search?searchKeyword={QUERY}",
        homeUrl = "https://www.amazon.in/tez/browse",
        defaultZoom = 70
    ),
    Provider(
        key = "flipkart_minutes",
        displayName = "Flipkart Minutes",
        urlTemplate = "https://www.flipkart.com/search?q={QUERY}&otracker=search&otracker1=search&marketplace=HYPERLOCAL",
        homeUrl = "https://www.flipkart.com/flipkart-minutes-store?marketplace=HYPERLOCAL",
        defaultZoom = 70
    ),
    Provider(
        key = "jiomart",
        displayName = "JioMart",
        urlTemplate = "https://www.jiomart.com/search?q={QUERY}",
        homeUrl = "https://www.jiomart.com/",
        defaultZoom = 70
    ),

    // Marketplace additions
    Provider(
        key = "amazon",
        displayName = "Amazon.in",
        urlTemplate = "https://www.amazon.in/s?k={QUERY}",
        homeUrl = "https://www.amazon.in/",
        defaultZoom = 90
    ),
    Provider(
        key = "flipkart",
        displayName = "Flipkart",
        urlTemplate = "https://www.flipkart.com/search?q={QUERY}",
        homeUrl = "https://www.flipkart.com/",
        defaultZoom = 65
    ),
    Provider(
        key = "meesho",
        displayName = "Meesho",
        urlTemplate = "https://www.meesho.com/search?q={QUERY}",
        homeUrl = "https://www.meesho.com/",
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
