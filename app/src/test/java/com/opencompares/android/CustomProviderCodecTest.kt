package com.opencompares.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderCodecTest {

    private val sample = Provider(
        key = "custom_dmart_1700000000000",
        displayName = "DMart",
        urlTemplate = "https://www.dmart.in/search?searchTerm={query}",
        homeUrl = "https://www.dmart.in/",
        defaultZoom = 60
    )

    @Test
    fun encodeThenDecodeRoundTrips() {
        assertEquals(sample, CustomProviderCodec.decode(CustomProviderCodec.encode(sample)))
    }

    @Test
    fun legacyEmptyHomepageDecodesAsNoHomepage() {
        // Version 1.1 saved a missing homepage as "", which then loaded a blank page.
        val decoded = CustomProviderCodec.decode("custom_x||X Store||https://x.in/s?q={query}||||50")
        assertNotNull(decoded)
        assertNull(decoded!!.homeUrl)
        assertEquals("https://x.in/s?q=", decoded.home())
    }

    @Test
    fun incompleteEntriesAreDropped() {
        assertNull(CustomProviderCodec.decode(""))
        assertNull(CustomProviderCodec.decode("key_only"))
        assertNull(CustomProviderCodec.decode("key||name||"))
    }

    @Test
    fun badZoomFallsBackToTheDefault() {
        assertEquals(50, CustomProviderCodec.decode("k||N||https://n.in/{query}||||abc")!!.defaultZoom)
        assertEquals(100, CustomProviderCodec.decode("k||N||https://n.in/{query}||||900")!!.defaultZoom)
    }

    @Test
    fun clashingLegacyKeysAreMadeUnique() {
        // Version 1.1 gave every custom provider the same literal key.
        val legacyKey = "custom_\$safe_\${System.currentTimeMillis()}"
        val saved = setOf(
            "$legacyKey||Store A||https://a.in/{query}||||50",
            "$legacyKey||Store B||https://b.in/{query}||||50"
        )
        val decoded = CustomProviderCodec.decodeAll(saved)
        assertEquals(2, decoded.size)
        assertNotEquals(decoded[0].key, decoded[1].key)
    }

    @Test
    fun customKeysNeverShadowBuiltInProviders() {
        val decoded = CustomProviderCodec.decodeAll(setOf("blinkit||Fake||https://f.in/{query}||||50"))
        assertTrue(decoded.single().key != "blinkit")
    }

    @Test
    fun newKeysAreUniqueEvenAtTheSameMillisecond() {
        val first = newCustomProviderKey("My Shop", emptySet(), nowMillis = 42)
        val second = newCustomProviderKey("My Shop", setOf(first), nowMillis = 42)
        assertEquals("custom_my_shop_42", first)
        assertNotEquals(first, second)
    }

    @Test
    fun validationRequiresAPlaceholderAndAWebUrl() {
        assertNull(validateCustomProvider("Shop", "https://shop.in/s?q={query}"))
        assertNotNull(validateCustomProvider("", "https://shop.in/s?q={query}"))
        assertNotNull(validateCustomProvider("Shop", "https://shop.in/s?q="))
        assertNotNull(validateCustomProvider("Shop", "shop.in/s?q={query}"))
    }
}
