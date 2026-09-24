package hu.motor.telemetria.net

import org.json.JSONObject

/**
 * Az Android `optString`-je a JSON `null`-ból a "null" *szöveget* csinálja
 * (a JSONObject.NULL toString-je), ezért mindenhol ezt kell használni, ahol a
 * mező hiányozhat – különben a felületen "null" jelenik meg.
 */
fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
