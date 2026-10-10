package se.jonasp.rinkside

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One GET of <base>/api/live/<name>.json with HttpURLConnection. Short timeouts; the caller does the back-off. */
object OverlayHttp {
    class Response(val code: Int, val body: String)

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 256 * 1024

    fun get(baseUrl: String, name: String): Response {
        val url = URL(baseUrl.trimEnd('/') + "/api/live/" + name + ".json")
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.useCaches = false
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "RinksideAndroid/" + BuildConfig.VERSION_NAME)
            val code = c.responseCode
            val stream: InputStream? = if (code in 200..299) c.inputStream else c.errorStream
            val body = if (stream != null) stream.use { readLimited(it) } else ""
            return Response(code, body)
        } finally {
            c.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw java.io.IOException("Response too large")
        }
        return out.toString("UTF-8")
    }
}
