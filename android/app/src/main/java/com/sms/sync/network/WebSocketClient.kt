package com.sms.sync.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object WebSocketClient {
    private const val TAG = "WebSocketClient"

    private var ws: WebSocket? = null
    private var serverUrl: String = ""
    private var token: String = ""

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    var onPairResult: ((success: Boolean, message: String) -> Unit)? = null

    fun connect(url: String, savedToken: String = "") {
        serverUrl = url
        token = savedToken
        doConnect()
    }

    private fun doConnect() {
        if (serverUrl.isBlank()) return

        ws?.cancel()

        val request = Request.Builder()
            .url("$serverUrl?role=android")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to server")
                _connected.value = true
                if (token.isNotBlank()) {
                    val authMsg = JSONObject().apply {
                        put("type", "auth")
                        put("token", token)
                    }
                    webSocket.send(authMsg.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "pair_response" -> handlePairResponse(json)
                        "auth_response" -> handleAuthResponse(json)
                        "pong" -> Log.d(TAG, "pong")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error", e)
                }
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

    fun send(message: JSONObject): Boolean {
        return ws?.send(message.toString()) ?: false
    }

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
        if (!send(msg)) {
            Log.w(TAG, "Failed to send code, not connected")
        }
    }

    fun sendPairRequest(pairCode: String) {
        val msg = JSONObject().apply {
            put("type", "pair_request")
            put("pair_code", pairCode)
            put("device_id", android.os.Build.MODEL)
        }
        send(msg)
    }

    fun disconnect() {
        ws?.close(1000, "Client disconnect")
        _connected.value = false
    }

    private fun handlePairResponse(json: JSONObject) {
        val success = json.optBoolean("success", false)
        val newToken = json.optString("token", "")
        if (success && newToken.isNotBlank()) {
            token = newToken
        }
        onPairResult?.invoke(
            success,
            if (success) newToken else json.optString("error", "Pairing failed")
        )
    }

    private fun handleAuthResponse(json: JSONObject) {
        val success = json.optBoolean("success", false)
        if (!success) {
            Log.w(TAG, "Auth failed: ${json.optString("error")}")
            token = ""
        } else {
            Log.i(TAG, "Auth successful")
        }
    }

    private fun scheduleReconnect() {
        Thread {
            Thread.sleep(5000)
            if (!_connected.value) {
                Log.i(TAG, "Attempting reconnect...")
                doConnect()
            }
        }.start()
    }
}
