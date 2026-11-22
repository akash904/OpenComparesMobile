package com.opencompares.android.shopping

import com.opencompares.android.prices.Quantity
import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing the shopper wants. [want] is the size of one unit ("1 l", "500 g") when they
 * said or it is obvious, and [count] how many of those, so "2 litres of milk" is 1 l × 2.
 */
data class WantedItem(
    val name: String,
    val search: String,
    val want: String?,
    val count: Int,
    /** Stays the same while the item is changed or replaced, so the app can tell edits from additions. */
    val id: Int = 0
) {
    /** The total amount wanted, when [want] is a size the app understands. */
    val wantedTotal: Quantity?
        get() = Quantity.parse(want)?.let { it.copy(amount = it.amount * count) }

    /** "1 l × 2", "500 g", "× 3" or "" for one of anything. */
    val amountText: String
        get() = when {
            want != null && count > 1 -> "$want × $count"
            want != null -> want
            count > 1 -> "× $count"
            else -> ""
        }
}

/** ChatGPT's answer to a shopping message: a short reply and the whole updated list. */
data class ListUpdate(val reply: String?, val items: List<WantedItem>)

/** How an item differs from the list before the latest message. */
enum class ItemChange { Same, Added, Replaced, Changed }

/** What a message did to the list: each item's change, what replaced items were, what was removed. */
data class ListDiff(
    val changes: Map<Int, ItemChange>,
    val replaced: Map<Int, WantedItem>,
    val removed: List<WantedItem>
) {
    val isEmpty: Boolean get() = removed.isEmpty() && changes.values.all { it == ItemChange.Same }

    companion object {
        /**
         * Keeps ids ChatGPT carried over from [old] (each once) and numbers the rest from
         * [nextId]. Returns the list with ids, and the next free id.
         */
        fun assignIds(old: List<WantedItem>, new: List<WantedItem>, nextId: Int): Pair<List<WantedItem>, Int> {
            val known = old.map { it.id }.toSet()
            val used = HashSet<Int>()
            var next = nextId
            val withIds = new.map { item ->
                if (item.id in known && used.add(item.id)) item else item.copy(id = next++).also { used += it.id }
            }
            return withIds to next
        }

        fun between(old: List<WantedItem>, new: List<WantedItem>): ListDiff {
            val before = old.associateBy { it.id }
            val changes = HashMap<Int, ItemChange>()
            val replaced = HashMap<Int, WantedItem>()
            new.forEach { item ->
                val was = before[item.id]
                changes[item.id] = when {
                    was == null -> ItemChange.Added
                    was == item -> ItemChange.Same
                    !was.name.equals(item.name, ignoreCase = true) || !was.search.equals(item.search, ignoreCase = true) -> {
                        replaced[item.id] = was
                        ItemChange.Replaced
                    }
                    else -> ItemChange.Changed
                }
            }
            val kept = new.map { it.id }.toSet()
            return ListDiff(changes, replaced, old.filter { it.id !in kept })
        }
    }
}

class ShoppingFormatException(message: String) : Exception(message)

object ShoppingListPrompts {
    const val MAX_ITEMS = 15

    val INSTRUCTIONS = """
        You help a shopper in India plan one grocery order from quick-commerce apps such as Blinkit, Zepto and Instamart.
        Turn their message into a shopping list. If there is a current list, return the whole updated list:
        add, remove or change items as they ask, and keep the rest exactly as it is.
        Every item on the current list has an id. Keep an item's id whenever the shopper changes it,
        including when they want something else in its place: "instead", "use", "swap", "change to",
        "make it", "not X but Y", or naming a similar product they were shown. Give no id (null) only to
        items that are truly extra, as in "also", "add" or "and". If it is unclear, and the new thing
        does the same job as an item already on the list, replace that item and say so in reply.
        For each item give:
        - name: what they want, in a few words, with the brand only if they named one
        - search: a short phrase to type into the shopping app's search box
        - want: the size of one unit if they said it or it is obvious, like "1 l", "500 g", "6 pcs", otherwise null
        - count: how many of those units, usually 1
        At most $MAX_ITEMS items. Do not invent items they did not ask for.
        Reply with JSON only, without code fences, in exactly this shape:
        {"reply":"one short friendly sentence saying what changed","items":[{"id":1,"name":"Amul Taaza toned milk","search":"amul taaza milk","want":"1 l","count":2},{"id":null,"name":"Bread","search":"bread","want":null,"count":1}]}
        If the message is not about shopping, say so briefly in reply and return the current list unchanged.
    """.trimIndent()

    fun input(message: String, current: List<WantedItem>): String = buildString {
        if (current.isEmpty()) {
            append("Current list: empty\n")
        } else {
            append("Current list:\n")
            current.forEach { append("- ").append(it.toJson().toString()).append('\n') }
        }
        append("\nShopper says: ").append(message.trim())
    }

    fun parse(reply: String): ListUpdate {
        val json = extractJson(reply) ?: throw ShoppingFormatException("No JSON object in the reply")
        val array = json.optJSONArray("items") ?: throw ShoppingFormatException("No items list")
        return ListUpdate(
            reply = json.optString("reply").trim().ifEmpty { null }?.take(300),
            items = parseItems(array)
        )
    }

    private fun parseItems(array: JSONArray): List<WantedItem> {
        val items = mutableListOf<WantedItem>()
        for (i in 0 until array.length()) {
            if (items.size >= MAX_ITEMS) break
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name").trim().take(80)
            if (name.isEmpty()) continue
            val search = o.optString("search").trim().take(80).ifEmpty { name }
            val want = if (o.isNull("want")) null else o.optString("want").trim().ifEmpty { null }?.take(20)
            val count = o.optInt("count", 1).coerceIn(1, 20)
            val id = if (o.isNull("id")) 0 else o.optInt("id", 0).coerceAtLeast(0)
            items += WantedItem(name, search, want, count, id)
        }
        return items.distinctBy { it.search.lowercase() }
    }

    private fun WantedItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("search", search)
        .put("want", want ?: JSONObject.NULL)
        .put("count", count)
}

/** Accepts a bare object, one wrapped in code fences, or one with stray text around it. */
internal fun extractJson(reply: String): JSONObject? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(reply.substring(start, end + 1)) }.getOrNull()
}
