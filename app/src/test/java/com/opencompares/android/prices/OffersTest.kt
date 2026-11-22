package com.opencompares.android.prices

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OffersTest {

    private fun q(text: String?) = Quantity.parse(text)

    @Test
    fun quantitiesNormaliseToBaseUnits() {
        assertEquals(Quantity(500.0, Quantity.BaseUnit.MILLILITRE), q("500 ml"))
        assertEquals(Quantity(1000.0, Quantity.BaseUnit.MILLILITRE), q("1 ltr"))
        assertEquals(Quantity(1000.0, Quantity.BaseUnit.GRAM), q("1 kg"))
        assertEquals(Quantity(200.0, Quantity.BaseUnit.GRAM), q("200 g"))
        assertEquals(Quantity(6.0, Quantity.BaseUnit.PIECE), q("6 pcs"))
        assertEquals(Quantity(12.0, Quantity.BaseUnit.PIECE), q("1 dozen"))
    }

    @Test
    fun multipacksMultiply() {
        assertEquals(Quantity(400.0, Quantity.BaseUnit.MILLILITRE), q("2 x 200 ml"))
        assertEquals(Quantity(400.0, Quantity.BaseUnit.MILLILITRE), q("200 ml x 2"))
    }

    @Test
    fun aSizeInBracketsBeatsAPackCount() {
        // Zepto shows sizes like this.
        assertEquals(Quantity(500.0, Quantity.BaseUnit.MILLILITRE), q("1 pack (500 ml)"))
        assertEquals(Quantity(1000.0, Quantity.BaseUnit.MILLILITRE), q("1 pack (1 L)"))
        // Zepto eggs: a piece count says more than "pack".
        assertEquals(Quantity(6.0, Quantity.BaseUnit.PIECE), q("1 pack (6 pcs)"))
        assertEquals(Quantity(1.0, Quantity.BaseUnit.PIECE), q("1 pack"))
    }

    @Test
    fun noQuantityGivesNull() {
        assertNull(q(null))
        assertNull(q("Amul Taaza Toned Milk"))
    }

    @Test
    fun quantityPrintsReadably() {
        assertEquals("1 l", q("1 ltr").toString())
        assertEquals("1.5 kg", q("1.5 kg").toString())
        assertEquals("450 ml", q("450 ml").toString())
    }

    @Test
    fun rupeesParseFromPageText() {
        assertEquals(1299.0, parseRupees("₹1,299")!!, 0.0)
        assertEquals(45.5, parseRupees("Rs. 45.50")!!, 0.0)
        assertEquals(99.0, parseRupees("INR 99")!!, 0.0)
        assertEquals(31.0, parseRupees("₹ 31")!!, 0.0)
        assertNull(parseRupees(null))
    }

    @Test
    fun rupeesFormatWithoutFloatingPointNoise() {
        assertEquals("₹55", formatRupees(55.00000000000001))
        assertEquals("₹7.10", formatRupees(7.1))
        assertEquals("₹141", formatRupees(141.0))
    }

    private fun offer(price: Double, mrp: Double?, qty: String?) = Offer(
        name = "x", price = price, mrp = mrp, discountText = null, quantity = Quantity.parse(qty),
        quantityText = qty, etaText = null, inStock = true, canAdd = true, link = null
    )

    @Test
    fun unitPricesMakeDifferentPackSizesComparable() {
        // Amul Taaza ₹31 for 500 ml vs Amul Gold ₹76 for 1 l.
        assertEquals("₹6.20/100 ml", offer(31.0, null, "500 ml").unitPrice.toString())
        assertEquals("₹7.60/100 ml", offer(76.0, null, "1 ltr").unitPrice.toString())
        assertEquals("₹40/pc", offer(240.0, null, "6 pcs").unitPrice.toString())
        assertNull(offer(50.0, null, null).unitPrice)
    }

    @Test
    fun discountComesFromListPrice() {
        assertEquals(50, offer(120.0, 240.0, "1 ltr").discountPercent)
        assertNull(offer(120.0, null, "1 ltr").discountPercent)
    }

    @Test
    fun parserBuildsOffersAndSkipsBadOnes() {
        val json = JSONObject()
            .put("url", "https://blinkit.com/s/?q=milk")
            .put("title", "milk")
            .put("scannedPrices", 33)
            .put("needsLogin", false)
            .put(
                "products", JSONArray()
                    .put(JSONObject().put("name", "Country Delight 25 g High Protein Milk").put("priceText", "₹57")
                        .put("mrpText", "₹60").put("quantityText", "450 ml").put("etaText", "13 MINS")
                        .put("inStock", true).put("canAdd", true).put("link", JSONObject.NULL))
                    .put(JSONObject().put("name", "Broken").put("priceText", "free"))
                    .put(JSONObject().put("name", "").put("priceText", "₹10"))
                    // The same product twice collapses into one.
                    .put(JSONObject().put("name", "Country Delight 25 g High Protein Milk").put("priceText", "₹57"))
                    // A "list price" lower than the price is ignored.
                    .put(JSONObject().put("name", "Odd").put("priceText", "₹50").put("mrpText", "₹40"))
            )
        val page = OfferParser.parse("blinkit", json, nowMillis = 1)
        assertEquals(2, page.offers.size)
        val first = page.offers[0]
        assertEquals(57.0, first.price, 0.0)
        assertEquals(60.0, first.mrp!!, 0.0)
        assertEquals(Quantity(450.0, Quantity.BaseUnit.MILLILITRE), first.quantity)
        assertNull(first.link)
        assertNull(page.offers[1].mrp)
        assertFalse(page.needsLogin)
    }

    @Test
    fun loginWallIsReported() {
        val page = OfferParser.parse(
            "instamart",
            JSONObject().put("products", JSONArray()).put("scannedPrices", 0).put("needsLogin", true)
        )
        assertTrue(page.needsLogin)
        assertTrue(page.offers.isEmpty())
    }

    @Test
    fun quantityFallsBackToTheName() {
        val json = JSONObject().put(
            "products", JSONArray().put(JSONObject().put("name", "Whole Farm Grocery Almonds (200 g)").put("priceText", "₹282"))
        )
        assertEquals(Quantity(200.0, Quantity.BaseUnit.GRAM), OfferParser.parse("blinkit", json).offers[0].quantity)
    }
}
