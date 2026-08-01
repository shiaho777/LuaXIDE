package dev.luaxide.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * A node in the UI tree produced by executing the user's Lua.
 *
 * This is a pure data structure — the "execution is truth" contract: the engine
 * runs the script and hands back this tree, we never parse Lua source to guess
 * at the UI. [UiTreeRenderer] maps each node to a Compose component via the
 * [dev.luaxide.ui.runtime.ComponentRegistry].
 */
data class UiNode(
    val type: String,
    val props: Map<String, PropValue>,
    val children: List<UiNode>,
) {
    /** Convenience typed accessors used by components. */
    fun string(key: String, default: String = ""): String =
        (props[key] as? PropValue.Str)?.value ?: default

    fun number(key: String, default: Double = 0.0): Double =
        (props[key] as? PropValue.Num)?.value ?: default

    fun bool(key: String, default: Boolean = false): Boolean =
        (props[key] as? PropValue.Bool)?.value ?: default

    /** Handler id for an event prop (e.g. "onClick"), or null if not a handler. */
    fun handler(key: String): Int? =
        (props[key] as? PropValue.Handler)?.id
}

/** A property value carried by a [UiNode]. */
sealed interface PropValue {
    data class Str(val value: String) : PropValue
    data class Num(val value: Double) : PropValue
    data class Bool(val value: Boolean) : PropValue
    data class Handler(val id: Int) : PropValue
    data class ListVal(val items: List<PropValue>) : PropValue
    data object Null : PropValue
}

/**
 * Parse the engine's JSON tree into [UiNode]s. Returns null for a "null" tree
 * (script returned no UI). Tolerant of missing fields.
 */
object UiTreeParser {
    fun parse(json: String): UiNode? {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return runCatching { parseNode(JSONObject(trimmed)) }.getOrNull()
    }

    private fun parseNode(obj: JSONObject): UiNode {
        val type = obj.optString("type", "unknown")
        val propsObj = obj.optJSONObject("props") ?: JSONObject()
        val props = buildMap {
            for (key in propsObj.keys()) {
                put(key, parseValue(propsObj.get(key)))
            }
        }
        val childrenArr = obj.optJSONArray("children") ?: JSONArray()
        val children = buildList {
            for (i in 0 until childrenArr.length()) {
                val c = childrenArr.optJSONObject(i) ?: continue
                add(parseNode(c))
            }
        }
        return UiNode(type, props, children)
    }

    private fun parseValue(v: Any?): PropValue = when (v) {
        is Boolean -> PropValue.Bool(v)
        is Int -> PropValue.Num(v.toDouble())
        is Long -> PropValue.Num(v.toDouble())
        is Double -> PropValue.Num(v)
        is String -> PropValue.Str(v)
        is JSONObject -> if (v.has("__handler")) {
            PropValue.Handler(v.optInt("__handler", -1))
        } else {
            PropValue.Null // nested non-ui objects not surfaced as props
        }
        is JSONArray -> PropValue.ListVal(
            (0 until v.length()).map { parseValue(v.get(it)) },
        )
        else -> PropValue.Null
    }
}
