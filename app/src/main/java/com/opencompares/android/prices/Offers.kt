package com.opencompares.android.prices

import org.json.JSONObject
import java.util.Locale

/** A pack size in a base unit: grams, millilitres or pieces. */
data class Quantity(val amount: Double, val unit: BaseUnit) {
    enum class BaseUnit(val label: String) { GRAM("g"), MILLILITRE("ml"), PIECE("pc") }

    override fun toString(): String = when {
        unit == BaseUnit.GRAM && amount >= 1000 -> trim(amount / 1000) + " kg"
        unit == BaseUnit.MILLILITRE && amount >= 1000 -> trim(amount / 1000) + " l"
        else -> trim(amount) + " " + unit.label
    }

    companion object {
        // "450-550g", "1.95-2.05Kg": a range of the same unit.
        private val RANGE = Regex(
            """(\d+(?:\.\d+)?)\s?(?:-|–|to)\s?(\d+(?:\.\d+)?)\s?(g|gm|gms|grams?|kg|kgs|ml|l|ltr|litres?|liters?)\b""",
            RegexOption.IGNORE_CASE
        )

        private val PATTERN = Regex(
            """(\d+(?:\.\d+)?)\s?(?:(?:x|×)\s?(\d+(?:\.\d+)?)\s?)?(g|gm|gms|grams?|kg|kgs|ml|l|ltr|litres?|liters?|pc|pcs|pieces?|pack|units?|dozen|tabs?|tablets?|caps?|sachets?)\b(?:\s?(?:x|×)\s?(\d+))?""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Parses texts like "500 g", "1 kg", "2 x 200 ml", "200 ml x 2" or "6 pcs".
         * A leading count multiplies: "2 x 200 ml" is 400 ml.
         */
        fun parse(text: String?): Quantity? {
            if (text.isNullOrBlank()) return null
            // A weight range is sold by its middle: "450-550g" is about 500 g.
            RANGE.find(text)?.let { r ->
                val low = r.groupValues[1].toDouble()
                val high = r.groupValues[2].toDouble()
                Quantity.parse("${trim((low + high) / 2)} ${r.groupValues[3]}")?.let { return it }
            }
            // "1 pack (500 ml)": a weight or volume says more than a pack or piece count,
            // and "1 pack (6 pcs)": a piece count says more than "pack".
            val matches = PATTERN.findAll(text).toList()
            val m = matches.firstOrNull { isWeightOrVolume(it.groupValues[3]) }
                ?: matches.firstOrNull { !it.groupValues[3].equals("pack", ignoreCase = true) }
                ?: matches.firstOrNull()
                ?: return null
            val first = m.groupValues[1].toDoubleOrNull() ?: return null
            val second = m.groupValues[2].toDoubleOrNull()
            val trailingCount = m.groupValues[4].toDoubleOrNull()
            // "2 x 200 ml": count first, size second. "200 ml": size only.
            var size = second ?: first
            val count = (if (second != null) first else 1.0) * (trailingCount ?: 1.0)
            val (factor, unit) = when (m.groupValues[3].lowercase(Locale.ROOT)) {
                "g", "gm", "gms", "gram", "grams" -> 1.0 to BaseUnit.GRAM
                "kg", "kgs" -> 1000.0 to BaseUnit.GRAM
                "ml" -> 1.0 to BaseUnit.MILLILITRE
                "l", "ltr", "litre", "litres", "liter", "liters" -> 1000.0 to BaseUnit.MILLILITRE
                "dozen" -> 12.0 to BaseUnit.PIECE
                else -> 1.0 to BaseUnit.PIECE
            }
            size *= factor
            val total = size * count
            return if (total > 0) Quantity(total, unit) else null
        }

        private fun isWeightOrVolume(unit: String): Boolean =
            unit.lowercase(Locale.ROOT) in setOf(
                "g", "gm", "gms", "gram", "grams", "kg", "kgs",
                "ml", "l", "ltr", "litre", "litres", "liter", "liters"
            )

        private fun trim(v: Double): String =
            if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}

/** One product as shown on one marketplace page. */
data class Offer(
    val name: String,
    val price: Double,
    val mrp: Double?,
    val discountText: String?,
    val quantity: Quantity?,
    val quantityText: String?,
    val etaText: String?,
    val inStock: Boolean,
    val canAdd: Boolean,
    val link: String?
) {
    /** Saving against the list price, in percent, when both are known. */
    val discountPercent: Int?
        get() = mrp?.takeIf { it > price }?.let { Math.round((it - price) / it * 100).toInt() }

    /**
     * Price per 100 g, per 100 ml or per piece. Normalising this way lets a 500 g
     * pack on one site be compared with a 1 kg pack on another.
     */
    val unitPrice: UnitPrice?
        get() {
            val q = quantity ?: return null
            return when (q.unit) {
                Quantity.BaseUnit.GRAM -> UnitPrice(price / q.amount * 100, "100 g")
                Quantity.BaseUnit.MILLILITRE -> UnitPrice(price / q.amount * 100, "100 ml")
                Quantity.BaseUnit.PIECE -> UnitPrice(price / q.amount, "pc")
            }
        }
}

data class UnitPrice(val amount: Double, val per: String) {
    override fun toString(): String = "${formatRupees(amount)}/$per"
}

/** What was read from one pane at one moment. */
data class PageOffers(
    val providerKey: String,
    val url: String,
    val title: String,
    val offers: List<Offer>,
    /** Price-looking texts seen on the page; helps tell "no products" from "page not loaded". */
    val scannedPrices: Int,
    /** The page shows no prices but asks the user to log in. */
    val needsLogin: Boolean,
    val readAtMillis: Long,
    /** The page wants a delivery address before it shows products. */
    val needsLocation: Boolean = false
)

/** Reads a rupee amount from text like "₹1,299", "Rs. 45.50" or "INR 99". */
fun parseRupees(text: String?): Double? {
    if (text.isNullOrBlank()) return null
    val m = Regex("""(?:₹|rs\.?|inr)?\s?(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE).find(text) ?: return null
    return m.groupValues[1].replace(",", "").toDoubleOrNull()
}

/** "₹55" for whole amounts (after rounding to paise, so 55.0000001 is "₹55"), else "₹7.10". */
fun formatRupees(amount: Double): String {
    val paise = Math.round(amount * 100)
    return if (paise % 100 == 0L) "₹" + paise / 100
    else "₹" + String.format(Locale.ROOT, "%.2f", paise / 100.0)
}

/** Turns the extractor script's JSON into [PageOffers]. Malformed products are skipped. */
object OfferParser {
    fun parse(providerKey: String, json: JSONObject, nowMillis: Long = System.currentTimeMillis()): PageOffers {
        val items = json.optJSONArray("products")
        val offers = mutableListOf<Offer>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val name = o.optString("name").trim().ifEmpty { null } ?: continue
                val price = parseRupees(o.optString("priceText")) ?: continue
                if (price <= 0) continue
                val mrp = parseRupees(o.optNullableString("mrpText"))?.takeIf { it > price }
                val quantityText = o.optNullableString("quantityText")
                offers += Offer(
                    name = name,
                    price = price,
                    mrp = mrp,
                    discountText = o.optNullableString("discountText"),
                    quantity = Quantity.parse(quantityText) ?: Quantity.parse(name),
                    quantityText = quantityText,
                    etaText = o.optNullableString("etaText"),
                    inStock = o.optBoolean("inStock", true),
                    canAdd = o.optBoolean("canAdd", false),
                    link = o.optNullableString("link")
                )
            }
        }
        return PageOffers(
            providerKey = providerKey,
            url = json.optString("url"),
            title = json.optString("title"),
            offers = offers.distinctBy { it.name.lowercase(Locale.ROOT) to it.price },
            scannedPrices = json.optInt("scannedPrices", 0),
            needsLogin = json.optBoolean("needsLogin", false),
            needsLocation = json.optBoolean("needsLocation", false),
            readAtMillis = nowMillis
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifEmpty { null }
}
