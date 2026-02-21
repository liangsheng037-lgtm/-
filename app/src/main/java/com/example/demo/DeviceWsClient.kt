package com.example.demo

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DeviceWsClient private constructor(private val context: Context) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var reconnectJob: Job? = null

    private val pending = ConcurrentHashMap<Long, String>()
    private val random = SecureRandom()
    @Volatile private var nextSeq = 1L
    @Volatile private var lastAckFromServer = 0L

    fun connect() {
        if (ws != null) return
        val token = SecureStorage.loadDeviceToken(appContext) ?: return
        val deviceId =
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"

        val wsUrl = buildWsUrl(ApiClient.getServerBaseUrl(appContext), deviceId, token) ?: return
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS connected")
                reconnectJob?.cancel()
                reconnectJob = null
                sendHello()
                sendState(
                    JSONObject().apply {
                        put("accessibility", AutoPaymentService.instance != null)
                        put("looping", AutoPaymentService.loopingState)
                    },
                )
                resendPending()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                if (code == 401 || code == 403) {
                    Log.w(TAG, "WS unauthorized ($code), refreshing token")
                    ws = null
                    refreshToken()
                    return
                }
                Log.w(TAG, "WS failure: ${t.message}")
                ws = null
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS closed: $code $reason")
                ws = null
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "bye")
        ws = null
        pending.clear()
    }

    fun sendState(data: JSONObject) {
        sendEncryptedPayload(
            JSONObject().apply {
                put("type", "STATE")
                put("data", data)
            },
        )
    }

    private fun sendHello() {
        sendEncryptedPayload(
            JSONObject().apply {
                put("type", "HELLO")
                put("ack", lastAckFromServer)
            },
        )
    }

    private fun resendPending() {
        val cur = ws ?: return
        for (msg in pending.values) {
            cur.send(msg)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob != null) return
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(1500)
            ws = null
            connect()
            reconnectJob = null
        }
    }

    private fun refreshToken() {
        SecureStorage.clearDeviceToken(appContext)
        pending.clear()
        nextSeq = 1L
        lastAckFromServer = 0L
        val accessibilityEnabled = AutoPaymentService.instance != null
        val scriptRecorded = SecureStorage.loadPaymentRecord(appContext)?.password?.isNotBlank() == true
        val looping = AutoPaymentService.loopingState
        ApiClient.upsertDevice(appContext, accessibilityEnabled, scriptRecorded, looping)
    }

    private fun handleMessage(text: String) {
        val token = SecureStorage.loadDeviceToken(appContext) ?: return
        val env = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val v = env.optInt("v", 0)
        val seq = env.optLong("seq", 0L)
        val nonce = env.optString("nonce", "")
        val ct = env.optString("ct", "")
        if (v != 1 || nonce.isBlank() || ct.isBlank()) return

        val payloadText = try {
            decrypt(token, nonce, ct)
        } catch (_: Exception) {
            return
        }
        val p = try {
            JSONObject(payloadText)
        } catch (_: Exception) {
            return
        }
        val type = p.optString("type", "")
        if (type == "ACK") {
            val ack = p.optLong("ack", 0L)
            if (ack > 0) pending.remove(ack)
            return
        }

        if (seq > lastAckFromServer) {
            lastAckFromServer = seq
        }
        sendEncryptedPayload(
            JSONObject().apply {
                put("type", "ACK")
                put("ack", seq)
            },
        )
    }

    private fun sendEncryptedPayload(payload: JSONObject) {
        val cur = ws ?: return
        val token = SecureStorage.loadDeviceToken(appContext) ?: return
        val seq = nextSeq++
        val (nonceB64, ctB64) = try {
            encrypt(token, payload.toString())
        } catch (_: Exception) {
            return
        }
        val env = JSONObject().apply {
            put("v", 1)
            put("seq", seq)
            put("nonce", nonceB64)
            put("ct", ctB64)
        }.toString()
        pending[seq] = env
        cur.send(env)
    }

    private fun encrypt(token: String, plaintext: String): Pair<String, String> {
        val key = deriveKey(token)
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce, Base64.NO_WRAP) to Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(token: String, nonceB64: String, ctB64: String): String {
        val key = deriveKey(token)
        val nonce = Base64.decode(nonceB64, Base64.NO_WRAP)
        val ct = Base64.decode(ctB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val plain = cipher.doFinal(ct)
        return String(plain, Charsets.UTF_8)
    }

    private fun deriveKey(token: String): SecretKeySpec {
        val md = MessageDigest.getInstance("SHA-256")
        val sum = md.digest(token.trim().toByteArray(Charsets.UTF_8))
        return SecretKeySpec(sum, "AES")
    }

    private fun buildWsUrl(baseUrl: String, deviceId: String, token: String): String? {
        return try {
            val u = URI(baseUrl)
            val scheme = if (u.scheme.equals("https", true)) "wss" else "ws"
            val host = u.host ?: return null
            val port = u.port
            val pathPrefix = (u.path ?: "").trimEnd('/')
            val wsPath = "$pathPrefix/ws/device"
            val q =
                "deviceId=${URLEncoder.encode(deviceId, "UTF-8")}&token=${URLEncoder.encode(token, "UTF-8")}"
            URI(scheme, null, host, port, wsPath, q, null).toString()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "DeviceWsClient"

        @Volatile private var instance: DeviceWsClient? = null

        fun get(context: Context): DeviceWsClient {
            return instance ?: synchronized(this) {
                instance ?: DeviceWsClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
