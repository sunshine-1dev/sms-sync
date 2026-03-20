package com.sms.sync.network

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebSocketClient {
    private const val TAG = "WebSocketClient"

    private var ws: WebSocket? = null
    private var serverBase: String = ""
    private var roomId: String = ""
    private var token: String = ""

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // Separate HTTP client with reasonable timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    var onPairResult: ((success: Boolean, message: String) -> Unit)? = null

    private val handlerThread = HandlerThread("WS-Reconnect").apply { start() }
    private val reconnectHandler = Handler(handlerThread.looper)
    private var reconnectAttempt = 0
    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val BASE_RECONNECT_DELAY = 5000L

    private var appContext: Context? = null
    private var sendWakeLock: PowerManager.WakeLock? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        sendWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SMSSync::SendWakeLock"
        )
    }

    fun pairWithCode(serverBaseUrl: String, pairCode: String, deviceId: String) {
        serverBase = serverBaseUrl.trimEnd('/')
        val jsonBody = JSONObject().apply {
            put("pairCode", pairCode)
            put("deviceId", deviceId)
        }
        val request = Request.Builder()
            .url("$serverBase/api/pair")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Pair request failed: ${e.message}")
                onPairResult?.invoke(false, "网络错误: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        token = json.optString("token", "")
                        roomId = json.optString("roomId", "")
                        Log.i(TAG, "Paired! roomId=$roomId")
                        onPairResult?.invoke(true, "$token|$roomId")
                        connectWS()
                    } else {
                        onPairResult?.invoke(false, json.optString("error", "配对失败"))
                    }
                } catch (e: Exception) {
                    onPairResult?.invoke(false, "解析失败: $body")
                }
            }
        })
    }

    fun connectWithParams(serverBaseUrl: String, savedRoomId: String, savedToken: String) {
        serverBase = serverBaseUrl.trimEnd('/')
        roomId = savedRoomId
        token = savedToken
        connectWS()
    }

    private fun connectWS() {
        if (serverBase.isBlank() || roomId.isBlank() || token.isBlank()) return

        ws?.cancel()

        val wsBase = serverBase
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val wsUrl = "$wsBase/ws?roomId=$roomId&token=$token&role=android"

        val request = Request.Builder().url(wsUrl).build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to relay")
                _connected.value = true
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed: ${t.message}")
                _connected.value = false
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closed: $reason")
                _connected.value = false
            }
        })
    }

    /**
     * Send a code message. Tries WebSocket first, falls back to synchronous HTTP POST.
     * This method may block on HTTP — call from a background thread or goAsync() context.
     */
    fun sendCode(source: String, sender: String?, code: String, rawText: String, appName: String = "") {
        val msg = JSONObject().apply {
            put("type", "code")
            put("source", source)
            put("sender", sender ?: "")
            put("code", code)
            put("raw_text", rawText)
            put("app_name", appName)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("device_id", android.os.Build.MODEL)
        }

        // Always use HTTP POST — it's the only reliable path.
        // WebSocket send() can silently buffer into a dead connection,
        // so we never trust it as the sole delivery mechanism.
        Log.i(TAG, "Sending code via HTTP")
        sendViaHttpSync(msg)

        // Also try WebSocket for instant delivery if connected
        if (_connected.value) {
            ws?.send(msg.toString())
        } else {
            scheduleReconnect()
        }
    }

    /**
     * Synchronous HTTP POST fallback. Blocks until complete.
     */
    private fun sendViaHttpSync(message: JSONObject) {
        if (serverBase.isBlank() || roomId.isBlank() || token.isBlank()) {
            Log.e(TAG, "HTTP fallback: not configured")
            return
        }

        val wl = sendWakeLock
        try {
            wl?.acquire(15_000)

            val body = JSONObject().apply {
                put("roomId", roomId)
                put("token", token)
                put("message", message)
            }

            val request = Request.Builder()
                .url("$serverBase/api/send-code")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            // Synchronous call — blocks until response or timeout
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Code sent via HTTP fallback")
                } else {
                    Log.e(TAG, "HTTP fallback error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP fallback exception: ${e.message}")
        } finally {
            releaseWakeLock(wl)
        }
    }

    private fun releaseWakeLock(wl: PowerManager.WakeLock?) {
        try {
            if (wl?.isHeld == true) wl.release()
        } catch (_: Exception) {}
    }

    fun disconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempt = 0
        ws?.close(1000, "Client disconnect")
        _connected.value = false
    }

    fun isConfigured(): Boolean = roomId.isNotBlank() && token.isNotBlank()

    fun getSavedParams(): Triple<String, String, String> = Triple(serverBase, roomId, token)

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached, resetting counter")
            reconnectAttempt = 0
            return
        }

        reconnectAttempt++
        val delay = BASE_RECONNECT_DELAY * reconnectAttempt
        Log.i(TAG, "Scheduling reconnect #$reconnectAttempt in ${delay}ms")

        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (!_connected.value && roomId.isNotBlank()) {
                Log.i(TAG, "Attempting reconnect #$reconnectAttempt...")
                connectWS()
            }
        }, delay)
    }
}
