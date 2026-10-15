package se.jonasp.rinkside

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import se.jonasp.rinkside.engine.SseDecoder
import se.jonasp.rinkside.engine.SseEvent
import se.jonasp.rinkside.engine.SseLineReader
import se.jonasp.rinkside.engine.StreamFeed
import se.jonasp.rinkside.engine.StreamPlan
import se.jonasp.rinkside.engine.WatchedGame

/** What the settings screen shows about the live stream. One process, so plain volatile fields (like [GameWatchService.running]). */
object StreamStatus {
    enum class State { OFF, CONNECTING, CONNECTED, WAITING }

    @Volatile
    var state: State = State.OFF

    /** Failures in a row so far (0 while a connection is good). */
    @Volatile
    var failures: Int = 0

    /** When the next attempt starts (device time), while [state] is WAITING. */
    @Volatile
    var retryAtMs: Long = 0L

    /** The last event of any kind on the connection (device time), 0 before the first. */
    @Volatile
    var lastEventAtMs: Long = 0L

    /** The last message about a watched game that went to the detector (device time), 0 before the first. */
    @Volatile
    var lastGameMessageAtMs: Long = 0L

    @Volatile
    var lastError: String = ""

    @Volatile
    var applied: Int = 0

    @Volatile
    var replayed: Int = 0
}

/**
 * The one connection to the SHL's public live stream (`GET https://game-broadcaster.s8y.se/live/game`, Server-Sent Events), held while at least one
 * watched SHL game is inside its watch window. The reader runs on its own thread, line by line, and hands every event to [StreamFeed]; what the
 * feed makes of it ([StreamFeed.Result]) goes to [onResult] on that same thread.
 *
 * [update] is called by the watch loop on each round with the SHL games to follow: none closes the connection, some open it (or keep it).
 * [stop] closes it for good. A connection that fails or ends is reopened after 2 s, 5 s, 10 s, 30 s, then every 60 s; a connection that stayed up
 * for [StreamPlan.STABLE_AFTER_MS] starts the series from the beginning. A connection that is silent for [StreamPlan.READ_TIMEOUT_MS] counts as dead.
 *
 * The thread takes no wake lock of its own: it lives inside the watch service, which holds the one wake lock and the foreground notification.
 * No permission beyond INTERNET is needed (the network state is not asked for: a dead network is a failed connection and the back-off retries).
 */
class LiveStream(
    private val app: Context,
    private val feed: StreamFeed,
    private val onResult: (StreamFeed.Result) -> Unit,
) {
    @Volatile
    private var games: List<WatchedGame> = emptyList()

    @Volatile
    private var wanted = false

    @Volatile
    private var connection: HttpURLConnection? = null
    private var thread: Thread? = null
    private val pause = Object()
    private var connectedAt = 0L

    /** The watch loop's word on which SHL games are to be followed right now. */
    @Synchronized
    fun update(watched: List<WatchedGame>) {
        games = watched
        if (watched.isEmpty()) {
            if (wanted) Store.log(app, "Live stream closed (no SHL game in its window)")
            shutDown()
            return
        }
        wanted = true
        if (thread?.isAlive != true) {
            val t = Thread({ run() }, "rinkside-stream")
            t.isDaemon = true
            thread = t
            t.start()
        }
    }

    @Synchronized
    fun stop() {
        shutDown()
    }

    private fun shutDown() {
        wanted = false
        val open = connection
        if (open != null) {
            // Closing ends a read that is blocked in the reader thread. Not on the main thread: Android does not like sockets touched there.
            if (Looper.myLooper() == Looper.getMainLooper()) Thread({ close(open) }, "rinkside-stream-close").start() else close(open)
        }
        synchronized(pause) { pause.notifyAll() }
    }

    private fun close(c: HttpURLConnection) {
        try {
            c.disconnect()
        } catch (e: RuntimeException) {
            // already closed
        }
    }

    private fun run() {
        var failures = 0
        StreamStatus.applied = 0
        StreamStatus.replayed = 0
        StreamStatus.lastEventAtMs = 0L
        StreamStatus.lastGameMessageAtMs = 0L
        try {
            while (wanted) {
                connectedAt = 0L
                var reason = ""
                try {
                    readOnce(failures)
                } catch (e: Exception) {
                    reason = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                }
                if (!wanted) break
                val stable = connectedAt != 0L && SystemClock.elapsedRealtime() - connectedAt >= StreamPlan.STABLE_AFTER_MS
                failures = if (stable) 1 else failures + 1
                val delay = StreamPlan.backoffMs(failures - 1)
                StreamStatus.failures = failures
                StreamStatus.lastError = reason
                StreamStatus.retryAtMs = System.currentTimeMillis() + delay
                StreamStatus.state = StreamStatus.State.WAITING
                if (failures == 1 || failures % 10 == 0) {
                    Store.log(app, "Live stream lost (" + failures + "): " + reason + "; trying again in " + delay / 1000 + " s")
                }
                waitFor(delay)
            }
        } finally {
            StreamStatus.state = StreamStatus.State.OFF
            StreamStatus.failures = 0
        }
    }

    private fun waitFor(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        synchronized(pause) {
            while (wanted) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                try {
                    pause.wait(left)
                } catch (e: InterruptedException) {
                    wanted = false
                }
            }
        }
    }

    /** One connection, from the request to its end. Throws when it fails or when the server ends the stream. */
    private fun readOnce(failuresBefore: Int) {
        StreamStatus.state = StreamStatus.State.CONNECTING
        val c = URL(StreamPlan.URL).openConnection() as HttpURLConnection
        connection = c
        try {
            if (!wanted) return // closed while this connection was being made
            c.requestMethod = "GET"
            c.connectTimeout = StreamPlan.CONNECT_TIMEOUT_MS
            c.readTimeout = StreamPlan.READ_TIMEOUT_MS
            c.useCaches = false
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "text/event-stream")
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("Accept-Encoding", "identity") // an event must not wait in a compressor for the next one
            c.setRequestProperty("User-Agent", "RinksideAndroid/" + BuildConfig.VERSION_NAME)
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val type = c.contentType ?: ""
            if (!type.startsWith("text/event-stream", ignoreCase = true)) throw IOException("not an event stream ($type)")
            if (!wanted) return
            connectedAt = SystemClock.elapsedRealtime()
            StreamStatus.state = StreamStatus.State.CONNECTED
            StreamStatus.lastError = ""
            if (failuresBefore < 3) Store.log(app, "Live stream connected")
            feed.open(System.currentTimeMillis())

            val lines = SseLineReader(BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)))
            val decoder = SseDecoder()
            while (wanted) {
                val line = lines.readLine() ?: break
                val event = decoder.line(line.text, line.truncated) ?: continue
                handle(event)
            }
            if (wanted) throw IOException("the server ended the stream")
        } finally {
            connection = null
            c.disconnect()
        }
    }

    private fun handle(event: SseEvent) {
        val now = System.currentTimeMillis()
        StreamStatus.lastEventAtMs = now
        val result = try {
            feed.onEvent(event, games, now)
        } catch (e: Exception) {
            // One odd message must not end the connection. It is logged once per kind.
            Store.log(app, "Live stream: could not read a message (" + e.javaClass.simpleName + ")")
            return
        }
        when (result.outcome) {
            StreamFeed.Outcome.APPLIED -> {
                StreamStatus.applied++
                StreamStatus.lastGameMessageAtMs = now
            }
            StreamFeed.Outcome.REPLAYED -> StreamStatus.replayed++
            StreamFeed.Outcome.SCORER -> StreamStatus.lastGameMessageAtMs = now
            else -> Unit
        }
        if (result.changed || result.events.isNotEmpty() || result.scorer != null) {
            try {
                onResult(result)
            } catch (e: Exception) {
                Store.log(app, "Live stream: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
