package com.opencompares.android.shopping

import com.opencompares.android.prices.Offer
import com.opencompares.android.prices.PageOffers
import com.opencompares.android.prices.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingTest {

    private fun offer(name: String, price: Double, qty: String?, inStock: Boolean = true) = Offer(
        name = name, price = price, mrp = null, discountText = null, quantity = Quantity.parse(qty),
        quantityText = qty, etaText = null, inStock = inStock, canAdd = true, link = null
    )

    private fun page(key: String, vararg offers: Offer) = PageOffers(key, "https://$key", key, offers.toList(), offers.size, false, 0)

    @Test
    fun listReplyParses() {
        val update = ShoppingListPrompts.parse(
            """```json
            {"reply":"Got it.","items":[
              {"name":"Amul Taaza toned milk","search":"amul taaza milk","want":"1 l","count":2},
              {"name":"Eggs","search":"eggs","want":"6 pcs","count":0},
              {"name":"Maggi","search":"maggi","want":null},
              {"name":"","search":"nothing"},
              {"name":"Eggs again","search":"EGGS","want":"6 pcs","count":1}
            ]}
            ```"""
        )
        assertEquals("Got it.", update.reply)
        assertEquals(listOf("Amul Taaza toned milk", "Eggs", "Maggi"), update.items.map { it.name })
        assertEquals(1, update.items[1].count) // counts below 1 become 1
        assertNull(update.items[2].want)
        assertEquals("1 l × 2", update.items[0].amountText)
        assertEquals(Quantity(2000.0, Quantity.BaseUnit.MILLILITRE), update.items[0].wantedTotal)
    }

    @Test
    fun idsTellReplacingFromAdding() {
        val before = listOf(
            WantedItem("Potatoes", "potato", "1 kg", 3, id = 1),
            WantedItem("MTR idli batter", "mtr idli batter", null, 1, id = 2),
            WantedItem("Papaya", "papaya", null, 1, id = 3)
        )
        // "use the MTR mix instead, make potatoes 2 kg, drop the papaya, and add bread"
        val reply = ShoppingListPrompts.parse(
            """{"reply":"Done.","items":[
              {"id":1,"name":"Potatoes","search":"potato","want":"1 kg","count":2},
              {"id":2,"name":"MTR Rava Idli Mix","search":"mtr rava idli mix","want":null,"count":1},
              {"id":null,"name":"Bread","search":"bread","want":null,"count":1}
            ]}"""
        )
        val (after, next) = ListDiff.assignIds(before, reply.items, nextId = 4)
        assertEquals(listOf(1, 2, 4), after.map { it.id })
        assertEquals(5, next)

        val diff = ListDiff.between(before, after)
        assertEquals(ItemChange.Changed, diff.changes[1])
        assertEquals(ItemChange.Replaced, diff.changes[2])
        assertEquals("MTR idli batter", diff.replaced[2]!!.name)
        assertEquals(ItemChange.Added, diff.changes[4])
        assertEquals(listOf("Papaya"), diff.removed.map { it.name })
        assertFalse(diff.isEmpty)
    }

    @Test
    fun madeUpOrRepeatedIdsBecomeNewItems() {
        val before = listOf(WantedItem("Milk", "milk", "1 l", 1, id = 1))
        val parsed = listOf(
            WantedItem("Milk", "milk", "1 l", 1, id = 1),
            WantedItem("Curd", "curd", null, 1, id = 1), // reused id: must not overwrite milk
            WantedItem("Eggs", "eggs", null, 1, id = 99) // never existed
        )
        val (after, _) = ListDiff.assignIds(before, parsed, nextId = 2)
        assertEquals(listOf(1, 2, 3), after.map { it.id })
        assertTrue(ListDiff.between(before, listOf(before[0])).isEmpty)
    }

    @Test
    fun theCurrentListIsSentBack() {
        val input = ShoppingListPrompts.input("add bread", listOf(WantedItem("Milk", "milk", "1 l", 2)))
        assertTrue(input.contains(""""name":"Milk""""))
        assertTrue(input.endsWith("Shopper says: add bread"))
    }

    @Test
    fun packsCoverTheAmountWanted() {
        val milk = WantedItem("Milk", "milk", "1 l", 2)
        assertEquals(4, packsNeeded(milk, offer("Milk", 27.0, "500 ml")))
        assertEquals(2, packsNeeded(milk, offer("Milk", 54.0, "1 l")))
        assertEquals(1, packsNeeded(milk, offer("Milk", 110.0, "2 l")))
        assertEquals(1, packsNeeded(milk, offer("Milk", 150.0, "5 l"))) // never less than one
        // 450 ml packs: 5 packs make 2.25 l.
        assertEquals(5, packsNeeded(milk, offer("Milk", 25.0, "450 ml")))
        // Zepto sells potatoes as "900 g - 1 kg": one pack is enough for 1 kg.
        assertEquals(1, packsNeeded(WantedItem("Potatoes", "potato", "1 kg", 1), offer("Potato", 26.0, "900 g")))
        // Grams can't cover litres, and no size means "as many as asked".
        assertEquals(2, packsNeeded(milk, offer("Milk powder", 200.0, "500 g")))
        assertEquals(3, packsNeeded(WantedItem("Maggi", "maggi", null, 3), offer("Maggi", 14.0, "70 g")))
    }

    @Test
    fun biggerPacksThanWantedAreFlagged() {
        val atta = WantedItem("Aashirvaad atta", "aashirvaad atta", "1 kg", 1)
        val search = listOf(ItemSearch(atta, listOf(page("zepto", offer("Atta", 69.0, "1 kg")), page("blinkit", offer("Atta", 120.0, "2 kg")))))
        val p = MatchPrompts.build(search) { it }
        val result = BasketResult.from(search, MatchParser.parse("""{"items":[{"item":1,"ids":["1a1","1b1"]}]}""", p), listOf("zepto", "blinkit"))
        assertFalse(result.items[0].options.first { it.providerKey == "zepto" }.moreThanWanted)
        assertTrue(result.items[0].options.first { it.providerKey == "blinkit" }.moreThanWanted)
    }

    private val milk = WantedItem("Amul Taaza milk", "amul taaza milk", "1 l", 1)
    private val eggs = WantedItem("Eggs", "eggs", "6 pcs", 1)
    private val searches = listOf(
        ItemSearch(
            milk, listOf(
                page("blinkit", offer("Amul Taaza Toned Milk", 31.0, "500 ml"), offer("Amul Gold", 38.0, "500 ml")),
                page("zepto", offer("Amul Taaza Toned Fresh Milk", 56.0, "1 l"), offer("Sold out milk", 1.0, "1 l", inStock = false))
            )
        ),
        ItemSearch(
            eggs, listOf(
                page("blinkit", offer("Farm Eggs", 60.0, "6 pcs")),
                page("zepto", offer("Bread", 40.0, "400 g"))
            )
        )
    )
    private val names = mapOf("blinkit" to "Blinkit", "zepto" to "Zepto")
    private fun prompt() = MatchPrompts.build(searches) { names.getValue(it) }

    @Test
    fun matchPromptGivesEachItemItsOwnIds() {
        val p = prompt()
        assertEquals(listOf("1a1", "1a2", "1b1", "2a1", "2b1"), p.candidates.keys.toList())
        assertTrue(p.input.contains("Item 1: Amul Taaza milk, wanted 1 l"))
        // Out-of-stock listings are not offered.
        assertTrue(!p.input.contains("Sold out"))
    }

    @Test
    fun matchesFromAnotherItemOrMadeUpAreDropped() {
        val reply = """{"items":[
            {"item":1,"ids":["1a1","1a2","1b1","2a1","9z9"]},
            {"item":2,"ids":["2a1"],"note":"Zepto had no eggs"}
        ]}"""
        val m = MatchParser.parse(reply, prompt())
        assertEquals(setOf("blinkit", "zepto"), m.picks.getValue(0).keys)
        assertEquals("1a1", m.picks.getValue(0).getValue("blinkit").id) // first per site wins
        assertEquals(setOf("blinkit"), m.picks.getValue(1).keys)
        assertEquals("Zepto had no eggs", m.notes[1])
    }

    @Test
    fun basketTotalsUseThePacksNeeded() {
        val m = MatchParser.parse("""{"items":[{"item":1,"ids":["1a1","1b1"]},{"item":2,"ids":["2a1"]}]}""", prompt())
        val result = BasketResult.from(searches, m, listOf("blinkit", "zepto"))

        // Milk: Blinkit 2 × ₹31 = ₹62, Zepto 1 × ₹56.
        val milkPlan = result.items[0]
        assertEquals("zepto", milkPlan.cheapest!!.providerKey)
        assertEquals(62.0, milkPlan.options[1].cost, 0.0)
        assertEquals("2 × 500 ml", milkPlan.options[1].amountText)

        // Only Blinkit has everything: ₹62 + ₹60.
        assertEquals("blinkit", result.bestSingleSite!!.providerKey)
        assertEquals(122.0, result.bestSingleSite!!.subtotal, 0.0)
        val zepto = result.sites.first { it.providerKey == "zepto" }
        assertEquals(listOf("Eggs"), zepto.missing.map { it.name })

        // Cheapest by item: milk on Zepto, eggs on Blinkit.
        assertEquals(116.0, result.cheapestSplitTotal, 0.0)
        assertEquals(setOf("zepto", "blinkit"), result.cheapestSplit.keys)
        assertTrue(result.unavailable.isEmpty())
    }

    @Test
    fun equalPricesAreNotACheapestAndFavourTheSiteWithMoreItems() {
        // Milk is ₹56 on both; only Blinkit also has eggs, so milk goes there in the split.
        val tied = listOf(
            ItemSearch(milk, listOf(page("zepto", offer("Milk", 56.0, "1 l")), page("blinkit", offer("Milk", 56.0, "1 l")))),
            ItemSearch(eggs, listOf(page("blinkit", offer("Eggs", 60.0, "6 pcs"))))
        )
        val p = MatchPrompts.build(tied) { names.getValue(it) }
        val m = MatchParser.parse("""{"items":[{"item":1,"ids":["1a1","1b1"]},{"item":2,"ids":["2b1"]}]}""", p)
        val result = BasketResult.from(tied, m, listOf("zepto", "blinkit"))
        assertFalse(result.items[0].hasClearCheapest)
        assertEquals("blinkit", result.items[0].cheapest!!.providerKey)
        assertEquals(setOf("blinkit"), result.cheapestSplit.keys)
    }

    @Test
    fun similarListingsOnlyStandInWhereThereIsNoExactMatch() {
        val batter = WantedItem("MTR idli batter", "mtr idli batter", null, 1)
        val search = listOf(
            ItemSearch(
                batter, listOf(
                    page("blinkit", offer("MTR Rava Idli Mix", 95.0, "500 g")),
                    page("zepto", offer("iD Idli Dosa Batter", 80.0, "1 kg"), offer("MTR Rava Idli Mix", 90.0, "500 g"))
                )
            )
        )
        val p = MatchPrompts.build(search) { it }
        // Zepto has the exact item; its "similar" must be ignored. Blinkit only has a mix.
        val m = MatchParser.parse("""{"items":[{"item":1,"ids":["1b1"],"similar":["1a1","1b2"]}]}""", p)
        val result = BasketResult.from(search, m, listOf("blinkit", "zepto"))
        val plan = result.items[0]
        assertEquals(listOf("zepto"), plan.options.map { it.providerKey })
        assertEquals(listOf("blinkit"), plan.similar.map { it.providerKey })
        // Similar listings don't count: only Zepto has the item.
        assertEquals("zepto", result.bestSingleSite!!.providerKey)
    }

    @Test
    fun loginWallsAreKeptApartFromNotFound() {
        val walled = PageOffers("instamart", "", "", emptyList(), 0, needsLogin = true, readAtMillis = 0)
        val search = listOf(ItemSearch(eggs, listOf(page("blinkit", offer("Eggs", 60.0, "6 pcs")), walled)))
        val p = MatchPrompts.build(search) { it }
        val result = BasketResult.from(search, MatchParser.parse("""{"items":[{"item":1,"ids":["1a1"]}]}""", p), listOf("blinkit", "instamart"))
        assertEquals(listOf("instamart"), result.items[0].loginNeeded)
    }

    @Test
    fun anItemNoSiteHasDoesNotHideTheSiteWithEverythingElse() {
        val batter = WantedItem("MTR idli batter", "mtr idli batter", null, 1)
        val search = searches + ItemSearch(batter, listOf(page("blinkit", offer("Khetika Batter", 99.0, "1 kg"))))
        val p = MatchPrompts.build(search) { names.getValue(it) }
        // Blinkit has milk and eggs; nobody has the batter itself.
        val m = MatchParser.parse("""{"items":[{"item":1,"ids":["1a1","1b1"]},{"item":2,"ids":["2a1"]},{"item":3,"ids":[],"similar":["3a1"]}]}""", p)
        val result = BasketResult.from(search, m, listOf("blinkit", "zepto"))
        assertEquals(listOf("MTR idli batter"), result.unavailable.map { it.name })
        assertEquals("blinkit", result.bestSingleSite!!.providerKey)
        assertEquals(122.0, result.bestSingleSite!!.subtotal, 0.0)
    }

    @Test
    fun itemsFoundNowhereAreReported() {
        val result = BasketResult.from(searches, Matches(emptyMap(), emptyMap()), listOf("blinkit", "zepto"))
        assertEquals(listOf("Amul Taaza milk", "Eggs"), result.unavailable.map { it.name })
        assertNull(result.bestSingleSite)
    }
}
