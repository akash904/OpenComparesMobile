package com.opencompares.android.shopping

import com.opencompares.android.prices.ComparisonPrompts
import com.opencompares.android.prices.Offer
import com.opencompares.android.prices.PageOffers
import com.opencompares.android.prices.formatRupees
import java.util.Locale
import kotlin.math.ceil

/** What each site showed when searched for one wanted item. */
data class ItemSearch(val item: WantedItem, val pages: List<PageOffers>)

/** A listing sent to ChatGPT for matching, with the short id it is referred to by. */
data class Candidate(val id: String, val itemIndex: Int, val providerKey: String, val offer: Offer)

class MatchPrompt(val instructions: String, val input: String, val candidates: Map<String, Candidate>)

/**
 * Asks ChatGPT which listing on each site is the item the shopper wants. Only names,
 * sizes, prices and stock are sent; out-of-stock listings are left out.
 */
object MatchPrompts {
    /** Listings per site per item; the top of a search page is where the matches are. */
    const val PER_SITE = 10

    val INSTRUCTIONS = """
        You match a shopper's grocery list to listings an app read from shopping sites in India.
        For each wanted item, pick on each site the one listing that is that item: the same product,
        the same brand if one was named, the same variant (toned or full cream, flavour, and so on).
        Prefer a pack size that fits the amount wanted. If nothing on a site is really that item,
        leave that site out of "ids" rather than picking something different.
        For a site left out, you may put its closest listing in "similar": another brand, variant
        or form the shopper might accept instead (for example an idli mix when they asked for batter).
        Use only the listing ids given, and only ids listed under that item.
        Reply with JSON only, without code fences, in exactly this shape:
        {"items":[{"item":1,"ids":["1a3","1b2"],"similar":["1c5"],"note":"optional short note, or empty"}]}
    """.trimIndent()

    fun build(searches: List<ItemSearch>, siteName: (String) -> String): MatchPrompt {
        val siteCodes = LinkedHashMap<String, String>()
        searches.flatMap { s -> s.pages.map { it.providerKey } }.distinct().forEachIndexed { i, key ->
            siteCodes[key] = ComparisonPrompts.siteCode(i)
        }
        val candidates = LinkedHashMap<String, Candidate>()
        val input = StringBuilder("Each listing: id | name | pack size | price | price per unit\n")
        searches.forEachIndexed { index, search ->
            val itemNo = index + 1
            val item = search.item
            input.append("\nItem ").append(itemNo).append(": ").append(item.name)
            if (item.amountText.isNotEmpty()) input.append(", wanted ").append(item.amountText)
            input.append('\n')
            search.pages.forEach { page ->
                val code = siteCodes.getValue(page.providerKey)
                val offers = page.offers.filter { it.inStock }.take(PER_SITE)
                if (offers.isEmpty()) return@forEach
                input.append("Site ").append(code).append(": ").append(siteName(page.providerKey)).append('\n')
                offers.forEachIndexed { n, offer ->
                    val id = "$itemNo$code${n + 1}"
                    candidates[id] = Candidate(id, index, page.providerKey, offer)
                    input.append(id).append(" | ").append(offer.name.replace('|', '/'))
                        .append(" | ").append(offer.quantity?.toString() ?: offer.quantityText ?: "size unknown")
                        .append(" | ").append(formatRupees(offer.price))
                        .append(" | ").append(offer.unitPrice?.toString() ?: "-")
                        .append('\n')
                }
            }
        }
        return MatchPrompt(INSTRUCTIONS, input.toString(), candidates)
    }
}

/**
 * For each item index: the listing that is that item on each site, a similar listing on
 * sites without one, and ChatGPT's note.
 */
data class Matches(
    val picks: Map<Int, Map<String, Candidate>>,
    val notes: Map<Int, String>,
    val similar: Map<Int, Map<String, Candidate>> = emptyMap()
)

object MatchParser {
    /** Ids that don't exist, or belong to a different item, are dropped; one listing per site. */
    fun parse(reply: String, prompt: MatchPrompt): Matches {
        val json = extractJson(reply) ?: throw ShoppingFormatException("No JSON object in the reply")
        val items = json.optJSONArray("items") ?: throw ShoppingFormatException("No items list")
        val picks = HashMap<Int, LinkedHashMap<String, Candidate>>()
        val similar = HashMap<Int, LinkedHashMap<String, Candidate>>()
        val notes = HashMap<Int, String>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val index = o.optInt("item", 0) - 1
            if (index < 0) continue
            val ids = o.optJSONArray("ids") ?: continue
            val chosen = picks.getOrPut(index) { LinkedHashMap() }
            for (j in 0 until ids.length()) {
                val c = prompt.candidates[ids.optString(j).trim().lowercase(Locale.ROOT)] ?: continue
                if (c.itemIndex != index || c.providerKey in chosen) continue
                chosen[c.providerKey] = c
            }
            // A similar listing only stands in on a site with no exact match.
            o.optJSONArray("similar")?.let { ids2 ->
                val alt = similar.getOrPut(index) { LinkedHashMap() }
                for (j in 0 until ids2.length()) {
                    val c = prompt.candidates[ids2.optString(j).trim().lowercase(Locale.ROOT)] ?: continue
                    if (c.itemIndex != index || c.providerKey in chosen || c.providerKey in alt) continue
                    alt[c.providerKey] = c
                }
            }
            o.optString("note").trim().take(200).ifEmpty { null }?.let { notes[index] = it }
        }
        return Matches(picks, notes, similar)
    }
}

/** Buying one item on one site: which listing, and how many packs. */
data class ItemOption(
    val providerKey: String,
    val offer: Offer,
    val packs: Int,
    /** The packs add up to half as much again as wanted or more, like 2 kg for 1 kg. */
    val moreThanWanted: Boolean = false
) {
    val cost: Double get() = offer.price * packs

    /** "2 × 500 ml", or just the pack size for one pack. */
    val amountText: String
        get() {
            val size = offer.quantity?.toString() ?: offer.quantityText ?: ""
            return when {
                size.isEmpty() -> if (packs > 1) "$packs packs" else ""
                packs > 1 -> "$packs × $size"
                else -> size
            }
        }
}

/**
 * Packs needed to cover the amount wanted: 1 l × 2 from 500 ml packs is 4 packs.
 * When the sizes can't be compared, it is the number of units asked for.
 */
fun packsNeeded(item: WantedItem, offer: Offer): Int {
    return packsFor(item, offer).first
}

/** A pack up to 15% short of what is wanted still counts as enough. */
private const val PACK_TOLERANCE = 0.15

/** Packs needed, and whether they come to half as much again as wanted or more. */
private fun packsFor(item: WantedItem, offer: Offer): Pair<Int, Boolean> {
    val total = item.wantedTotal
    val pack = offer.quantity
    if (total != null && pack != null && total.unit == pack.unit && pack.amount > 0) {
        // Close enough counts: a "900 g - 1 kg" pack is one pack of the 1 kg asked for, not two.
        val packs = maxOf(1, ceil(total.amount / pack.amount - PACK_TOLERANCE).toInt())
        return packs to (packs * pack.amount >= total.amount * 1.5)
    }
    return item.count to false
}

/** One wanted item across sites, cheapest first. */
data class ItemPlan(
    val item: WantedItem,
    val options: List<ItemOption>,
    val note: String?,
    /** Sites that showed a login page instead of results for this item. */
    val loginNeeded: List<String> = emptyList(),
    /** Close but not the same, on sites without the item itself. Not counted in totals. */
    val similar: List<ItemOption> = emptyList()
) {
    val cheapest: ItemOption? get() = options.firstOrNull()

    /** True when one site is cheaper than every other, not just tied for cheapest. */
    val hasClearCheapest: Boolean
        get() = options.size > 1 && options[1].cost - options[0].cost >= 0.005
}

/** What one site can supply, before delivery and other charges. */
data class SiteTotal(val providerKey: String, val found: List<ItemOption>, val missing: List<WantedItem>) {
    val subtotal: Double get() = found.sumOf { it.cost }
    val complete: Boolean get() = missing.isEmpty()
}

/**
 * Item prices per site for the whole list, before delivery, handling and other charges.
 * Those come from each site's cart in a later step.
 */
class BasketResult(val items: List<ItemPlan>, siteKeys: List<String>) {

    /**
     * Sites with everything first (cheapest first), then the ones with the most items.
     * Items no site has don't count as missing anywhere, so one of them can't hide the
     * site that has everything else.
     */
    val sites: List<SiteTotal> = siteKeys.map { key ->
        val found = items.mapNotNull { plan -> plan.options.firstOrNull { it.providerKey == key } }
        val missing = items
            .filter { plan -> plan.options.isNotEmpty() && plan.options.none { it.providerKey == key } }
            .map { it.item }
        SiteTotal(key, found, missing)
    }.sortedWith(compareBy<SiteTotal>({ !it.complete }, { it.missing.size }, { it.subtotal }))

    val bestSingleSite: SiteTotal? = sites.firstOrNull { it.complete && it.found.isNotEmpty() }

    /** Each item from wherever it is cheapest, grouped by site. */
    val cheapestSplit: Map<String, List<ItemOption>> =
        items.mapNotNull { it.cheapest }.groupBy { it.providerKey }

    val cheapestSplitTotal: Double = cheapestSplit.values.flatten().sumOf { it.cost }

    /** Items no site had. */
    val unavailable: List<WantedItem> = items.filter { it.options.isEmpty() }.map { it.item }

    companion object {
        fun from(searches: List<ItemSearch>, matches: Matches, siteKeys: List<String>): BasketResult {
            // On equal prices, prefer the site that has more of the list: fewer orders to place.
            val coverage = matches.picks.values.flatMap { it.keys }.groupingBy { it }.eachCount()
            val plans = searches.mapIndexed { index, search ->
                val options = matches.picks[index].orEmpty().values
                    .map { c ->
                        val (packs, more) = packsFor(search.item, c.offer)
                        ItemOption(c.providerKey, c.offer, packs, more)
                    }
                    .sortedWith(compareBy<ItemOption>({ Math.round(it.cost * 100) }, { -(coverage[it.providerKey] ?: 0) }))
                val loginNeeded = search.pages.filter { it.needsLogin }.map { it.providerKey }
                val similar = matches.similar[index].orEmpty().values
                    .map { c -> ItemOption(c.providerKey, c.offer, packsFor(search.item, c.offer).first) }
                    .sortedBy { it.cost }
                ItemPlan(search.item, options, matches.notes[index], loginNeeded, similar)
            }
            return BasketResult(plans, siteKeys)
        }
    }
}
