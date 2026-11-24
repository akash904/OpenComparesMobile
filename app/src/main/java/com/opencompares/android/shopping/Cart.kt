package com.opencompares.android.shopping

import com.opencompares.android.prices.Offer
import org.json.JSONObject
import java.util.Locale

/** What a line on a site's bill is for. */
enum class ChargeKind { Items, Delivery, Handling, SmallCart, Platform, Surge, Discount, Other }

data class BillLine(val label: String, val amount: Double) {
    val kind: ChargeKind
        get() {
            val l = label.lowercase(Locale.ROOT)
            return when {
                amount < 0 || DISCOUNT_RE.containsMatchIn(l) -> ChargeKind.Discount
                ITEMS_RE.containsMatchIn(l) -> ChargeKind.Items
                "small cart" in l || "small order" in l || "low cart" in l || "minimum order" in l -> ChargeKind.SmallCart
                "delivery" in l -> ChargeKind.Delivery
                "handling" in l || "packaging" in l -> ChargeKind.Handling
                "platform" in l || "convenience" in l -> ChargeKind.Platform
                "surge" in l || "rain" in l || "late night" in l || "peak" in l -> ChargeKind.Surge
                else -> ChargeKind.Other
            }
        }

    private companion object {
        // Flipkart's is "Price (1 item)".
        val ITEMS_RE = Regex("""^(items? total|item total|sub ?total|mrp total|items? price|cart total|price \(\d+ items?\))""")
        val DISCOUNT_RE = Regex("""discount|saving|coupon|offer|cashback|promo""")
    }
}

/** A site's bill, as shown on its cart page. */
data class Bill(val lines: List<BillLine>, val toPay: Double, val freeDeliveryAbove: Double?) {
    val itemTotal: Double? get() = lines.firstOrNull { it.kind == ChargeKind.Items }?.amount

    /** Everything that isn't the items: fees, minus discounts. */
    val charges: List<BillLine> get() = lines.filter { it.kind != ChargeKind.Items }

    /** Discounts on the bill (negative amounts), e.g. Flipkart's "Discount -₹28" off list prices. */
    val discounts: Double get() = lines.filter { it.kind == ChargeKind.Discount }.sumOf { it.amount }

    /**
     * What the order costs on top of the items at their selling price: delivery, handling and
     * other fees. Discounts lower the items' price; they are not taken off the charges.
     */
    val extra: Double? get() = itemTotal?.let { toPay - (it + discounts) }
}

object BillParser {
    fun parse(json: JSONObject): Bill? {
        if (!json.optBoolean("found")) return null
        val toPay = json.optDouble("toPay").takeIf { !it.isNaN() } ?: return null
        val rows = json.optJSONArray("rows")
        val lines = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            val o = rows!!.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("label").trim().ifEmpty { return@mapNotNull null }
            val amount = o.optDouble("amount").takeIf { !it.isNaN() } ?: return@mapNotNull null
            BillLine(label, amount)
        }
        return Bill(lines, toPay, json.optDouble("freeDeliveryAbove").takeIf { !it.isNaN() && it > 0 })
    }
}

/** What cart_actions.js saw on a product card. */
data class CardState(
    val found: Boolean,
    val count: Int,
    val canAdd: Boolean,
    val canPlus: Boolean,
    val outOfStock: Boolean,
    /** A pack-size picker is open over the page (after "variant" or "confirm"). */
    val picker: Boolean = false,
    /** The card's buttons work; false while a site is still wiring them up, and nothing was pressed. */
    val live: Boolean = true
) {
    companion object {
        fun parse(json: JSONObject) = CardState(
            found = json.optBoolean("found"),
            live = json.optBoolean("live", true),
            count = json.optInt("count", 0),
            canAdd = json.optBoolean("canAdd"),
            canPlus = json.optBoolean("canPlus"),
            outOfStock = json.optBoolean("outOfStock"),
            picker = json.optBoolean("picker")
        )
    }
}

/** A cart page's lines as the cart script sees them: lines with a stepper, and items across them. */
data class CartLines(
    val lines: Int,
    val units: Int,
    /** The script pressed − (or said yes to a "remove?" prompt). */
    val pressed: Boolean
) {
    companion object {
        fun parse(json: JSONObject) = CartLines(
            lines = json.optInt("lines", 0),
            units = json.optInt("units", 0),
            pressed = json.optBoolean("pressed")
        )
    }
}

/** What emptying one site's cart did. */
data class EmptiedCart(
    val providerKey: String,
    val removed: Int,
    /** Items still in the cart afterwards. */
    val left: Int,
    val wasEmpty: Boolean = false,
    val problem: String? = null
) {
    fun describe(name: String): String = when {
        problem != null -> "$name: $problem"
        wasEmpty -> "$name: already empty."
        left == 0 -> "$name: emptied, took out ${items(removed)}."
        else -> "$name: took out ${items(removed)}, ${items(left)} still there. Open its cart to remove the rest."
    }

    private fun items(n: Int) = "$n item${if (n == 1) "" else "s"}"
}

/** One item the app tried to put in a site's cart. */
data class AddedItem(
    val item: WantedItem,
    val offer: Offer,
    val wanted: Int,
    /** How many were in the cart before the app added any. */
    val before: Int,
    /** How many are in the cart now. */
    val inCart: Int,
    /** Why it isn't all there, in words, or null. */
    val problem: String?
) {
    val addedByApp: Int get() = (inCart - before).coerceAtLeast(0)
    val complete: Boolean get() = inCart >= wanted
}

/** One site's cart after the app filled it, with its real bill. */
data class SiteCart(
    val providerKey: String,
    val items: List<AddedItem>,
    val bill: Bill?,
    /** Items already in the cart before the app added anything; they are in the bill too. */
    val itemsBefore: Int?,
    val problem: String?
) {
    val complete: Boolean get() = items.isNotEmpty() && items.all { it.complete } && problem == null

    /**
     * Items in the cart that aren't on the list, as far as the app can tell; they're in the
     * bill too. Units of the listed products that were already there don't count.
     */
    val otherItems: Int get() = itemsBefore?.let { (it - items.sumOf { i -> i.before }).coerceAtLeast(0) } ?: 0
}

/** Units of one listing that the app itself put in a site's cart during this chat. */
data class OwnedUnits(val providerKey: String, val itemId: Int, val offer: Offer, val units: Int)

object CartSync {
    /**
     * What to take back out of each site's cart so it holds only the new list: the app's own
     * units of items no longer on it, of a listing it no longer picks, or beyond the packs now
     * needed. Returns, per site, each listing with how many to take out.
     */
    fun removals(owned: List<OwnedUnits>, result: BasketResult): Map<String, List<Pair<Offer, Int>>> =
        owned.mapNotNull { o ->
            val option = result.items.firstOrNull { it.item.id == o.itemId }?.options?.firstOrNull { it.providerKey == o.providerKey }
            val extra = when {
                option == null || option.offer.name != o.offer.name -> o.units
                else -> o.units - option.packs
            }
            if (extra > 0) o to extra else null
        }.groupBy({ it.first.providerKey }, { it.first.offer to it.second })

    /** The ledger after a fill: each listing's units the app added go on top of what it had. */
    fun afterFill(owned: List<OwnedUnits>, carts: List<SiteCart>): List<OwnedUnits> {
        val next = owned.toMutableList()
        for (cart in carts) for (a in cart.items) {
            if (a.addedByApp <= 0) continue
            val i = next.indexOfFirst { it.providerKey == cart.providerKey && it.itemId == a.item.id && it.offer.name == a.offer.name }
            if (i >= 0) next[i] = next[i].copy(units = next[i].units + a.addedByApp)
            else next += OwnedUnits(cart.providerKey, a.item.id, a.offer, a.addedByApp)
        }
        return next
    }

    /** The ledger after [removed] units of a listing came out of a site's cart. */
    fun afterRemoval(owned: List<OwnedUnits>, site: String, offerName: String, removed: Int): List<OwnedUnits> =
        owned.mapNotNull { o ->
            if (o.providerKey != site || o.offer.name != offerName) o
            else o.copy(units = o.units - removed).takeIf { it.units > 0 }
        }
}

/** How the filled carts compare, all charges included. */
class CartComparison(val carts: List<SiteCart>, private val result: BasketResult) {

    /** Sites whose cart holds every item the site had, with a bill read. */
    val billed: List<SiteCart> = carts.filter { it.bill != null }

    /** Billed carts that also hold items not on the list, so their bills aren't comparable. */
    val mixed: List<SiteCart> = billed.filter { it.otherItems > 0 }

    /** The cheapest bill among carts that hold the whole list (as far as it was found) and nothing else. */
    val best: SiteCart? = billed
        .filter { it.complete && it.otherItems == 0 && it.items.size == result.items.count { plan -> plan.options.isNotEmpty() } }
        .minByOrNull { it.bill!!.toPay }

    /**
     * The least a split by item could cost: each part's items plus that site's charges as
     * seen on its full cart. Smaller orders often pay more (small-cart fees, no free
     * delivery), so the real split costs at least this much. Null without bills for every part.
     */
    val splitAtLeast: Double? = run {
        val split = result.cheapestSplit
        if (split.size < 2) return@run null
        var total = 0.0
        for ((site, options) in split) {
            val extra = carts.firstOrNull { it.providerKey == site }?.bill?.extra ?: return@run null
            total += options.sumOf { it.cost } + extra.coerceAtLeast(0.0)
        }
        total
    }

    /** True when splitting the order can't beat [best], even at its cheapest. */
    val splitNotWorthIt: Boolean
        get() {
            val b = best?.bill?.toPay ?: return false
            val s = splitAtLeast ?: return false
            return s >= b
        }
}
