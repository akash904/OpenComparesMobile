package com.opencompares.android.prices

import org.json.JSONObject
import java.util.Locale

/** An offer as sent to ChatGPT, with the short id the model refers to it by. */
data class ListedOffer(val id: String, val providerKey: String, val offer: Offer)

/** What is sent to ChatGPT for one comparison, and the id table needed to read its answer. */
class ComparisonPrompt(
    val instructions: String,
    val input: String,
    val listings: Map<String, ListedOffer>
) {
    val siteCount: Int get() = listings.values.map { it.providerKey }.distinct().size
}

/**
 * Builds the comparison request. Only product names, sizes, prices, delivery times and
 * stock are sent: no page addresses, links, location or account details.
 */
object ComparisonPrompts {
    const val MAX_MATCHES = 12

    val INSTRUCTIONS = """
        You help a shopper in India compare listings that an app read from several shopping sites.
        Group listings that are the same product: same brand, same product line and same variant
        (for example toned vs full cream milk, flavour, fat percentage, organic or not).
        Pack sizes may differ inside a group. Never group different brands or different variants.
        Only return groups with listings from at least two different sites.
        Use only the listing ids given. List the groups most relevant to the search first, at most $MAX_MATCHES.
        Reply with JSON only, without code fences or any other text, in exactly this shape:
        {"matches":[{"title":"short product name without pack size","ids":["a1","b3"]}],"summary":"one or two plain sentences for the shopper"}
        In the summary, mention only prices that appear in the listings.
    """.trimIndent()

    fun build(query: String, pages: List<PageOffers>, siteName: (String) -> String): ComparisonPrompt {
        val listings = LinkedHashMap<String, ListedOffer>()
        val input = StringBuilder()
        input.append("Search: ").append(query.ifBlank { "(none, the shopper is browsing)" }).append('\n')
        input.append("Each listing: id | name | pack size | price | price per unit | delivery | stock\n")
        pages.forEachIndexed { siteIndex, page ->
            val code = siteCode(siteIndex)
            input.append('\n').append("Site ").append(code).append(": ").append(siteName(page.providerKey)).append('\n')
            page.offers.forEachIndexed { offerIndex, offer ->
                val id = code + (offerIndex + 1)
                listings[id] = ListedOffer(id, page.providerKey, offer)
                input.append(describe(id, offer)).append('\n')
            }
        }
        return ComparisonPrompt(INSTRUCTIONS, input.toString(), listings)
    }

    private fun describe(id: String, o: Offer): String = listOf(
        id,
        o.name.replace('|', '/'),
        o.quantity?.toString() ?: o.quantityText ?: "size unknown",
        formatRupees(o.price) + (o.mrp?.let { " (list ${formatRupees(it)})" } ?: ""),
        o.unitPrice?.toString() ?: "-",
        o.etaText ?: "-",
        if (o.inStock) "in stock" else "out of stock"
    ).joinToString(" | ")

    /** a, b, … z, then aa, ab, … so ids stay short and never look like numbers. */
    internal fun siteCode(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'a' + i % 26)
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }
}

/** The same product as listed on two or more sites, best value first. */
class ProductMatch(val title: String, listed: List<ListedOffer>) {

    /** "100 ml", "100 g" or "pc" when unit prices decide; null when the plain price does. */
    val comparedPer: String?

    /** Offers in order of value: comparable in-stock offers by cost, then the rest. */
    val offers: List<ListedOffer>

    /** The best value in stock, or null when nothing can be compared. */
    val best: ListedOffer?

    /** How much cheaper [best] is, per unit, than the best offer on any other site. */
    val leadPercent: Int?

    /** Another site matches [best] to the paisa, so neither is better. */
    val tie: Boolean

    init {
        val inStock = listed.filter { it.offer.inStock }
        comparedPer = inStock.mapNotNull { it.offer.unitPrice?.per }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val cost = { l: ListedOffer ->
            if (comparedPer != null) l.offer.unitPrice?.takeIf { it.per == comparedPer }?.amount else l.offer.price
        }
        val ranked = inStock.filter { cost(it) != null }.sortedBy { cost(it)!! }
        offers = ranked + listed.filter { it !in ranked }
        best = ranked.firstOrNull()
        val bestCost = best?.let(cost)
        val rivalCost = best?.let { b -> ranked.firstOrNull { it.providerKey != b.providerKey } }?.let(cost)
        // ₹62 for 1 l and ₹31 for 500 ml can differ in the last floating-point digit.
        tie = bestCost != null && rivalCost != null && rivalCost - bestCost < 0.005
        leadPercent = if (bestCost == null || rivalCost == null || tie) null
        else Math.round((rivalCost - bestCost) / rivalCost * 100).toInt().takeIf { it > 0 }
    }

    val providerKeys: Set<String> get() = offers.map { it.providerKey }.toSet()
}

data class Comparison(
    /** ChatGPT's own words; may be absent. Prices shown elsewhere come from the pages, not the model. */
    val summary: String?,
    val matches: List<ProductMatch>,
    val sitesCompared: Int,
    val offersCompared: Int
)

class ComparisonFormatException(message: String) : Exception(message)

/**
 * Reads ChatGPT's JSON answer. The model only says which listings belong together;
 * ids it made up are dropped, and every price comes from what the app read itself.
 */
object ComparisonParser {
    fun parse(reply: String, prompt: ComparisonPrompt): Comparison {
        val json = extractJson(reply) ?: throw ComparisonFormatException("No JSON object in the reply")
        val matchesJson = json.optJSONArray("matches") ?: throw ComparisonFormatException("No matches list")
        val used = HashSet<String>()
        val matches = mutableListOf<ProductMatch>()
        for (i in 0 until matchesJson.length()) {
            if (matches.size >= ComparisonPrompts.MAX_MATCHES) break
            val m = matchesJson.optJSONObject(i) ?: continue
            val ids = m.optJSONArray("ids") ?: continue
            // Each listing belongs to one product only.
            val listed = (0 until ids.length())
                .map { ids.optString(it).trim().lowercase(Locale.ROOT) }
                .distinct()
                .filter { it !in used }
                .mapNotNull { prompt.listings[it] }
            if (listed.map { it.providerKey }.distinct().size < 2) continue
            used += listed.map { it.id }
            val title = m.optString("title").trim().ifEmpty { listed.first().offer.name }
            matches += ProductMatch(title.take(120), listed)
        }
        return Comparison(
            summary = json.optString("summary").trim().ifEmpty { null }?.take(600),
            matches = matches,
            sitesCompared = prompt.siteCount,
            offersCompared = prompt.listings.size
        )
    }

    /** Accepts a bare object, one wrapped in code fences, or one with stray text around it. */
    private fun extractJson(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
    }
}
