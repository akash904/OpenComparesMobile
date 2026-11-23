package com.opencompares.android.shopping

import com.opencompares.android.prices.Offer
import com.opencompares.android.prices.PageOffers
import com.opencompares.android.prices.Quantity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CartTest {

    // Bills read from the Note10 Lite on 2026-11-22.
    private val blinkitJson = """{"found":true,"rows":[{"label":"Items total","amount":37},{"label":"Delivery charge","amount":35},
        {"label":"Handling charge","amount":10},{"label":"Small cart charge","amount":25}],"toPay":107,"freeDeliveryAbove":null}"""
    private val zeptoJson = """{"found":true,"rows":[{"label":"Item Total","amount":100},{"label":"Delivery Fee","amount":30},
        {"label":"Handling Fee","amount":13}],"toPay":143,"freeDeliveryAbove":199}"""

    @Test
    fun billsParseWithTheirCharges() {
        val b = BillParser.parse(JSONObject(blinkitJson))!!
        assertEquals(37.0, b.itemTotal!!, 0.0)
        assertEquals(107.0, b.toPay, 0.0)
        assertEquals(70.0, b.extra!!, 0.0)
        assertEquals(
            listOf(ChargeKind.Delivery, ChargeKind.Handling, ChargeKind.SmallCart),
            b.charges.map { it.kind }
        )
        val z = BillParser.parse(JSONObject(zeptoJson))!!
        assertEquals(199.0, z.freeDeliveryAbove!!, 0.0)
        assertEquals(43.0, z.extra!!, 0.0)
    }

    @Test
    fun amazonAndFlipkartBillsParse() {
        // Read from Amazon Now and Flipkart Minutes on 2026-11-23.
        val amazon = BillParser.parse(JSONObject("""{"found":true,"rows":[{"label":"Items total","amount":26},
            {"label":"Handling fee","amount":0},{"label":"Delivery fee","amount":30}],"toPay":56,"freeDeliveryAbove":99}"""))!!
        assertEquals(26.0, amazon.itemTotal!!, 0.0)
        assertEquals(30.0, amazon.extra!!, 0.0)
        assertEquals(99.0, amazon.freeDeliveryAbove!!, 0.0)

        val flipkart = BillParser.parse(JSONObject("""{"found":true,"rows":[{"label":"Price (1 item)","amount":102},
            {"label":"Discount","amount":-44},{"label":"Handling fee","amount":30}],"toPay":88,"freeDeliveryAbove":null}"""))!!
        assertEquals(ChargeKind.Items, flipkart.lines[0].kind)
        assertEquals(ChargeKind.Discount, flipkart.lines[1].kind)
        assertEquals(88.0, flipkart.toPay, 0.0)
        // ₹102 list price less ₹44 off is ₹58 of items; the ₹30 handling fee is the charge, not ₹-14.
        assertEquals(-44.0, flipkart.discounts, 0.0)
        assertEquals(30.0, flipkart.extra!!, 0.0)
    }

    @Test
    fun noBillOnThePageIsNull() {
        assertNull(BillParser.parse(JSONObject("""{"found":false,"rows":[],"toPay":null}""")))
    }

    @Test
    fun chargeKindsFromLabels() {
        assertEquals(ChargeKind.Items, BillLine("Sub Total", 200.0).kind)
        assertEquals(ChargeKind.Platform, BillLine("Platform fee", 2.0).kind)
        assertEquals(ChargeKind.Surge, BillLine("Rain fee", 20.0).kind)
        assertEquals(ChargeKind.Discount, BillLine("Coupon discount", -30.0).kind)
        assertEquals(ChargeKind.Discount, BillLine("Something", -5.0).kind)
        assertEquals(ChargeKind.SmallCart, BillLine("Small order fee", 15.0).kind)
    }

    @Test
    fun cardStateParses() {
        val s = CardState.parse(JSONObject("""{"found":true,"count":2,"canAdd":false,"canPlus":true,"outOfStock":false}"""))
        assertTrue(s.found)
        assertEquals(2, s.count)
        assertTrue(s.canPlus)
    }

    @Test
    fun cartLinesParse() {
        val lines = CartLines.parse(JSONObject("""{"lines":2,"units":3,"pressed":true}"""))
        assertEquals(CartLines(2, 3, true), lines)
        assertEquals(CartLines(0, 0, false), CartLines.parse(JSONObject("{}")))
    }

    @Test
    fun emptiedCartsDescribeThemselves() {
        assertEquals("Zepto: emptied, took out 3 items.", EmptiedCart("zepto", removed = 3, left = 0).describe("Zepto"))
        assertEquals("Blinkit: already empty.", EmptiedCart("blinkit", 0, 0, wasEmpty = true).describe("Blinkit"))
        assertEquals(
            "Amazon Now: took out 1 item, 2 items still there. Open its cart to remove the rest.",
            EmptiedCart("amazon", removed = 1, left = 2).describe("Amazon Now")
        )
        assertEquals("Instamart: no cart.", EmptiedCart("instamart", 0, 0, problem = "no cart.").describe("Instamart"))
    }

    private fun offer(name: String, price: Double, qty: String) = Offer(
        name = name, price = price, mrp = null, discountText = null, quantity = Quantity.parse(qty),
        quantityText = qty, etaText = null, inStock = true, canAdd = true, link = null
    )

    private fun page(key: String, vararg offers: Offer) = PageOffers(key, "", "", offers.toList(), offers.size, false, 0)

    private fun added(item: WantedItem, offer: Offer, wanted: Int = 1, inCart: Int = wanted) =
        AddedItem(item, offer, wanted, before = 0, inCart = inCart, problem = null)

    @Test
    fun theCheapestBillWinsAndAPricierSplitIsCalledOut() {
        val milk = WantedItem("Milk", "milk", "1 l", 1)
        val eggs = WantedItem("Eggs", "eggs", "6 pcs", 1)
        val bMilk = offer("Milk", 60.0, "1 l")
        val zMilk = offer("Milk", 56.0, "1 l")
        val bEggs = offer("Eggs", 50.0, "6 pcs")
        val zEggs = offer("Eggs", 55.0, "6 pcs")
        val searches = listOf(
            ItemSearch(milk, listOf(page("blinkit", bMilk), page("zepto", zMilk))),
            ItemSearch(eggs, listOf(page("blinkit", bEggs), page("zepto", zEggs)))
        )
        val p = MatchPrompts.build(searches) { it }
        val m = MatchParser.parse("""{"items":[{"item":1,"ids":["1a1","1b1"]},{"item":2,"ids":["2a1","2b1"]}]}""", p)
        val result = BasketResult.from(searches, m, listOf("blinkit", "zepto"))
        // By item price alone, the split (Zepto milk 56 + Blinkit eggs 50 = 106) looks best.
        assertEquals(106.0, result.cheapestSplitTotal, 0.0)

        val blinkit = SiteCart(
            "blinkit", listOf(added(milk, bMilk), added(eggs, bEggs)),
            Bill(listOf(BillLine("Items total", 110.0), BillLine("Delivery charge", 30.0)), 140.0, null), 0, null
        )
        val zepto = SiteCart(
            "zepto", listOf(added(milk, zMilk), added(eggs, zEggs)),
            Bill(listOf(BillLine("Item Total", 111.0), BillLine("Delivery Fee", 0.0), BillLine("Handling Fee", 13.0)), 124.0, 199.0), 0, null
        )
        val c = CartComparison(listOf(blinkit, zepto), result)
        assertEquals("zepto", c.best!!.providerKey)
        // Split: 56 + 13 (Zepto's charges) + 50 + 30 (Blinkit's) = 149, at least.
        assertEquals(149.0, c.splitAtLeast!!, 0.0)
        assertTrue(c.splitNotWorthIt)
    }

    @Test
    fun aCartMissingItemsCannotBeTheBest() {
        val milk = WantedItem("Milk", "milk", "1 l", 1)
        val o = offer("Milk", 60.0, "1 l")
        val searches = listOf(ItemSearch(milk, listOf(page("blinkit", o))))
        val p = MatchPrompts.build(searches) { it }
        val result = BasketResult.from(searches, MatchParser.parse("""{"items":[{"item":1,"ids":["1a1"]}]}""", p), listOf("blinkit"))
        val short = SiteCart("blinkit", listOf(added(milk, o, wanted = 2, inCart = 1)), Bill(emptyList(), 90.0, null), 0, null)
        val c = CartComparison(listOf(short), result)
        assertNull(c.best)
        assertFalse(short.complete)
        assertEquals(1, short.items[0].addedByApp)
    }
}
