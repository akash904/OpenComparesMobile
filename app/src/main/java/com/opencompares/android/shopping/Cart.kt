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
        val ITEMS_RE = Regex("""^(items? total|item total|sub ?total|mrp total|items? price|cart total)""")
        val DISCOUNT_RE = Regex("""discount|saving|coupon|offer|cashback|promo""")
    }
}

/** A site's bill, as shown on its cart page. */
data class Bill(val lines: List<BillLine>, val toPay: Double, val freeDeliveryAbove: Double?) {
    val itemTotal: Double? get() = lines.firstOrNull { it.kind == ChargeKind.Items }?.amount

    /** Everything that isn't the items: fees, minus discounts. */
    val charges: List<BillLine> get() = lines.filter { it.kind != ChargeKind.Items }

    /** What the order costs on top of the items. */
    val extra: Double? get() = itemTotal?.let { toPay - it }
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
    val picker: Boolean = false
) {
    companion object {
        fun parse(json: JSONObject) = CardState(
            found = json.optBoolean("found"),
            count = json.optInt("count", 0),
            canAdd = json.optBoolean("canAdd"),
            canPlus = json.optBoolean("canPlus"),
            outOfStock = json.optBoolean("outOfStock"),
            picker = json.optBoolean("picker")
        )
    }
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
}

/** How the filled carts compare, all charges included. */
class CartComparison(val carts: List<SiteCart>, private val result: BasketResult) {

    /** Sites whose cart holds every item the site had, with a bill read. */
    val billed: List<SiteCart> = carts.filter { it.bill != null }

    /** The cheapest bill among carts that hold the whole list (as far as it was found). */
    val best: SiteCart? = billed
        .filter { it.complete && it.items.size == result.items.count { plan -> plan.options.isNotEmpty() } }
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
