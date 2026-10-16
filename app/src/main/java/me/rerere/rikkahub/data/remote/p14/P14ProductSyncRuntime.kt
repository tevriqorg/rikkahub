package me.rerere.rikkahub.data.remote.p14

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Cold replay + live WebSocket. Push remains only a wake-up accelerator. */
internal object P14ProductSyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    @Synchronized
    fun start(context: Context, http: OkHttpClient) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            val repo = P14ProductRepository(appContext, http)
            var backoff = 500L
            while (isActive) {
                try {
                    val connection = P14RuntimeCredentialStore(appContext).activeConnection()
                    if (connection == null) {
                        delay(2_000)
                        continue
                    }
                    repo.replay(connection)
                    val cursor = repo.currentCursor()
                    collectSocket(repo, connection, cursor, http)
                    backoff = 500L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(8_000L)
                }
            }
        }
    }

    private suspend fun collectSocket(
        repo: P14ProductRepository,
        connection: P14RuntimeConnection,
        cursor: Long,
        http: OkHttpClient,
    ) {
        val frames = Channel<String>(Channel.UNLIMITED)
        val url = connection.baseUrl.toHttpUrl().newBuilder()
            .scheme(if (connection.baseUrl.startsWith("https://")) "wss" else "ws")
            .addPathSegments("sync/ws")
            .addQueryParameter("after", cursor.toString())
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${connection.bearerToken}").build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.trySend(text) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { frames.close(t) }
        })
        try {
            for (frame in frames) repo.applyLiveEvents(frame)
        } finally {
            socket.cancel()
            frames.close()
        }
    }
}
