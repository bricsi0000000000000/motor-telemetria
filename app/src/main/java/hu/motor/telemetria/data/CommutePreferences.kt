package hu.motor.telemetria.data

import android.content.Context
import hu.motor.telemetria.net.Waypoint
import org.json.JSONArray
import org.json.JSONObject

/** Az oda- és visszaút szabályai a telefonon, egymástól függetlenül maradnak meg. */
class CommutePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("commute_routes", Context.MODE_PRIVATE)
    data class Rules(val fixed: List<Waypoint> = emptyList(), val shops: List<Waypoint> = emptyList())

    fun load(key: String): Rules = runCatching {
        val json = JSONObject(prefs.getString(key, "{}") ?: "{}")
        Rules(Waypoint.parseList(json.optJSONArray("fixed")), Waypoint.parseList(json.optJSONArray("shops")))
    }.getOrDefault(Rules())

    fun save(key: String, rules: Rules) {
        fun array(points: List<Waypoint>) = JSONArray().apply { points.forEach { put(it.toJson()) } }
        prefs.edit().putString(key, JSONObject().put("fixed", array(rules.fixed))
            .put("shops", array(rules.shops)).toString()).apply()
    }
}
