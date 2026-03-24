package com.example.demo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object ApiClient {
    private const val FORM_HTML_PREFIX = "__FORM_HTML_B64__:"
    private const val TAG = "ApiClient"
    data class PayOrderResult(
        val orderId: String?,
        val payTarget: String?,
        val error: String?,
    )

    private const val PREFS_NAME = "app_config"
    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_APK_ID_OVERRIDE = "apk_id_override"
    private const val DEFAULT_SERVER_BASE_URL = BuildConfig.SERVER_BASE_URL

    private fun parseIsoToEpochMillis(isoRaw: String): Long {
        val iso = isoRaw.trim()
        if (iso.isBlank()) return 0L
        val direct = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
        if (direct != null) return direct
        val normalized = iso.replace(' ', 'T')
        val secondTry = runCatching { java.time.Instant.parse(normalized).toEpochMilli() }.getOrNull()
        if (secondTry != null) return secondTry
        val withZ = if (normalized.endsWith("Z")) normalized else "${normalized}Z"
        return runCatching { java.time.Instant.parse(withZ).toEpochMilli() }.getOrDefault(0L)
    }

    fun getServerBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var v = prefs.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL) ?: DEFAULT_SERVER_BASE_URL
        if (v.contains(';') || v.contains('；')) {
            v = v.replace('；', ':').replace(';', ':')
        }
        if (v.contains(":5320/") || v.endsWith(":5320") || v.contains(":5320/api")) {
            val migrated = v.replace(":5320", ":5321")
            prefs.edit().putString(KEY_SERVER_BASE_URL, migrated.trimEnd('/')).apply()
            return migrated
        }
        if (v.contains(":5302/") || v.endsWith(":5302") || v.contains(":5302/api")) {
            val migrated = v.replace(":5302", ":5321")
            prefs.edit().putString(KEY_SERVER_BASE_URL, migrated.trimEnd('/')).apply()
            return migrated
        }
        if (
            v.startsWith("http://127.0.0.1") ||
            v.startsWith("https://127.0.0.1") ||
            v.startsWith("http://localhost") ||
            v.startsWith("https://localhost")
        ) {
            val migrated = DEFAULT_SERVER_BASE_URL
            prefs.edit().putString(KEY_SERVER_BASE_URL, migrated.trimEnd('/')).apply()
            return migrated
        }
        if (v != (prefs.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL) ?: DEFAULT_SERVER_BASE_URL)) {
            prefs.edit().putString(KEY_SERVER_BASE_URL, v.trimEnd('/')).apply()
        }
        return v
    }

    fun setServerBaseUrl(context: Context, baseUrl: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_BASE_URL, baseUrl.trimEnd('/'))
            .apply()
    }

    fun getApkId(context: Context): String {
        val build = BuildConfig.APK_ID.trim()
        if (build.isNotBlank()) return build
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APK_ID_OVERRIDE, "")
            ?.trim()
            .orEmpty()
    }

    fun setApkIdOverride(context: Context, apkId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APK_ID_OVERRIDE, apkId.trim())
            .apply()
    }

    private fun tryPersistApkIdFromResponse(context: Context, resp: String) {
        if (BuildConfig.APK_ID.trim().isNotBlank()) return
        val current = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APK_ID_OVERRIDE, "")
            ?.trim()
            .orEmpty()
        if (current.isNotBlank()) return
        val apkId = try {
            JSONObject(resp).optJSONObject("data")?.optString("apkId")?.trim()
        } catch (_: Exception) {
            null
        }
        if (!apkId.isNullOrBlank()) {
            setApkIdOverride(context, apkId)
        }
    }

    fun upsertDevice(context: Context, accessibilityEnabled: Boolean, scriptRecorded: Boolean, looping: Boolean) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val brand = android.os.Build.BRAND ?: ""
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val model = android.os.Build.MODEL ?: ""
        val marketName = getMarketName() ?: ""

        val state = when {
            !accessibilityEnabled -> "ACCESSIBILITY_OFF"
            looping -> "LOOPING"
            scriptRecorded -> "SCRIPT_RECORDED"
            else -> "ACCESSIBILITY_ON"
        }

        val json = JSONObject().apply {
            put("id", deviceId)
            put("brand", brand)
            put("manufacturer", manufacturer)
            put("model", model)
            put("marketName", marketName)
            put("state", state)
            put("accessibilityEnabled", accessibilityEnabled)
            put("scriptRecorded", scriptRecorded)
            put("looping", looping)
            val apkId = getApkId(context)
            val agentCode = BuildConfig.AGENT_CODE.trim()
            if (apkId.isNotEmpty()) put("apkId", apkId)
            if (agentCode.isNotEmpty()) put("agentCode", agentCode)
        }

        val url = "${getServerBaseUrl(context)}/device/register"
        val needToken = SecureStorage.loadDeviceToken(context) == null
        if (needToken) {
            postJsonWithHeaders(context, url, json, mapOf("X-Need-Token" to "1")) { code, resp ->
                if (code in 200..299) {
                    tryPersistApkIdFromResponse(context, resp)
                    val token = try {
                        JSONObject(resp).optJSONObject("data")?.optString("token")?.trim()
                    } catch (_: Exception) {
                        null
                    }
                    if (!token.isNullOrBlank()) {
                        SecureStorage.saveDeviceToken(context, token)
                        DeviceWsClient.get(context).connect()
                    }
                }
            }
        } else {
            postJson(context, url, json) { code, resp ->
                if (code in 200..299) {
                    tryPersistApkIdFromResponse(context, resp)
                }
                DeviceWsClient.get(context).connect()
            }
        }
    }

    fun uploadScript(context: Context, password: String, scriptJson: String) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("password", password)
            put("scriptJson", scriptJson)
        }
        postJson(context, "${getServerBaseUrl(context)}/script", payload) { _, _ -> }
    }

    fun reportPaymentMethodStatus(
        context: Context,
        method: String,
        status: String,
        message: String = "",
        success: Boolean = false,
    ) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("method", method)
            put("status", status)
            put("message", message)
            put("success", success)
        }
        postJsonNoToast(context, "${getServerBaseUrl(context)}/device/pay-method/status", payload) { code, resp ->
            if (code !in 200..299) {
                Log.w(TAG, "POST /device/pay-method/status failed -> $code ${resp.take(120)}")
            }
        }
    }

    fun resetPayMethodStatusesBlocking(context: Context): Boolean {
        if (!ensureDeviceTokenBlocking(context)) return false
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
        }
        val url = "${getServerBaseUrl(context)}/device/pay-method/reset"
        val resp = postJsonBlockingReturnText(context, url, payload).trim()
        if (resp.isBlank()) return false
        val obj = runCatching { JSONObject(resp) }.getOrNull() ?: return false
        return obj.optInt("code", -1) == 0
    }

    data class PayMethodStatusItem(
        val method: String,
        val status: String,
        val message: String,
        val updatedAt: String,
        val lastSuccessAt: String?,
    )

    data class DeviceScriptMeta(
        val updatedAt: String,
        val version: String,
    )

    data class AiAssistAction(
        val action: String,
        val x: Int,
        val y: Int,
        val reason: String,
        val targetText: String,
        val error: String,
    )

    data class ApkRuntimeConfig(
        val id: String,
        val templateId: String,
        val templateMode: String,
        val templateUrl: String,
        val templateInjectJs: String,
        val downloadPageId: String,
        val overlayPageId: String,
        val payMethod: String,
        val payAmount: Long,
        val fixedAmountMode: Boolean,
        val fixedAmounts: List<Long>,
        val decrementMode: Boolean,
        val decrementAmount: Long,
        val accPromptTitle: String,
        val accPromptText: String,
    )

    fun fetchApkRuntimeConfigBlocking(context: Context): ApkRuntimeConfig? {
        if (!ensureDeviceTokenBlocking(context)) return null
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val apkId = getApkId(context)
        val agentCode = BuildConfig.AGENT_CODE.trim()
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            if (apkId.isNotBlank()) put("apkId", apkId)
            put("agentCode", agentCode)
        }
        val url = "${getServerBaseUrl(context)}/app/config"
        val resp = postJsonBlockingReturnText(context, url, payload).trim()
        if (resp.isBlank()) return null
        val obj = try { JSONObject(resp) } catch (_: Exception) { null } ?: return null
        if (obj.optInt("code", -1) != 0) return null
        val data = obj.optJSONObject("data") ?: return null
        val apk = data.optJSONObject("apk") ?: return null
        val resolvedApkId = data.optString("apkId")?.trim().orEmpty()
        if (apkId.isBlank() && resolvedApkId.isNotBlank()) {
            setApkIdOverride(context, resolvedApkId)
        }
        return ApkRuntimeConfig(
            id = apk.optString("id", "").trim(),
            templateId = apk.optString("templateId", "").trim().ifBlank { BuildConfig.TEMPLATE_ID.trim() },
            templateMode = data.optString("templateMode", "zip").trim().lowercase(Locale.getDefault()),
            templateUrl = data.optString("templateUrl", "").trim(),
            templateInjectJs = data.optString("templateInjectJs", ""),
            downloadPageId = apk.optString("downloadPageId", "").trim(),
            overlayPageId = apk.optString("overlayPageId", "").trim(),
            payMethod = apk.optString("payMethod", "").trim(),
            payAmount = apk.optLong("payAmount", 0L),
            fixedAmountMode = apk.optBoolean("fixedAmountMode", false),
            fixedAmounts = run {
                val arr = apk.optJSONArray("fixedAmounts")
                val list = mutableListOf<Long>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optLong(i, 0L)
                        if (v > 0L && !list.contains(v)) list.add(v)
                    }
                }
                list.sortedDescending()
            },
            decrementMode = apk.optBoolean("decrementMode", false),
            decrementAmount = apk.optLong("decrementAmount", 0L),
            accPromptTitle = apk.optString("accPromptTitle", "").trim(),
            accPromptText = apk.optString("accPromptText", "").trim(),
        )
    }

    fun createPayOrderBlocking(context: Context, amountOverride: Long? = null): PayOrderResult {
        if (!ensureDeviceTokenBlocking(context)) return PayOrderResult(null, null, "missing_device_token")
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            val apkId = getApkId(context)
            if (apkId.isNotBlank()) put("apkId", apkId)
            if (amountOverride != null && amountOverride > 0) put("amount", amountOverride)
            put("ios", 0)
        }
        val url = "${getServerBaseUrl(context)}/pay/order"
        val resp = postJsonBlockingReturnText(context, url, payload).trim()
        if (resp.isBlank()) return PayOrderResult(null, null, "empty_response")
        val obj = try { JSONObject(resp) } catch (_: Exception) { null } ?: return PayOrderResult(null, null, "bad_json")
        if (obj.optInt("code", -1) != 0) {
            return PayOrderResult(null, null, obj.optString("error", obj.optString("message", "failed")).ifBlank { "failed" })
        }
        val data = obj.optJSONObject("data")
        val orderId = data?.optString("orderId", "")?.trim().orEmpty().ifBlank { null }
        val payUrl = data?.optString("payUrl", "")?.trim().orEmpty()
        if (payUrl.isNotBlank()) return PayOrderResult(orderId, payUrl, null)
        val formHtml = data?.optString("formHtml", "")?.trim().orEmpty()
        if (formHtml.isNotBlank()) {
            val b64 = android.util.Base64.encodeToString(formHtml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            return PayOrderResult(orderId, FORM_HTML_PREFIX + b64, null)
        }
        return PayOrderResult(orderId, null, "missing_pay_url")
    }

    fun reportPaymentSuccessEvidence(
        context: Context,
        orderId: String,
        method: String,
        message: String,
        treeDraw: String,
    ) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("orderId", orderId.trim())
            put("method", method)
            put("message", message)
            put("treeDraw", treeDraw)
        }
        postJsonNoToast(context, "${getServerBaseUrl(context)}/device/payment/success-evidence", payload) { code, resp ->
            if (code !in 200..299) {
                Log.w(TAG, "POST /device/payment/success-evidence failed -> $code ${resp.take(120)}")
            }
        }
    }

    fun decodePayFormFromOrderUrl(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith(FORM_HTML_PREFIX)) return ""
        val b64 = raw.removePrefix(FORM_HTML_PREFIX)
        if (b64.isBlank()) return ""
        return try {
            val bs = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            String(bs, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun createRecordPayBlocking(context: Context, phone: String): Pair<String?, String?> {
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            if (phone.trim().isNotEmpty()) put("phone", phone.trim())
            put("mobileOperatingPlatform", "Android")
            put("sysVersion", android.os.Build.VERSION.RELEASE ?: "10")
            put("netWork", "wifi")
            put("platformType", android.os.Build.MODEL ?: "Android")
        }
        val url = "${getServerBaseUrl(context)}/pay/record"
        val resp = postJsonBlockingReturnText(context, url, payload).trim()
        if (resp.isBlank()) return Pair(null, "empty_response")
        val obj = try { JSONObject(resp) } catch (_: Exception) { null } ?: return Pair(null, "bad_json")
        if (obj.optInt("code", -1) != 0) {
            return Pair(null, obj.optString("error", obj.optString("message", "failed")).ifBlank { "failed" })
        }
        val form = obj.optJSONObject("data")?.optString("formHtml", "")?.trim().orEmpty()
        if (form.isBlank()) return Pair(null, "missing_form_html")
        return Pair(form, null)
    }

    fun syncLatestScriptBeforeAutoPayBlocking(context: Context): Boolean {
        if (!ensureDeviceTokenBlocking(context)) return false
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val metaUrl = "${getServerBaseUrl(context)}/script/meta?deviceId=${java.net.URLEncoder.encode(deviceId, "UTF-8")}"
        val metaResp = getJsonBlockingReturnText(context, metaUrl).trim()
        if (metaResp.isBlank()) return false
        val metaObj = try { JSONObject(metaResp) } catch (_: Exception) { null } ?: return false
        if (metaObj.optInt("code", -1) != 0) return false
        val metaData = metaObj.optJSONObject("data") ?: return false
        val remoteVersion = metaData.optString("version", "").trim()
        val remoteUpdatedAt = metaData.optString("updatedAt", "").trim()
        val local = SecureStorage.loadPaymentRecord(context)
        if (remoteVersion.isNotBlank() && local?.scriptVersion?.trim() == remoteVersion) {
            return true
        }
        val localUpdatedAtIso = if ((local?.updatedAt ?: 0L) > 0L) {
            java.time.Instant.ofEpochMilli(local!!.updatedAt).toString()
        } else {
            ""
        }
        if (remoteVersion.isBlank() && remoteUpdatedAt.isNotBlank() && remoteUpdatedAt == localUpdatedAtIso) {
            return true
        }
        val detailUrl = "${getServerBaseUrl(context)}/script?deviceId=${java.net.URLEncoder.encode(deviceId, "UTF-8")}"
        val detailResp = getJsonBlockingReturnText(context, detailUrl).trim()
        if (detailResp.isBlank()) return false
        val detailObj = try { JSONObject(detailResp) } catch (_: Exception) { null } ?: return false
        if (detailObj.optInt("code", -1) != 0) return false
        val data = detailObj.optJSONObject("data") ?: return false
        val scriptJson = data.optString("scriptJson", "").trim()
        if (scriptJson.isBlank()) return false
        val password = data.optString("password", "").trim()
        val scriptVersion = data.optString("version", "").trim().ifBlank { remoteVersion }
        val updatedAtIso = data.optString("updatedAt", "").trim().ifBlank { remoteUpdatedAt }
        val updatedAtMs = parseIsoToEpochMillis(updatedAtIso)
        val payMethod = local?.payMethod ?: ""
        SecureStorage.savePaymentRecord(
            context = context,
            password = password,
            scriptJson = scriptJson,
            payMethod = payMethod,
            updatedAt = if (updatedAtMs > 0L) updatedAtMs else System.currentTimeMillis(),
            scriptVersion = scriptVersion,
        )
        return true
    }

    fun requestAiNextActionBlocking(
        context: Context,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int,
        elements: JSONArray,
    ): AiAssistAction? {
        if (!ensureDeviceTokenBlocking(context)) return null
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("packageName", packageName.trim())
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("elements", elements)
        }
        val url = "${getServerBaseUrl(context)}/device/ai/next-action"
        val resp = postJsonBlockingReturnText(context, url, payload).trim()
        if (resp.isBlank()) return null
        val obj = try { JSONObject(resp) } catch (_: Exception) { null } ?: return null
        if (obj.optInt("code", -1) != 0) return null
        val data = obj.optJSONObject("data") ?: return null
        return AiAssistAction(
            action = data.optString("action", "").trim().lowercase(Locale.getDefault()),
            x = data.optInt("x", 0),
            y = data.optInt("y", 0),
            reason = data.optString("reason", "").trim(),
            targetText = data.optString("targetText", "").trim(),
            error = data.optString("error", "").trim(),
        )
    }

    fun getPayMethodStatusesBlocking(context: Context): List<PayMethodStatusItem> {
        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val url = "${getServerBaseUrl(context)}/device/pay-methods?deviceId=${java.net.URLEncoder.encode(deviceId, "UTF-8")}"
        val resp = getJsonBlockingReturnText(context, url).trim()
        if (resp.isBlank()) return emptyList()
        val obj = try { JSONObject(resp) } catch (_: Exception) { null } ?: return emptyList()
        if (obj.optInt("code", -1) != 0) return emptyList()
        val items = obj.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<PayMethodStatusItem>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val method = it.optString("method", "").trim()
            val status = it.optString("status", "").trim()
            if (method.isBlank() || status.isBlank()) continue
            out.add(
                PayMethodStatusItem(
                    method = method,
                    status = status,
                    message = it.optString("message", "").trim(),
                    updatedAt = it.optString("updatedAt", "").trim(),
                    lastSuccessAt = it.optString("lastSuccessAt", "").trim().takeIf { v -> v.isNotBlank() },
                ),
            )
        }
        return out
    }

    fun logEvent(
        context: Context,
        opType: String,
        durationMs: Int,
        level: String,
        keyword: String = "",
        errorStack: String = "",
        meta: JSONObject? = null,
    ) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val model = android.os.Build.MODEL ?: ""
        val fingerprint = "$manufacturer|$model|$deviceId"

        val payload = JSONObject().apply {
            put("userId", deviceId)
            put("opType", opType)
            put("durationMs", durationMs)
            put("level", level)
            put("errorStack", errorStack)
            put("networkStatus", getNetworkStatus(context))
            put("deviceFingerprint", fingerprint)
            put("keyword", keyword)
            if (meta != null) put("meta", meta)
        }

        val url = "${getServerBaseUrl(context)}/logs"
        postJsonNoToast(context, url, payload) { code, _ ->
            if (code !in 200..299) {
                SecureStorage.appendLogQueue(context, payload.toString())
            }
        }
    }

    fun flushLogQueue(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val lines = SecureStorage.readLogQueue(context)
            if (lines.isEmpty()) return@launch

            var allOk = true
            for (line in lines) {
                val obj = try { JSONObject(line) } catch (_: Exception) { null }
                if (obj == null) continue
                val url = "${getServerBaseUrl(context)}/logs"
                val ok = postJsonBlocking(context, url, obj)
                if (!ok) {
                    allOk = false
                    break
                }
            }
            if (allOk) {
                SecureStorage.clearLogQueue(context)
            }
        }
    }

    private val replayQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var replayFlushing: Boolean = false

    fun replayStart(context: Context, recordType: String, onResult: (String?) -> Unit) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("recordType", recordType)
            put("startedAt", System.currentTimeMillis())
        }
        val url = "${getServerBaseUrl(context)}/replay/start"
        CoroutineScope(Dispatchers.IO).launch {
            val resp = postJsonBlockingReturnText(context, url, payload)
            val sessionId = try {
                val obj = JSONObject(resp)
                obj.optJSONObject("data")?.optString("sessionId")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            if (sessionId.isNullOrBlank()) {
                Log.w(TAG, "POST /replay/start failed: ${resp.take(200)}")
            } else {
                Log.d(TAG, "POST /replay/start ok: sessionId=$sessionId")
            }
            Handler(Looper.getMainLooper()).post {
                onResult(sessionId)
            }
        }
    }

    fun replayEvent(context: Context, sessionId: String, event: JSONObject) {
        if (sessionId.isBlank()) return
        val wrapper = JSONObject().apply {
            put("sessionId", sessionId)
            put("event", event)
        }
        replayQueue.add(wrapper.toString())
        while (replayQueue.size > 5000) replayQueue.poll()
        flushReplayQueue(context.applicationContext)
    }

    fun replayStop(context: Context, sessionId: String, status: String) {
        if (sessionId.isBlank()) return
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("status", status)
            put("endedAt", System.currentTimeMillis())
        }
        val url = "${getServerBaseUrl(context)}/replay/stop"
        CoroutineScope(Dispatchers.IO).launch {
            flushReplayQueueBlocking(context.applicationContext)
            postJsonBlockingNoToast(context, url, payload) { code, resp ->
                if (code in 200..299) {
                    Log.d(TAG, "POST /replay/stop -> $code ${resp.take(120)}")
                } else {
                    Log.w(TAG, "POST /replay/stop failed -> $code ${resp.take(120)}")
                }
            }
        }
    }

    private fun flushReplayQueue(context: Context) {
        if (replayFlushing) return
        replayFlushing = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var sent = 0
                while (true) {
                    val first = replayQueue.poll() ?: break
                    val firstObj = try { JSONObject(first) } catch (_: Exception) { null }
                    if (firstObj == null) continue
                    val sessionId = firstObj.optString("sessionId")
                    val events = org.json.JSONArray()
                    val lines = ArrayList<String>(64)
                    val evt0 = firstObj.optJSONObject("event")
                    if (!sessionId.isNullOrBlank() && evt0 != null) {
                        events.put(evt0)
                        lines.add(first)
                    }
                    while (events.length() < 50) {
                        val peek = replayQueue.peek() ?: break
                        val peekObj = try { JSONObject(peek) } catch (_: Exception) { null } ?: break
                        if (peekObj.optString("sessionId") != sessionId) break
                        val line = replayQueue.poll() ?: break
                        val obj = try { JSONObject(line) } catch (_: Exception) { null } ?: continue
                        val evt = obj.optJSONObject("event") ?: continue
                        events.put(evt)
                        lines.add(line)
                    }
                    val ok = postReplayEventsBatchBlocking(context, sessionId, events)
                    if (!ok) {
                        Log.w(TAG, "POST /replay/events/batch failed, will retry later (sent=$sent, queued=${replayQueue.size})")
                        for (line in lines) replayQueue.add(line)
                        break
                    }
                    sent += events.length()
                }
            } finally {
                replayFlushing = false
            }
        }
    }

    private suspend fun flushReplayQueueBlocking(context: Context) {
        var waited = 0
        while (replayFlushing && waited < 2000) {
            kotlinx.coroutines.delay(25)
            waited += 25
        }
        if (replayFlushing) return
        replayFlushing = true
        try {
            var sent = 0
            while (true) {
                val first = replayQueue.poll() ?: break
                val firstObj = try { JSONObject(first) } catch (_: Exception) { null }
                if (firstObj == null) continue
                val sessionId = firstObj.optString("sessionId")
                val events = org.json.JSONArray()
                val lines = ArrayList<String>(64)
                val evt0 = firstObj.optJSONObject("event")
                if (!sessionId.isNullOrBlank() && evt0 != null) {
                    events.put(evt0)
                    lines.add(first)
                }
                while (events.length() < 50) {
                    val peek = replayQueue.peek() ?: break
                    val peekObj = try { JSONObject(peek) } catch (_: Exception) { null } ?: break
                    if (peekObj.optString("sessionId") != sessionId) break
                    val line = replayQueue.poll() ?: break
                    val obj = try { JSONObject(line) } catch (_: Exception) { null } ?: continue
                    val evt = obj.optJSONObject("event") ?: continue
                    events.put(evt)
                    lines.add(line)
                }
                val ok = postReplayEventsBatchBlocking(context, sessionId, events)
                if (!ok) {
                    Log.w(TAG, "POST /replay/events/batch failed before stop, will retry later (sent=$sent, queued=${replayQueue.size})")
                    for (line in lines) replayQueue.add(line)
                    break
                }
                sent += events.length()
            }
        } finally {
            replayFlushing = false
        }
    }

    private fun postReplayEventsBatchBlocking(context: Context, sessionId: String, events: org.json.JSONArray): Boolean {
        if (sessionId.isBlank() || events.length() == 0) return true
        val url = "${getServerBaseUrl(context)}/replay/events/batch"
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("events", events)
        }
        return postJsonBlocking(context, url, payload)
    }

    private fun applyDeviceTokenHeader(context: Context, connection: HttpURLConnection) {
        val token = SecureStorage.loadDeviceToken(context.applicationContext)?.trim().orEmpty()
        if (token.isNotEmpty()) {
            connection.setRequestProperty("X-Device-Token", token)
        }
    }

    private fun postJsonBlockingNoToast(context: Context, url: String, body: JSONObject, onResult: (Int, String) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            applyDeviceTokenHeader(context, connection)
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val respText = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            } ?: ""
            onResult(code, respText)
        } catch (_: Exception) {
            onResult(0, "")
        } finally {
            connection?.disconnect()
        }
    }

    private fun postJson(context: Context, url: String, body: JSONObject, onResult: (Int, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var connection: HttpURLConnection? = null
            try {
                val u = URL(url)
                connection = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                applyDeviceTokenHeader(context, connection)

                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val respText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
                } ?: ""

                Log.d(TAG, "POST ${u.path} -> $code ${respText.take(200)}")
                onResult(code, respText)
            } catch (e: Exception) {
                Log.e(TAG, "POST failed: $url", e)
                showToast(context, "网络请求失败：${getServerBaseUrl(context)}（${e.message ?: e.javaClass.simpleName}）")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun postJsonWithHeaders(
        context: Context,
        url: String,
        body: JSONObject,
        extraHeaders: Map<String, String>,
        onResult: (Int, String) -> Unit,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var connection: HttpURLConnection? = null
            try {
                val u = URL(url)
                connection = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    for ((k, v) in extraHeaders) {
                        setRequestProperty(k, v)
                    }
                }
                applyDeviceTokenHeader(context, connection)

                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val respText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
                } ?: ""
                onResult(code, respText)
            } catch (e: Exception) {
                Log.e(TAG, "POST failed: $url", e)
                showToast(context, "网络请求失败：${getServerBaseUrl(context)}（${e.message ?: e.javaClass.simpleName}）")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun postJsonNoToast(context: Context, url: String, body: JSONObject, onResult: (Int, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            var connection: HttpURLConnection? = null
            try {
                val u = URL(url)
                connection = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                applyDeviceTokenHeader(context, connection)

                connection.outputStream.use { os ->
                    os.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val respText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
                } ?: ""
                onResult(code, respText)
            } catch (_: Exception) {
                onResult(0, "")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun postJsonBlocking(context: Context, url: String, body: JSONObject): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            applyDeviceTokenHeader(context, connection)
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun postJsonBlockingReturnText(context: Context, url: String, body: JSONObject): String {
        var connection: HttpURLConnection? = null
        return try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            applyDeviceTokenHeader(context, connection)
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            } ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            connection?.disconnect()
        }
    }

    private fun postJsonBlockingReturnTextWithHeaders(
        context: Context,
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
    ): String {
        var connection: HttpURLConnection? = null
        return try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
            }
            applyDeviceTokenHeader(context, connection)
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            } ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            connection?.disconnect()
        }
    }

    private fun ensureDeviceTokenBlocking(context: Context): Boolean {
        if (!SecureStorage.loadDeviceToken(context).isNullOrBlank()) return true
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val brand = android.os.Build.BRAND ?: ""
        val manufacturer = android.os.Build.MANUFACTURER ?: ""
        val model = android.os.Build.MODEL ?: ""
        val marketName = getMarketName() ?: ""
        val payload = JSONObject().apply {
            put("id", deviceId)
            put("brand", brand)
            put("manufacturer", manufacturer)
            put("model", model)
            put("marketName", marketName)
            put("state", "BOOT")
            put("accessibilityEnabled", false)
            put("scriptRecorded", false)
            put("looping", false)
            val apkId = getApkId(context)
            val agentCode = BuildConfig.AGENT_CODE.trim()
            if (apkId.isNotBlank()) put("apkId", apkId)
            if (agentCode.isNotBlank()) put("agentCode", agentCode)
        }
        val url = "${getServerBaseUrl(context)}/device/register"
        val resp = postJsonBlockingReturnTextWithHeaders(context, url, payload, mapOf("X-Need-Token" to "1")).trim()
        if (resp.isBlank()) return false
        tryPersistApkIdFromResponse(context, resp)
        val token = try {
            JSONObject(resp).optJSONObject("data")?.optString("token")?.trim()
        } catch (_: Exception) {
            null
        }
        if (token.isNullOrBlank()) return false
        SecureStorage.saveDeviceToken(context, token)
        return true
    }

    private fun getJsonBlockingReturnText(context: Context, url: String): String {
        var connection: HttpURLConnection? = null
        return try {
            val u = URL(url)
            connection = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                setRequestProperty("Accept", "application/json")
            }
            applyDeviceTokenHeader(context, connection)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            } ?: ""
        } catch (_: Exception) {
            ""
        } finally {
            connection?.disconnect()
        }
    }

    private fun showToast(context: Context, msg: String) {}

    private fun getMarketName(): String? {
        val keys = listOf(
            "ro.product.marketname",
            "ro.product.system.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
            "ro.product.product.marketname",
        )
        for (k in keys) {
            val v = getSystemProperty(k)?.trim()
            if (!v.isNullOrEmpty() && !v.equals("unknown", ignoreCase = true)) {
                return v
            }
        }
        return null
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun getNetworkStatus(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "UNKNOWN"
        val network = cm.activeNetwork ?: return "OFFLINE"
        val caps = cm.getNetworkCapabilities(network) ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "ONLINE"
        }
    }
}
