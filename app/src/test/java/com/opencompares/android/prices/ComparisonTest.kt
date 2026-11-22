package com.opencompares.android.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonTest {

    private fun offer(name: String, price: Double, qty: String?, inStock: Boolean = true, mrp: Double? = null) = Offer(
        name = name, price = price, mrp = mrp, discountText = null, quantity = Quantity.parse(qty),
        quantityText = qty, etaText = "10 mins", inStock = inStock, canAdd = true, link = "https://example.com/$name"
    )

    private fun page(key: String, vararg offers: Offer) = PageOffers(key, "https://$key", key, offers.toList(), offers.size, false, 0)

    // Real listings from a "milk" search on the Note10 Lite.
    private val blinkit = page(
        "blinkit",
        offer("Amul Taaza Toned Fresh Milk", 29.0, "500 ml", mrp = 30.0),
        offer("Amul Gold Full Cream Milk", 35.0, "500 ml")
    )
    private val zepto = page(
        "zepto",
        offer("Amul Taaza Homogenised Toned Milk", 56.0, "1 pack (1 L)"),
        offer("Nandini Toned Milk", 27.0, "500 ml")
    )
    private val names = mapOf("blinkit" to "Blinkit", "zepto" to "Zepto")
    private fun prompt() = ComparisonPrompts.build("milk", listOf(blinkit, zepto)) { names.getValue(it) }

    @Test
    fun promptListsEveryOfferWithAShortId() {
        val p = prompt()
        assertEquals(listOf("a1", "a2", "b1", "b2"), p.listings.keys.toList())
        assertEquals(2, p.siteCount)
        assertTrue(p.input.contains("Site a: Blinkit"))
        assertTrue(p.input.contains("a1 | Amul Taaza Toned Fresh Milk | 500 ml | ₹29 (list ₹30) | ₹5.80/100 ml | 10 mins | in stock"))
        assertTrue(p.input.contains("b1 | Amul Taaza Homogenised Toned Milk | 1 l | ₹56 | ₹5.60/100 ml"))
    }

    @Test
    fun promptNeverSendsLinks() {
        assertFalse(prompt().input.contains("http"))
    }

    @Test
    fun siteCodesStayAlphabetic() {
        assertEquals("a", ComparisonPrompts.siteCode(0))
        assertEquals("z", ComparisonPrompts.siteCode(25))
        assertEquals("aa", ComparisonPrompts.siteCode(26))
        assertEquals("ab", ComparisonPrompts.siteCode(27))
    }

    @Test
    fun bestValueIsPerUnitNotPerPack() {
        val reply = """{"matches":[{"title":"Amul Taaza Toned Milk","ids":["a1","b1"]}],"summary":"Zepto's 1 L pack is cheaper per litre."}"""
        val c = ComparisonParser.parse(reply, prompt())
        val match = c.matches.single()
        // ₹56 for 1 l beats ₹29 for 500 ml.
        assertEquals("b1", match.best!!.id)
        assertEquals("100 ml", match.comparedPer)
        assertEquals(3, match.leadPercent) // ₹5.60 vs ₹5.80 per 100 ml
        assertFalse(match.tie)
        assertEquals(listOf("b1", "a1"), match.offers.map { it.id })
        assertEquals("Zepto's 1 L pack is cheaper per litre.", c.summary)
        assertEquals(4, c.offersCompared)
    }

    @Test
    fun madeUpIdsAndSingleSiteGroupsAreDropped() {
        val reply = """
            ```json
            {"matches":[
              {"title":"Ghost","ids":["a1","q9"]},
              {"title":"Only Blinkit","ids":["a1","a2"]},
              {"title":"Amul Taaza","ids":["A1"," b1 "]}
            ]}
            ```
        """.trimIndent()
        val c = ComparisonParser.parse(reply, prompt())
        assertEquals(listOf("Amul Taaza"), c.matches.map { it.title })
        assertNull(c.summary)
    }

    @Test
    fun aListingIsUsedInOneMatchOnly() {
        val reply = """{"matches":[{"title":"One","ids":["a1","b1"]},{"title":"Two","ids":["a1","b2"]}]}"""
        assertEquals(listOf("One"), ComparisonParser.parse(reply, prompt()).matches.map { it.title })
    }

    @Test
    fun outOfStockOffersAreNeverBest() {
        val match = ProductMatch(
            "Milk",
            listOf(
                ListedOffer("a1", "blinkit", offer("Milk", 20.0, "500 ml", inStock = false)),
                ListedOffer("b1", "zepto", offer("Milk", 30.0, "500 ml"))
            )
        )
        assertEquals("b1", match.best!!.id)
        assertNull(match.leadPercent)
        assertEquals(listOf("b1", "a1"), match.offers.map { it.id })
    }

    @Test
    fun withoutSizesThePlainPriceDecides() {
        val match = ProductMatch(
            "Bread",
            listOf(
                ListedOffer("a1", "blinkit", offer("Bread", 50.0, null)),
                ListedOffer("b1", "zepto", offer("Bread", 40.0, null))
            )
        )
        assertNull(match.comparedPer)
        assertEquals("b1", match.best!!.id)
        assertEquals(20, match.leadPercent)
    }

    @Test
    fun equalUnitPricesAreATie() {
        // Mother Dairy Toned: ₹62 for 1 l on Instamart, ₹31 for 500 ml on Blinkit.
        val match = ProductMatch(
            "Mother Dairy Toned Milk",
            listOf(
                ListedOffer("a1", "instamart", offer("Toned", 62.0, "1 ltr")),
                ListedOffer("b1", "blinkit", offer("Toned", 31.0, "500 ml"))
            )
        )
        assertTrue(match.tie)
        assertNull(match.leadPercent)
    }

    @Test(expected = ComparisonFormatException::class)
    fun proseInsteadOfJsonIsAnError() {
        ComparisonParser.parse("Sorry, I can't help with that.", prompt())
    }
}
