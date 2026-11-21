package com.opencompares.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    private fun provider(key: String) = MVP_PROVIDERS.single { it.key == key }

    @Test
    fun providerKeysAreUnique() {
        val keys = MVP_PROVIDERS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyTemplateHasAQueryPlaceholder() {
        MVP_PROVIDERS.forEach { p ->
            assertTrue(
                "${p.key} template has no query placeholder",
                p.urlTemplate.contains("{QUERY}") || p.urlTemplate.contains("{query}")
            )
        }
    }

    @Test
    fun everyProviderHasAHomepage() {
        MVP_PROVIDERS.forEach { p -> assertTrue("${p.key} has no homepage", p.homeUrl != null) }
    }

    @Test
    fun zoomDefaultsAreInTheSupportedRange() {
        // The zoom controls clamp to 5..100.
        MVP_PROVIDERS.forEach { p -> assertTrue("${p.key} zoom ${p.defaultZoom}", p.defaultZoom in 5..100) }
    }

    @Test
    fun amazonNowBuildsAnEncodedSearchUrl() {
        assertEquals(
            "https://www.amazon.in/tez/browse/search?searchKeyword=amul+butter",
            provider("amazon_now").buildUrl("amul butter")
        )
    }

    @Test
    fun flipkartMinutesKeepsTheHyperlocalMarketplace() {
        val url = provider("flipkart_minutes").buildUrl("milk & bread")
        assertEquals(
            "https://www.flipkart.com/search?q=milk+%26+bread&otracker=search&otracker1=search&marketplace=HYPERLOCAL",
            url
        )
    }

    @Test
    fun jioMartBuildsASearchUrl() {
        assertEquals("https://www.jiomart.com/search?q=atta", provider("jiomart").buildUrl("atta"))
    }

    @Test
    fun quickCommerceProvidersComeBeforeTheGeneralMarketplaces() {
        val keys = MVP_PROVIDERS.map { it.key }
        val quickCommerce = listOf("instamart", "blinkit", "zepto", "amazon_now", "flipkart_minutes", "jiomart")
        assertEquals(quickCommerce, keys.take(quickCommerce.size))
    }
}
