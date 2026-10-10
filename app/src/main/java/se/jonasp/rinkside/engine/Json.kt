package se.jonasp.rinkside.engine

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small strict helpers on top of org.json (Android's own copy at runtime, org.json:json in the JVM unit tests).
 * They only use opt(), instanceof checks and length(), which behave the same in both.
 */
internal object Json {
    fun parseObject(text: String?): JSONObject? {
        if (text == null || text.isBlank()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    fun str(o: JSONObject, key: String): String? = o.opt(key) as? String

    fun obj(o: JSONObject, key: String): JSONObject? = o.opt(key) as? JSONObject

    fun arr(o: JSONObject, key: String): JSONArray? = o.opt(key) as? JSONArray

    fun bool(o: JSONObject, key: String): Boolean? = o.opt(key) as? Boolean

    /** A whole number in [min, max], or null for anything else (strings, fractions, nulls, out of range). */
    fun int(v: Any?, min: Int, max: Int): Int? {
        val n = v as? Number ?: return null
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite() || d != Math.rint(d)) return null
        if (d < min || d > max) return null
        return d.toInt()
    }

    fun long(v: Any?): Long? {
        val n = v as? Number ?: return null
        val d = n.toDouble()
        if (d.isNaN() || d.isInfinite() || d != Math.rint(d)) return null
        return n.toLong()
    }

    /** [home, away] with two whole goals 0..99, as the overlays send scores. */
    fun goals(v: Any?): Pair<Int, Int>? {
        val a = v as? JSONArray ?: return null
        if (a.length() != 2) return null
        val h = int(a.opt(0), 0, 99) ?: return null
        val g = int(a.opt(1), 0, 99) ?: return null
        return Pair(h, g)
    }

    fun iso(v: Any?): Long? {
        val s = v as? String ?: return null
        if (s.length > 40) return null
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
