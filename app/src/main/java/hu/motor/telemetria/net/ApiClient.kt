package hu.motor.telemetria.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Nyers HttpURLConnection kliens – szándékosan könyvtár nélkül, hogy az app
 * függőségei ne hízzanak. Minden hívás blokkol, ezért IO dispatcheren fusson.
 */
object ApiClient {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000

    /** A szerver által küldött hibaüzenet, hogy a UI-n értelmes szöveg jelenjen meg. */
    class ApiException(val code: Int, message: String) : IOException(message)

    /**
     * @param readTimeoutMs az útvonaltervezés kanyargós stílusnál több jelöltet
     *        is végigszámol, ezért ott hosszabb türelem kell.
     */
    fun postJson(path: String, body: JSONObject, readTimeoutMs: Int = READ_TIMEOUT_MS): JSONObject {
        val connection = open(path, "POST", readTimeoutMs)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        // Egy hosszú túra pontjai tömörítve töredék méretűek; az express magától kicsomagolja.
        connection.setRequestProperty("Content-Encoding", "gzip")
        connection.setChunkedStreamingMode(0)

        try {
            GZIPOutputStream(connection.outputStream).use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * @param readTimeoutMs a túraelemzés az OSM lekérdezés miatt percekig is
     *        tarthat, ezért ott hosszabb türelem kell.
     */
    fun getJson(path: String, readTimeoutMs: Int = READ_TIMEOUT_MS): JSONObject {
        val connection = open(path, "GET", readTimeoutMs)
        try {
            return readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        path: String,
        method: String,
        readTimeoutMs: Int = READ_TIMEOUT_MS
    ): HttpURLConnection {
        val url = URL(ServerSettings.baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-Motor-Token", ServerSettings.token)
        return connection
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (code !in 200..299) {
            val message = runCatching {
                val body = JSONObject(text)
                if (body.isNull("error")) null else body.optString("error").ifBlank { null }
            }.getOrNull()
            throw ApiException(code, message ?: "HTTP $code")
        }
        // A 204-es válasz (élő állapot) üres törzsű.
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
