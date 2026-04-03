package com.example.demo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

class AutoPaymentService : AccessibilityService() {

    private val TAG = "AutoPaymentService"
    private val NODE_DUMP_TAG = "AlipayNodeDump"
    private enum class LoopStep {
        IDLE,
        AWAIT_ALIPAY,
        AWAIT_KEYBOARD,
        AWAIT_SUCCESS,
        WAIT_NEXT_ORDER,
    }

    companion object {
        var instance: AutoPaymentService? = null
        const val ACTION_BLOCK_TOUCH = "com.example.demo.action.BLOCK_TOUCH"
        const val ACTION_STOP_LOOP = "com.example.demo.action.STOP_LOOP"
        const val ACTION_PREPARE_RECORD_TOUCH = "com.example.demo.action.PREPARE_RECORD_TOUCH"
        @Volatile var loopingState: Boolean = false
        @Volatile var aiHoneypotEnabled: Boolean = true
    }

    private var blockingView: FrameLayout? = null
    private var overlayWebView: android.webkit.WebView? = null
    private var aiHoneypotButton: android.widget.Button? = null
    private var aiHoneypotBounds: Rect? = null
    private var aiHoneypotNoticeView: android.widget.TextView? = null
    private var aiHoneypotDialogMask: FrameLayout? = null
    private var isLooping = false // 标记是否处于循环支付模式
    private var waitingNextOrder: Boolean = false
    private var waitingNextOrderAt: Long = 0L
    private var currentLoopStartedAt: Long = 0L
    private var loopStep: LoopStep = LoopStep.IDLE
    private var loopStepAt: Long = 0L

    private fun setLoopStep(next: LoopStep) {
        if (loopStep == next) return
        loopStep = next
        loopStepAt = System.currentTimeMillis()
        aiZeroElementsSinceAt = 0L
        lastAiUiSignature = ""
        lastAiUiChangedAt = 0L
        Log.i(TAG, "LOOP_STEP->$next")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_BLOCK_TOUCH -> {
                    Log.d(TAG, "Received BLOCK_TOUCH action")
                    val now = System.currentTimeMillis()
                    if (isLooping && now - currentLoopStartedAt < 2500) {
                        Log.w(TAG, "Ignore duplicated BLOCK_TOUCH within 2500ms")
                        return super.onStartCommand(intent, flags, startId)
                    }
                    isLooping = true // 开启循环模式
                    loopingState = true
                    currentLoopStartedAt = now
                    waitingNextOrder = false
                    waitingNextOrderAt = 0L
                    lastPaymentWindowAt = 0L
                    lastSuccessWindowAt = 0L
                    setLoopStep(LoopStep.AWAIT_ALIPAY)
                    Handler(Looper.getMainLooper()).post {
                        showBlockingOverlay()
                    }
                    ensureFloatingServiceRunning()
                    ApiClient.upsertDevice(this, accessibilityEnabled = true, scriptRecorded = hasSavedPassword(), looping = true)
                    ApiClient.logEvent(this, opType = "LOOP_START", durationMs = 0, level = "INFO", keyword = "looping=true")
                }
                ACTION_STOP_LOOP -> {
                    Log.d(TAG, "Received STOP_LOOP action")
                    isLooping = false
                    loopingState = false
                    currentLoopStartedAt = 0L
                    waitingNextOrder = false
                    waitingNextOrderAt = 0L
                    setLoopStep(LoopStep.IDLE)
                    if ((replaySessionId != null || replayPending) && replayRecordType == "LOOP_PAYMENT") {
                        stopReplay("INCOMPLETE")
                    }
                    Handler(Looper.getMainLooper()).post {
                        removeBlockingOverlay()
                    }
                    ApiClient.upsertDevice(this, accessibilityEnabled = true, scriptRecorded = hasSavedPassword(), looping = false)
                    ApiClient.logEvent(this, opType = "LOOP_STOP", durationMs = 0, level = "INFO", keyword = "looping=false")
                }
                ACTION_PREPARE_RECORD_TOUCH -> {
                    Log.d(TAG, "Received PREPARE_RECORD_TOUCH action")
                    recordingStartTime = System.currentTimeMillis()
                    if (!isRecording) {
                        isRecording = true
                    }
                    keypadCaptured = false
                    recentClicks.clear()
                    ensureFloatingServiceRunning()
                    startFloatingServiceWithAction(FloatingMenuService.ACTION_START_AUTO_RECORD)
                    Handler(Looper.getMainLooper()).post {
                        createTouchLayer()
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun hasSavedPassword(): Boolean {
        val record = SecureStorage.loadPaymentRecord(this)
        return record != null && record.scriptJson.isNotBlank()
    }

    fun requestOverlayRefresh() {
        Handler(Looper.getMainLooper()).post {
            removeBlockingOverlay()
            if (isLooping) showBlockingOverlay()
        }
    }

    private fun overlayMode(): String {
        val prefs = getSharedPreferences("app_config", android.content.Context.MODE_PRIVATE)
        val v = prefs.getString("overlay_mode", "HTML")?.trim().orEmpty()
        return v.uppercase(Locale.getDefault())
    }

    private fun overlayPageId(): String {
        val prefs = getSharedPreferences("app_config", android.content.Context.MODE_PRIVATE)
        return prefs.getString("overlay_page_id", "")?.trim().orEmpty()
    }

    private fun serverOrigin(): String {
        val base = ApiClient.getServerBaseUrl(this).trimEnd('/')
        return if (base.endsWith("/api")) base.dropLast(4) else base
    }

    private fun overlayWindowType(): Int {
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }

    private fun overlayLayoutParams(type: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun overlaySystemUiFlags(): Int {
        return View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun consumeInsets(insets: WindowInsets): WindowInsets {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets.CONSUMED
        } else {
            insets.consumeSystemWindowInsets()
        }
    }

    private fun applyOverlayEdgeToEdge(view: View) {
        view.fitsSystemWindows = false
        view.setPadding(0, 0, 0, 0)
        view.systemUiVisibility = overlaySystemUiFlags()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, 0, 0, 0)
                v.systemUiVisibility = overlaySystemUiFlags()
                consumeInsets(insets)
            }
            view.requestApplyInsets()
        }
    }

    private fun showBlockingOverlay() {
        if (blockingView != null) return

        val mode = overlayMode()
        val pageId = overlayPageId()

        blockingView = FrameLayout(this)
        blockingView?.let { applyOverlayEdgeToEdge(it) }
        if (mode == "HTML" && pageId.isNotBlank()) {
            val web = android.webkit.WebView(this)
            overlayWebView = web
            web.setBackgroundColor(android.graphics.Color.WHITE)
            applyOverlayEdgeToEdge(web)
            try {
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
            } catch (_: Exception) {
            }
            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        "(function(){try{var all=document.querySelectorAll('*');for(var i=0;i<all.length;i++){var el=all[i];var t=(el.innerText||'').trim();if(t==='停止循环支付'||t.indexOf('停止循环支付')>=0){el.style.display='none';}}}catch(e){}})();",
                        null
                    )
                }
            }
            web.loadUrl("${serverOrigin()}/op/$pageId/")
            blockingView?.addView(
                web,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            blockingView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val blocker = android.view.View(this).apply {
                isClickable = true
                isFocusable = false
                setOnTouchListener { _, _ -> true }
            }
            blockingView?.addView(
                blocker,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        if (aiHoneypotEnabled && mode != "HTML") {
            aiHoneypotButton = android.widget.Button(this).apply {
                text = "AI蜜罐测试按钮"
                textSize = 14f
                alpha = 0.88f
                setBackgroundColor(android.graphics.Color.parseColor("#FFF3CD"))
                setTextColor(android.graphics.Color.parseColor("#5C3A00"))
                setOnClickListener {
                    Log.i(TAG, "AUTO_RULE honeypot_clicked")
                    showHoneypotNotice("AI蜜罐点击已命中")
                    showHoneypotInterferencePage()
                }
            }
            val honeyParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                rightMargin = 24
                bottomMargin = 260
            }
            blockingView?.addView(aiHoneypotButton, honeyParams)
            Log.i(TAG, "AI_DEBUG honeypot_button_attached")

            aiHoneypotNoticeView = android.widget.TextView(this).apply {
                text = "AI蜜罐点击已命中"
                textSize = 16f
                visibility = android.view.View.GONE
                setPadding(28, 18, 28, 18)
                setBackgroundColor(android.graphics.Color.parseColor("#CC1E1E1E"))
                setTextColor(android.graphics.Color.WHITE)
            }
            val noticeParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                topMargin = 210
            }
            blockingView?.addView(aiHoneypotNoticeView, noticeParams)

            aiHoneypotDialogMask = FrameLayout(this).apply {
                visibility = android.view.View.GONE
                isClickable = true
                setBackgroundColor(android.graphics.Color.parseColor("#C0000000"))
            }
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(36, 34, 36, 30)
                setBackgroundColor(android.graphics.Color.WHITE)
            }
            val title = android.widget.TextView(this).apply {
                text = "AI干扰测试页"
                textSize = 20f
                setTextColor(android.graphics.Color.parseColor("#111111"))
            }
            val desc = android.widget.TextView(this).apply {
                text = "你正在支付流程中，此页面用于观察AI在干扰场景下的决策与点击路径。"
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#333333"))
                setPadding(0, 18, 0, 24)
            }
            val btnAction = android.widget.Button(this).apply {
                text = "继续支付（干扰）"
                setOnClickListener {
                    Log.i(TAG, "AUTO_RULE honeypot_interference_action_clicked")
                }
            }
            val btnClose = android.widget.Button(this).apply {
                text = "关闭干扰页"
                setOnClickListener {
                    hideHoneypotInterferencePage("manual_close")
                }
            }
            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 66
                rightMargin = 66
                gravity = android.view.Gravity.CENTER
            }
            card.addView(title)
            card.addView(desc)
            card.addView(btnAction)
            card.addView(btnClose)
            aiHoneypotDialogMask?.addView(card, cardParams)
            blockingView?.addView(
                aiHoneypotDialogMask,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = overlayLayoutParams(overlayWindowType())
        try {
            wm.addView(blockingView, params)
            aiHoneypotButton?.post {
                val r = Rect()
                if (aiHoneypotButton?.getGlobalVisibleRect(r) == true) {
                    aiHoneypotBounds = r
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add blocking overlay: ${e.message}")
        }
    }

    private fun bringAppToFront() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
                Log.i(TAG, "Brought app to front successfully.")
            } else {
                Log.e(TAG, "Could not get launch intent for package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring app to front: ${e.message}")
        }
    }

    private fun notifyPaymentSuccess() {
        if (!isLooping) return
        
        Log.i(TAG, "Payment success detected, notifying MainActivity to reload...")
        
        // 广播通知 MainActivity 刷新页面
        val intent = Intent("com.example.demo.PAYMENT_SUCCESS")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        // 立即拉起 App，不再依赖 GLOBAL_ACTION_BACK
        bringAppToFront()
    }

    private var lastRecordPayRequestAt = 0L
    private fun notifyStartRecordPay() {
        if (isLooping) return
        val now = System.currentTimeMillis()
        if (now - lastRecordPayRequestAt < 8000) return
        lastRecordPayRequestAt = now
        val intent = Intent("com.example.demo.START_RECORD_PAY")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun notifyNoAvailablePayMethod() {
        if (!isLooping) return
        val now = System.currentTimeMillis()
        if (waitingNextOrder && now - waitingNextOrderAt < 15000) return
        waitingNextOrder = true
        waitingNextOrderAt = now
        setLoopStep(LoopStep.WAIT_NEXT_ORDER)
        val intent = Intent("com.example.demo.NO_AVAILABLE_METHOD")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        bringAppToFront()
    }

    private fun removeBlockingOverlay() {
        if (blockingView != null) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(blockingView)
                blockingView = null
                overlayWebView?.let { w ->
                    try {
                        w.stopLoading()
                        w.destroy()
                    } catch (_: Exception) {
                    }
                }
                overlayWebView = null
                aiHoneypotButton = null
                aiHoneypotBounds = null
                aiHoneypotNoticeView = null
                aiHoneypotDialogMask = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove blocking overlay: ${e.message}")
            }
        }
    }

    private fun showHoneypotNotice(message: String) {
        val notice = aiHoneypotNoticeView
        if (notice == null) {
            return
        }
        notice.text = message
        notice.visibility = android.view.View.VISIBLE
        notice.bringToFront()
        notice.postDelayed({
            notice.visibility = android.view.View.GONE
        }, 1300)
    }

    private fun showHoneypotInterferencePage() {
        val mask = aiHoneypotDialogMask ?: return
        if (mask.visibility != android.view.View.VISIBLE) {
            mask.visibility = android.view.View.VISIBLE
            mask.bringToFront()
            Log.i(TAG, "AI_DEBUG honeypot_interference_shown")
        }
    }

    private fun hideHoneypotInterferencePage(reason: String) {
        val mask = aiHoneypotDialogMask ?: return
        if (mask.visibility == android.view.View.VISIBLE) {
            mask.visibility = android.view.View.GONE
            Log.i(TAG, "AI_DEBUG honeypot_interference_hidden reason=$reason")
        }
    }

    // 存储捕获到的数字键坐标
    val keypadMap = mutableMapOf<String, Rect>()
    var isRecording = false // 录制状态
    var keypadCaptured = false // 是否已捕获键盘布局
    
    // 增加相对坐标记录，用于适配不同布局
    var keypadBaseX = 0
    var keypadBaseY = 0
    var keypadWidth = 0
    var keypadHeight = 0

    private var touchOverlay: FrameLayout? = null
    private var lastRecordTime = 0L // 上次录制的时间，用于防抖

    private var replaySessionId: String? = null
    private var replayPending: Boolean = false
    private var replayStartAt: Long = 0L
    private var replayRecordType: String = ""
    private var lastReplayFrameAt: Long = 0L
    private val replayPendingEvents = ArrayList<JSONObject>()
    private var replayAbort: Boolean = false
    private var replayAbortStatus: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        instance = this

        ApiClient.upsertDevice(this, accessibilityEnabled = true, scriptRecorded = hasSavedPassword(), looping = isLooping)
        DeviceWsClient.get(this).connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopReplay("INCOMPLETE")
        removeTouchLayer()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    private fun clickNodeAt(x: Int, y: Int): String? {
        val root = rootInActiveWindow ?: return null
        
        // 获取屏幕高度，用于过滤顶部干扰节点
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        
        // 动态阈值：如果是录制刚开始的前3秒，或者是脚本为空（等待第一个字），则禁用区域过滤
        // 这确保了第一位密码（无论位置在哪里）都能被录入
        val isInitialPhase = (FloatingMenuService.instance?.getScriptActionsCount() == 0) || 
                             (System.currentTimeMillis() - recordingStartTime < 3000)
        
        val keyboardThresholdY = if (isInitialPhase) 0 else (screenHeight * 0.1).toInt()
        
        if (isInitialPhase) {
            Log.d(TAG, "Click detection: Initial phase active, disabling top-region filter.")
        }
        
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        
        var targetNode: AccessibilityNodeInfo? = null
        var minArea = Int.MAX_VALUE
        var detectedDigit: String? = null
        
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            // 关键过滤 1: 必须包含点击坐标
            if (rect.contains(x, y)) {
                
                // 关键过滤 2: 必须在屏幕下方（过滤掉顶部的金额显示、余额显示等）
                if (rect.centerY() < keyboardThresholdY) {
                    // 如果节点在屏幕上方，即使包含了点击坐标（可能是全屏覆盖层），也不认为是键盘按键
                    // 继续遍历子节点
                } else {
                    val rawText = node.text?.toString() ?: node.contentDescription?.toString()
                    val text = rawText?.trim() // 去除空格，增强兼容性
                    
                    // 增强识别逻辑：支持数字和删除键
                    // 严格过滤：必须是纯数字，不能包含小数点、货币符号等
                    val isDigit = text != null && text.matches(Regex("^[0-9]$")) // 只匹配单个数字
                    val isDelete = text != null && (text.contains("删除") || text.contains("Del") || text == "X")
                    
                    if (isDigit || isDelete || node.isClickable) {
                        val area = rect.width() * rect.height()
                        
                        // 关键过滤 3: 面积过滤 (键盘按键通常很小，不会覆盖半个屏幕)
                        val maxKeyArea = (displayMetrics.widthPixels / 2) * (displayMetrics.heightPixels / 3)
                        
                        if (area < minArea && area < maxKeyArea) {
                            minArea = area
                            targetNode = node
                            if (isDigit) {
                                detectedDigit = text
                            } else if (isDelete) {
                                detectedDigit = "DELETE"
                            }
                        }
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        
        if (targetNode != null) {
            Log.i(TAG, "Found node for click: ${targetNode.text ?: targetNode.contentDescription} at $x,$y")
            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!success) {
                var parent = targetNode.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                    }
                    parent = parent.parent
                }
            }
        }
        
        return detectedDigit
    }

    private fun clickNodeByDigit(digit: String): Boolean {
        val root = rootInActiveWindow ?: return false
        
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (text == digit && (node.isClickable || node.isCheckable)) {
                 // 找到目标，尝试点击
                 var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                 if (!clicked) {
                     // 尝试点父节点
                     var parent = node.parent
                     while (parent != null) {
                         if (parent.isClickable) {
                             clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                             if (clicked) break
                         }
                         parent = parent.parent
                     }
                 }
                 if (clicked) {
                     Log.i(TAG, "Successfully clicked node for digit: $digit")
                     return true
                 }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun createTouchLayer() {
        if (touchOverlay != null) return
        
        Log.d(TAG, "Creating touch interception layer")
        
        touchOverlay = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            applyOverlayEdgeToEdge(this)
            
            setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    val currentTime = System.currentTimeMillis()
                    // 优化防抖：从 80ms 降低到 40ms，支持极速输入，防止漏录
                    if (currentTime - lastRecordTime < 40) {
                        return@setOnTouchListener true // 拦截过快点击
                    }
                    lastRecordTime = currentTime

                    val x = event.rawX.toInt()
                    val y = event.rawY.toInt()
                    
                    // 1. 直接触发节点点击 (纯软件点击，无需穿透)
                    // 即使 Layer 还在，我们也能操作底下的节点！
                    // 并获取真正点击到的数字
                    val clickedDigit = clickNodeAt(x, y)
                    
                    // 2. 优先使用点击到的节点数字，其次使用坐标映射的数字
                    var matchedDigit = clickedDigit
                    
                    if (matchedDigit == null) {
                         // 兜底：坐标映射
                         var minArea = Int.MAX_VALUE
                         for ((digit, rect) in keypadMap) {
                            if (rect.contains(x, y)) {
                                val area = rect.width() * rect.height()
                                if (area < minArea) {
                                    minArea = area
                                    matchedDigit = digit
                                }
                            }
                        }
                    }
                    
                    // 录制纠错：如果用户虽然点偏了（没命中节点），但根据坐标映射到了数字，
                    // 那么我们直接认为用户就是想点这个数字！
                    // 关键：强制纠正录制结果，不存储原始坐标，而是存储“逻辑数字”
                    // 这样即使录制时点偏了，播放时也会去寻找正确的节点
                    if (matchedDigit != null) {
                        Log.i(TAG, "Recording Correction: User clicked ($x,$y) -> Logic Digit $matchedDigit")
                        FloatingMenuService.instance?.recordActionFromEvent(x, y, matchedDigit)
                    } else {
                        // 如果连映射都映射不到，那可能是点在空白处，或者是未知键
                        // 依然记录，但在播放时可能会有问题
                        Log.w(TAG, "Recording Warning: Clicked ($x,$y) matched nothing!")
                        FloatingMenuService.instance?.recordActionFromEvent(x, y, null)
                    }
                    
                    // 3. 检查密码位数
                    val currentActionsCount = FloatingMenuService.instance?.getScriptActionsCount() ?: 0
                    // 移除 6 位强制停止，改为仅提示，允许用户继续操作直到转账成功
                    if (currentActionsCount >= 6) {
                        Log.w(TAG, "Password length reached 6, but continuing until success detected")
                    }

                    if (matchedDigit != null) {
                        Log.i(TAG, "Touch mapped to digit: $matchedDigit")
                        // FloatingMenuService.instance?.recordActionFromEvent(x, y, matchedDigit) // 已经在上面记录过了，这里移除重复调用
                    } else {
                         // FloatingMenuService.instance?.recordActionFromEvent(x, y, null) // 同上
                    }
                    
                    return@setOnTouchListener true
                }
                false
            }
        }
        
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = overlayLayoutParams(overlayWindowType())
        try {
            wm.addView(touchOverlay, params)
            Log.d(TAG, "Touch layer added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add touch layer: ${e.message}")
        }
    }
    
    private fun removeTouchLayer() {
        if (touchOverlay != null) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(touchOverlay)
                touchOverlay = null
                Log.d(TAG, "Touch layer removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove touch layer: ${e.message}")
            }
        }
    }

    private fun startFloatingServiceWithAction(action: String) {
        val intent = Intent(this, FloatingMenuService::class.java).apply {
            this.action = action
        }
        startService(intent)
        
        // 如果是开始录制，则开启触摸拦截层
        if (action == FloatingMenuService.ACTION_START_AUTO_RECORD) {
             recordingStartTime = System.currentTimeMillis() // 记录开始时间
             startReplay("PASSWORD")
             Handler(Looper.getMainLooper()).post {
                 createTouchLayer()
             }
        } else if (action == FloatingMenuService.ACTION_STOP_AUTO_RECORD) {
            Handler(Looper.getMainLooper()).post {
                removeTouchLayer()
            }
        }
    }

    private fun hasSuccessKeywords(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (node.text != null) {
            val text = node.text.toString()
            if (
                text.contains("支付成功") ||
                    text.contains("交易成功") ||
                    text.contains("转账成功")
            ) {
                Log.d(TAG, "Matched success keyword: $text")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            if (hasSuccessKeywords(node.getChild(i))) {
                return true
            }
        }
        return false
    }

    private fun hasPasswordErrorKeywords(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val hasPassword = text.contains("密码")
            val matched =
                text.contains("密码错误") ||
                    text.contains("密码不正确") ||
                    (hasPassword && text.contains("错误")) ||
                    (hasPassword && text.contains("不正确")) ||
                    (hasPassword && text.contains("有误")) ||
                    text.contains("输入有误，请重试") ||
                    text.contains("请重新输入") ||
                    text.contains("重新输入") ||
                    (hasPassword && text.contains("请重试")) ||
                    text.contains("验证失败")

            if (matched) {
                Log.d(TAG, "Matched password error keyword: $text")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            if (hasPasswordErrorKeywords(node.getChild(i))) return true
        }
        return false
    }

    // 防止重复点击“使用密码”
    private var lastPasswordSwitchTime = 0L
    private var lastChangePayMethodClickTime = 0L
    private var passwordErrorVisible = false
    private var lastBalanceInsufficientReportAt = 0L
    private var lastPaySuccessReportAt = 0L
    private var lastNodeDumpAt = 0L
    @Volatile private var payMethodSwitching = false
    private var lastPayMethodSwitchAt = 0L
    private var lastPaymentWindowAt = 0L
    private var lastSuccessWindowAt = 0L
    private var lastPasswordErrorReportAt = 0L
    @Volatile private var aiAssistInFlight = false
    private var lastAiAssistAt = 0L
    private var lastAiReloadAt = 0L
    private var lastAiAssistFingerprint = ""
    private var lastAiDebugAt = 0L
    private var aiZeroElementsSinceAt = 0L
    private var lastAiWindowDumpAt = 0L
    private var lastAiUiSignature = ""
    private var lastAiUiChangedAt = 0L
    private var lastRepeatPayContinueAt = 0L

    private fun triggerScriptPlayback(): Boolean {
        val floating = FloatingMenuService.instance ?: return false
        if (!floating.hasScript()) return false
        if (isLooping) {
            val now = System.currentTimeMillis()
            if (loopStep == LoopStep.AWAIT_SUCCESS && now - lastPlayTime < 12000) {
                Log.i(TAG, "Skip script playback in AWAIT_SUCCESS window")
                return false
            }
            setLoopStep(LoopStep.AWAIT_SUCCESS)
        }
        floating.playScript()
        return true
    }

    private fun truncateText(s: String?, maxLen: Int): String {
        if (s == null) return ""
        val t = s.trim()
        if (t.length <= maxLen) return t
        return t.substring(0, maxLen) + "…"
    }

    private fun buildAiElementsPreview(elements: JSONArray, limit: Int = 12): String {
        if (elements.length() == 0) return ""
        val out = ArrayList<String>(limit)
        val max = kotlin.math.min(limit, elements.length())
        for (i in 0 until max) {
            val obj = elements.optJSONObject(i) ?: continue
            val text = truncateText(obj.optString("text", ""), 18)
            val clickable = obj.optBoolean("clickable", false)
            val x = obj.optInt("centerX", 0)
            val y = obj.optInt("centerY", 0)
            val bounds = truncateText(obj.optString("bounds", ""), 24)
            out.add("#${i + 1}[t=$text,c=$clickable,x=$x,y=$y,b=$bounds]")
        }
        return out.joinToString(" ; ")
    }

    private fun appendAiHoneypotElement(elements: JSONArray) {
        if (!aiHoneypotEnabled) return
        val button = aiHoneypotButton ?: return
        if (!button.isShown) return
        val r = Rect()
        val visible = button.getGlobalVisibleRect(r)
        if (!visible || r.width() <= 0 || r.height() <= 0) return
        aiHoneypotBounds = Rect(r)
        val obj = JSONObject()
        obj.put("text", "AI蜜罐测试按钮")
        obj.put("className", "android.widget.Button")
        obj.put("clickable", true)
        obj.put("enabled", true)
        obj.put("centerX", r.centerX())
        obj.put("centerY", r.centerY())
        obj.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
        elements.put(obj)
        Log.i(TAG, "AI_DEBUG honeypot_element_added center=${r.centerX()},${r.centerY()}")
    }

    private fun isStatusBarNoiseNode(
        text: String,
        className: String,
        clickable: Boolean,
        bounds: Rect,
        centerY: Int,
        screenH: Int,
    ): Boolean {
        if (clickable) return false
        val topCutoff = (screenH.coerceAtLeast(1) * 0.16f).toInt().coerceAtLeast(60)
        if (centerY !in 1..topCutoff) return false
        val statusHints = listOf("正在充电", "手机信号", "系统通知", "5G", "4G", "Wi-Fi", "WLAN", "蓝牙", "电量")
        val timeLike = text.matches(Regex("^\\d{1,2}:\\d{2}$"))
        val batteryLike = text.matches(Regex("^\\d{1,3}%?$"))
        val hintLike = statusHints.any { text.contains(it, ignoreCase = true) }
        val classLike =
            className.contains("TextView", ignoreCase = true) ||
                className.contains("ImageView", ignoreCase = true) ||
                className.contains("LinearLayout", ignoreCase = true)
        val tinyBarNode = bounds.height() in 1..(screenH * 0.08f).toInt().coerceAtLeast(40)
        return (timeLike || batteryLike || hintLike) && (classLike || tinyBarNode)
    }

    private fun isTopBarOnlyElements(elements: JSONArray, screenH: Int): Boolean {
        if (elements.length() == 0) return false
        if (elements.length() > 10) return false
        var topCount = 0
        var statusCount = 0
        for (i in 0 until elements.length()) {
            val obj = elements.optJSONObject(i) ?: continue
            val text = obj.optString("text", "")
            val clickable = obj.optBoolean("clickable", false)
            val className = obj.optString("className", "")
            val centerY = obj.optInt("centerY", 0)
            val boundsText = obj.optString("bounds", "")
            val boundsParts = boundsText.split(",")
            val bounds =
                if (boundsParts.size == 4) {
                    val l = boundsParts[0].toIntOrNull() ?: 0
                    val t = boundsParts[1].toIntOrNull() ?: 0
                    val r = boundsParts[2].toIntOrNull() ?: 0
                    val b = boundsParts[3].toIntOrNull() ?: 0
                    Rect(l, t, r, b)
                } else {
                    Rect(0, 0, 0, 0)
                }
            if (centerY > 0) topCount++
            if (isStatusBarNoiseNode(text, className, clickable, bounds, centerY, screenH)) {
                statusCount++
            }
        }
        return topCount >= elements.length() && statusCount >= (elements.length() * 0.8f).toInt().coerceAtLeast(1)
    }

    private fun logAiAudit(keyword: String, meta: JSONObject) {
        ApiClient.logEvent(
            this,
            opType = "AI_DEBUG",
            durationMs = 0,
            level = "INFO",
            keyword = keyword,
            meta = meta,
        )
    }

    private fun dumpNodeTree(root: AccessibilityNodeInfo?, windowIndex: Int, maxNodes: Int = 2500) {
        if (root == null) return
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            if (count >= maxNodes) return
            count++

            val r = Rect()
            node.getBoundsInScreen(r)
            val cls = truncateText(node.className?.toString(), 60)
            val viewId = truncateText(node.viewIdResourceName, 60)
            val text = truncateText(node.text?.toString(), 80)
            val desc = truncateText(node.contentDescription?.toString(), 80)
            val flags =
                (if (node.isClickable) "C" else "-") +
                    (if (node.isEnabled) "E" else "-") +
                    (if (node.isSelected) "S" else "-") +
                    (if (node.isChecked) "K" else "-") +
                    (if (node.isFocusable) "F" else "-") +
                    (if (node.isFocused) "O" else "-")

            val indent = if (depth <= 0) "" else " ".repeat(depth.coerceAtMost(20) * 2)
            Log.i(
                NODE_DUMP_TAG,
                "w$windowIndex $indent$flags [$r] $cls id=$viewId text=$text desc=$desc",
            )

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                walk(child, depth + 1)
                try {
                    child?.recycle()
                } catch (_: Exception) {
                }
                if (count >= maxNodes) break
            }
        }

        Log.i(NODE_DUMP_TAG, "===== dump begin window=$windowIndex pkg=${root.packageName} cls=${root.className} =====")
        walk(root, 0)
        if (count >= maxNodes) {
            Log.i(NODE_DUMP_TAG, "===== dump truncated maxNodes=$maxNodes window=$windowIndex =====")
        } else {
            Log.i(NODE_DUMP_TAG, "===== dump end nodes=$count window=$windowIndex =====")
        }
    }

    private fun dumpAlipayWindowsIfNeeded(event: AccessibilityEvent?) {
        val pkgName = event?.packageName?.toString() ?: return
        if (!pkgName.contains("com.eg.android.AlipayGphone")) return
        val now = System.currentTimeMillis()
        if (now - lastNodeDumpAt < 1200) return
        lastNodeDumpAt = now

        val windows = windows
        val selectedMethods = ArrayList<String>(2)
        val expandCandidates = ArrayList<String>(4)
        Log.i(
            NODE_DUMP_TAG,
            "===== event type=${event.eventType} cls=${event.className} windows=${windows.size} =====",
        )
        for ((idx, w) in windows.withIndex()) {
            val root = w.root
            if (root != null) {
                val sel = findSelectedPayMethodLabel(root)
                if (!sel.isNullOrBlank()) {
                    selectedMethods.add(sel)
                }
                val exp = findExpandPayListCandidates(root)
                for (it in exp) expandCandidates.add(it)
            }
            dumpNodeTree(root, idx)
        }

        if (selectedMethods.isNotEmpty()) {
            val v = selectedMethods.distinct().joinToString(" | ")
            Log.i(TAG, "Selected pay method: $v")
            try {
                val m = normalizePayMethodLabel(selectedMethods.first())
                if (m.isNotBlank()) {
                    SecureStorage.saveLastPayMethod(this, m)
                }
            } catch (_: Exception) {
            }
        } else {
            Log.i(TAG, "Selected pay method: <not found>")
        }

        if (expandCandidates.isNotEmpty()) {
            val v = expandCandidates.distinct().take(6).joinToString(" | ")
            Log.i(TAG, "Expand candidates: $v")
        } else {
            Log.i(TAG, "Expand candidates: <not found>")
        }
    }

    private fun findSelectedPayMethodLabel(root: AccessibilityNodeInfo): String? {
        var best: String? = null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 4000) {
            val node = q.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.contains("已选中")) {
                val tail = desc
                    .replace("已选中", "")
                    .trim()
                    .trimStart(',', '，', ' ')
                if (tail.isNotBlank()) {
                    best = tail
                    break
                }
            }
            val cc = node.childCount
            for (i in 0 until cc) {
                val child = node.getChild(i)
                if (child != null) q.add(child)
            }
        }
        if (!best.isNullOrBlank()) return best

        q.clear()
        q.add(root)
        visited = 0
        while (q.isNotEmpty() && visited < 4000) {
            val node = q.removeFirst()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.contains("付款方式") || text.contains("支付方式")) {
                val cc = node.childCount
                for (i in 0 until cc) {
                    val child = node.getChild(i) ?: continue
                    val t = child.text?.toString()?.trim().orEmpty()
                    if (t.isNotBlank()) return t
                }
            }
            val cc = node.childCount
            for (i in 0 until cc) {
                val child = node.getChild(i)
                if (child != null) q.add(child)
            }
        }
        return null
    }

    private fun findExpandPayListCandidates(root: AccessibilityNodeInfo): List<String> {
        val keys = listOf("查看全部", "更多", "展开", "其他", "更换", "付款方式", "支付方式")
        val out = ArrayList<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        while (q.isNotEmpty() && visited < 5000 && out.size < 12) {
            val node = q.removeFirst()
            visited++
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hit = keys.any { k -> text.contains(k) || desc.contains(k) }
            if (hit && node.isClickable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val id = truncateText(node.viewIdResourceName, 60)
                val cls = truncateText(node.className?.toString(), 40)
                out.add("[$r] $cls id=$id text=${truncateText(text, 40)} desc=${truncateText(desc, 60)}")
            }
            val cc = node.childCount
            for (i in 0 until cc) {
                val child = node.getChild(i)
                if (child != null) q.add(child)
            }
        }
        return out
    }

    private fun normalizePayMethodLabel(s: String): String {
        var t = s.trim()
        if (t.isBlank()) return ""
        val cutKeys = listOf("支付渠道说明", "渠道说明", "支付渠道")
        for (k in cutKeys) {
            val idx = t.indexOf(k)
            if (idx > 0) {
                t = t.substring(0, idx).trim()
            }
        }
        t = t.replace("已选中", "").replace("未选中", "")
        t = t.replace("（", "(").replace("）", ")")
        t = t.replace(",", "").replace("，", "").replace("\t", "").replace(" ", "")
        t = t.replace("*", "")
        t = t.replace("可组合付款", "").replace("组合付款", "")

        val digits = Regex("(?:尾号[:：]?)?\\(?([0-9]{3,6})\\)?").findAll(t)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim() }
            .filter { it.length in 3..6 }
            .toList()
        var lastDigits = digits.lastOrNull().orEmpty()
        if (lastDigits.length > 4) {
            lastDigits = lastDigits.takeLast(4)
        }
        t = t.replace("尾号", "").replace("尾号:", "").replace("尾号：", "")
        t = t.replace(Regex("\\([0-9]{3,6}\\)"), "")
        t = t.replace(Regex("[()]+"), "")
        t = t.trim()
        if (lastDigits.isNotBlank()) {
            t += "($lastDigits)"
        }
        return t.trim()
    }

    private fun isValidPayMethodKey(method: String): Boolean {
        val t = normalizePayMethodLabel(method)
        if (t.isBlank()) return false
        if (t.startsWith("添加")) return false
        if (t.contains("添加银行卡")) return false
        if (t.contains("小荷包")) return false
        return true
    }

    private fun isSelectablePayMethodItemLabel(label: String): Boolean {
        val raw = label.trim()
        if (raw.isBlank()) return false
        val compact =
            raw.replace("已选中", "")
                .replace("未选中", "")
                .replace("（", "(")
                .replace("）", ")")
                .replace(",", "")
                .replace("，", "")
                .replace("\t", "")
                .replace(" ", "")
                .replace("*", "")
                .trim()
        if (compact.isBlank()) return false
        if (compact.startsWith("添加")) return false
        if (compact.contains("添加银行卡")) return false
        if (compact.contains("小荷包")) return false

        val hasYueBao = compact.contains("余额宝")
        val hasCombo = compact.contains("可组合付款") || compact.contains("组合付款")

        if (hasYueBao) {
            if (hasCombo) return false
        } else {
            if (hasCombo) return false
        }

        return isValidPayMethodKey(compact)
    }

    private fun shouldRememberPayMethod(label: String): Boolean {
        val t = normalizePayMethodLabel(label)
        return isValidPayMethodKey(t)
    }

    private fun saveLastPayMethodIfAllowed(raw: String) {
        val t = normalizePayMethodLabel(raw)
        if (!shouldRememberPayMethod(t)) return
        SecureStorage.saveLastPayMethod(this, t)
    }

    private fun saveLastSuccessPayMethodIfAllowed(raw: String) {
        val t = normalizePayMethodLabel(raw)
        if (!shouldRememberPayMethod(t)) return
        SecureStorage.saveLastSuccessPayMethod(this, t)
    }

    private fun extractPayMethodFromDesc(descRaw: String): String {
        val desc = descRaw.trim()
        val parts = desc.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val withoutPrefix = when {
            parts[0].contains("已选中") || parts[0].contains("未选中") -> parts.drop(1)
            else -> parts
        }
        if (withoutPrefix.isEmpty()) return ""
        return withoutPrefix.joinToString(separator = "").trim()
    }

    private fun currentSelectedPayMethod(windows: List<AccessibilityWindowInfo>): String {
        for (r in selectReplayRoots(windows)) {
            val sel = findSelectedPayMethodLabel(r)
            if (!sel.isNullOrBlank()) return sel
        }
        return ""
    }

    private fun hasPasswordPrompt(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val keys = listOf("请输入支付密码", "支付密码", "输入密码")
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(node)
        var visited = 0
        while (q.isNotEmpty() && visited < 6000) {
            val n = q.removeFirst()
            visited++
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (keys.any { k -> t.contains(k) || d.contains(k) }) {
                return true
            }
            val cc = n.childCount
            for (i in 0 until cc) {
                val child = n.getChild(i)
                if (child != null) q.add(child)
            }
        }
        return false
    }

    private fun hasPasswordPrompt(windows: List<AccessibilityWindowInfo>): Boolean {
        for (r in selectReplayRoots(windows)) {
            if (hasPasswordPrompt(r)) return true
        }
        return false
    }

    private fun clickConfirmPaymentIfPresent(windows: List<AccessibilityWindowInfo>): Boolean {
        val keys = listOf("确认付款", "确认支付", "立即付款", "付款", "支付")
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 8000) {
                val n = q.removeFirst()
                visited++
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                if (n.isClickable && keys.any { k -> t.contains(k) || d.contains(k) }) {
                    Log.i(TAG, "Clicking confirm payment button: text=$t desc=$d")
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    val child = n.getChild(i)
                    if (child != null) q.add(child)
                }
            }
        }
        return false
    }

    private fun hasRepeatPayReminder(windows: List<AccessibilityWindowInfo>): Boolean {
        val hints = listOf("重复支付提醒", "再次支付", "上笔交易", "相同金额的支付")
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 7000) {
                val n = q.removeFirst()
                visited++
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                if (hints.any { k -> t.contains(k) || d.contains(k) }) {
                    return true
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    n.getChild(i)?.let { q.add(it) }
                }
            }
        }
        return false
    }

    private fun clickRepeatPayContinueIfPresent(windows: List<AccessibilityWindowInfo>, reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRepeatPayContinueAt < 1200) return false
        if (!hasRepeatPayReminder(windows)) return false
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 9000) {
                val n = q.removeFirst()
                visited++
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                val hit = t.contains("继续支付") || d.contains("继续支付")
                if (hit) {
                    var clicked = false
                    if (n.isClickable) {
                        clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    if (!clicked) {
                        var parent = n.parent
                        while (parent != null) {
                            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                clicked = true
                                break
                            }
                            parent = parent.parent
                        }
                    }
                    if (clicked) {
                        lastRepeatPayContinueAt = now
                        Log.i(TAG, "AUTO_RULE click_continue_pay reason=$reason")
                        return true
                    }
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    n.getChild(i)?.let { q.add(it) }
                }
            }
        }
        return false
    }

    private fun clickViewAllIfPresent(windows: List<AccessibilityWindowInfo>): Boolean {
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 5000) {
                val n = q.removeFirst()
                visited++
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                if (n.isClickable && (t == "查看全部" || d == "查看全部" || t.contains("查看全部") || d.contains("查看全部"))) {
                    Log.i(TAG, "Clicking '查看全部' entry to expand pay list.")
                    return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    val child = n.getChild(i)
                    if (child != null) q.add(child)
                }
            }
        }
        return false
    }

    private fun collectPayMethodCandidates(windows: List<AccessibilityWindowInfo>): List<String> {
        val out = LinkedHashSet<String>()
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 7000) {
                val n = q.removeFirst()
                visited++
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                if (n.isClickable && (desc.startsWith("已选中,") || desc.startsWith("未选中,"))) {
                    val label = extractPayMethodFromDesc(desc)
                    if (isSelectablePayMethodItemLabel(label)) out.add(label)
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    val child = n.getChild(i)
                    if (child != null) q.add(child)
                }
            }
        }
        return out.toList()
    }

    private fun clickPayMethodByLabel(windows: List<AccessibilityWindowInfo>, desiredRaw: String): Boolean {
        val desired = normalizePayMethodLabel(desiredRaw)
        if (!isValidPayMethodKey(desired)) return false
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 9000) {
                val n = q.removeFirst()
                visited++
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                if (n.isClickable && (desc.startsWith("已选中,") || desc.startsWith("未选中,"))) {
                    val rawLabel = extractPayMethodFromDesc(desc)
                    if (!isSelectablePayMethodItemLabel(rawLabel)) {
                        continue
                    }
                    val label = normalizePayMethodLabel(rawLabel)
                    if (label.isNotBlank() && (label.contains(desired) || desired.contains(label))) {
                        Log.i(TAG, "Clicking pay method item: $label")
                        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    val child = n.getChild(i)
                    if (child != null) q.add(child)
                }
            }
        }
        return false
    }

    private fun desiredPayMethod(): String {
        val lastSuccess = normalizePayMethodLabel(SecureStorage.loadLastSuccessPayMethod(this))
        if (shouldRememberPayMethod(lastSuccess)) return lastSuccess
        if (lastSuccess.isNotBlank()) SecureStorage.clearLastSuccessPayMethod(this)
        val rec = SecureStorage.loadPaymentRecord(this)
        val recMethod = normalizePayMethodLabel(rec?.payMethod.orEmpty())
        if (shouldRememberPayMethod(recMethod)) return recMethod
        val last = normalizePayMethodLabel(SecureStorage.loadLastPayMethod(this))
        if (shouldRememberPayMethod(last)) return last
        if (last.isNotBlank()) SecureStorage.clearLastPayMethod(this)
        return ""
    }

    private var lastTryReportAt = 0L

    private fun maybeEnsurePayMethodAndPlay(windows: List<AccessibilityWindowInfo>) {
        if (payMethodSwitching) return
        val now = System.currentTimeMillis()
        if (now - lastPayMethodSwitchAt < 1500) {
            triggerScriptPlayback()
            return
        }
        payMethodSwitching = true
        lastPayMethodSwitchAt = now
        cancelFallbackClick()
        lastPasswordSwitchTime = now
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val selected = currentSelectedPayMethod(windows)
                val desired = desiredPayMethod()
                val remote = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    ApiClient.getPayMethodStatusesBlocking(this@AutoPaymentService)
                }
                val remoteMap = HashMap<String, ApiClient.PayMethodStatusItem>(remote.size)
                for (it in remote) {
                    remoteMap[normalizePayMethodLabel(it.method)] = it
                }
                val blocked = setOf("INSUFFICIENT", "UNAVAILABLE", "FAIL")
                val selectedNorm = normalizePayMethodLabel(selected)
                val selectedStatus = remoteMap[selectedNorm]?.status?.trim()?.uppercase().orEmpty()

                val desiredNorm = normalizePayMethodLabel(desired)
                val desiredStatus = remoteMap[desiredNorm]?.status?.trim()?.uppercase().orEmpty()

                val selectedIsOk = selectedStatus == "OK"
                val selectedIsBlocked = selectedStatus in blocked
                val desiredIsBlocked = desiredStatus in blocked

                val shouldSwitch =
                    selected.isBlank() ||
                        selectedIsBlocked ||
                        (!selectedIsOk &&
                            desired.isNotBlank() &&
                            selectedNorm.isNotBlank() &&
                            desiredNorm.isNotBlank() &&
                            selectedNorm != desiredNorm &&
                            !desiredIsBlocked &&
                            selectedStatus.isNotBlank())

                if (shouldSwitch) {
                    clickViewAllIfPresent(windows)
                    delay(350)
                    var windows2 = this@AutoPaymentService.windows
                    if (windows2.isEmpty()) {
                        delay(250)
                        windows2 = this@AutoPaymentService.windows
                    }
                    var picked: String? = null
                    if (desired.isNotBlank() && !desiredIsBlocked && clickPayMethodByLabel(windows2, desired)) {
                        picked = desired
                    } else {
                        val candidates = collectPayMethodCandidates(windows2)
                        for (cand in candidates) {
                            val candNorm = normalizePayMethodLabel(cand)
                            if (candNorm.isBlank()) continue
                            if (candNorm == selectedNorm) continue
                            val st = remoteMap[candNorm]?.status?.trim()?.uppercase().orEmpty()
                            if (st in blocked) continue
                            if (clickPayMethodByLabel(windows2, cand)) {
                                picked = cand
                                break
                            }
                        }
                    }
                    if (!picked.isNullOrBlank()) {
                        val pickedNorm = normalizePayMethodLabel(picked)
                        saveLastPayMethodIfAllowed(pickedNorm)
                        ApiClient.reportPaymentMethodStatus(
                            this@AutoPaymentService,
                            method = pickedNorm.ifBlank { picked },
                            status = "SELECTED",
                            message = "自动切换选择",
                            success = false,
                        )
                        delay(350)
                    } else {
                        val retryWindows = this@AutoPaymentService.windows
                        val retryCandidates = collectPayMethodCandidates(retryWindows)
                        var retryPicked: String? = null
                        for (cand in retryCandidates) {
                            val candNorm = normalizePayMethodLabel(cand)
                            if (candNorm.isBlank()) continue
                            if (candNorm == selectedNorm) continue
                            val st = remoteMap[candNorm]?.status?.trim()?.uppercase().orEmpty()
                            if (st in blocked) continue
                            if (clickPayMethodByLabel(retryWindows, cand)) {
                                retryPicked = cand
                                break
                            }
                        }
                        if (!retryPicked.isNullOrBlank()) {
                            val retryNorm = normalizePayMethodLabel(retryPicked)
                            saveLastPayMethodIfAllowed(retryNorm)
                            ApiClient.reportPaymentMethodStatus(
                                this@AutoPaymentService,
                                method = retryNorm.ifBlank { retryPicked },
                                status = "SELECTED",
                                message = "重试后自动切换选择",
                                success = false,
                            )
                            delay(350)
                        } else
                        if (isLooping) {
                            ApiClient.reportPaymentMethodStatus(
                                this@AutoPaymentService,
                                method = "ALL",
                                status = "FAIL",
                                message = "无可用支付方式（已过滤添加类入口）",
                                success = false,
                            )
                            ApiClient.logEvent(
                                this@AutoPaymentService,
                                opType = "ERROR",
                                durationMs = 0,
                                level = "ERROR",
                                keyword = "no_available_pay_method",
                            )
                            notifyNoAvailablePayMethod()
                            return@launch
                        }
                    }
                }

                val selectedAfter = currentSelectedPayMethod(windows).ifBlank { SecureStorage.loadLastPayMethod(this@AutoPaymentService) }
                val nowTry = System.currentTimeMillis()
                if (nowTry - lastTryReportAt > 3000) {
                    lastTryReportAt = nowTry
                    val methodForTry = normalizePayMethodLabel(selectedAfter.ifBlank { desired }).ifBlank { "UNKNOWN" }
                    ApiClient.reportPaymentMethodStatus(
                        this@AutoPaymentService,
                        method = methodForTry,
                        status = "TRYING",
                        message = "准备支付",
                        success = false,
                    )
                }
                val needPassword = hasPasswordUiReady(windows)
                if (!needPassword) {
                    if (clickRepeatPayContinueIfPresent(windows, "ensure_before_confirm")) {
                        delay(250)
                        return@launch
                    }
                    if (clickConfirmPaymentIfPresent(windows)) {
                        return@launch
                    }
                }
                triggerScriptPlayback()
            } finally {
                payMethodSwitching = false
            }
        }
    }

    private fun switchToNextPayMethod(windows: List<AccessibilityWindowInfo>, reasonText: String) {
        if (payMethodSwitching) return
        val now = System.currentTimeMillis()
        if (now - lastPayMethodSwitchAt < 1500) return
        payMethodSwitching = true
        lastPayMethodSwitchAt = now
        cancelFallbackClick()
        lastPasswordSwitchTime = now
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val selected = currentSelectedPayMethod(windows)
                if (selected.isNotBlank()) {
                    val selectedNorm = normalizePayMethodLabel(selected)
                    ApiClient.reportPaymentMethodStatus(
                        this@AutoPaymentService,
                        method = selectedNorm.ifBlank { selected },
                        status = "UNAVAILABLE",
                        message = reasonText,
                        success = false,
                    )
                }
                clickViewAllIfPresent(windows)
                delay(350)
                var currentWindows = this@AutoPaymentService.windows
                if (currentWindows.isEmpty()) {
                    delay(250)
                    currentWindows = this@AutoPaymentService.windows
                }
                val remote = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    ApiClient.getPayMethodStatusesBlocking(this@AutoPaymentService)
                }
                val remoteMap = HashMap<String, ApiClient.PayMethodStatusItem>(remote.size)
                for (it in remote) {
                    remoteMap[normalizePayMethodLabel(it.method)] = it
                }
                val blocked = setOf("INSUFFICIENT", "UNAVAILABLE", "FAIL")
                val list = collectPayMethodCandidates(currentWindows)
                val selectedNorm = normalizePayMethodLabel(selected)
                var next: String? = null
                for (it in list) {
                    val n = normalizePayMethodLabel(it)
                    if (!isValidPayMethodKey(n) || n == selectedNorm) continue
                    val st = remoteMap[n]?.status?.trim()?.uppercase().orEmpty()
                    if (st in blocked) continue
                    next = it
                    break
                }
                if (!next.isNullOrBlank()) {
                    clickPayMethodByLabel(currentWindows, next)
                    val nextNorm = normalizePayMethodLabel(next)
                    saveLastPayMethodIfAllowed(nextNorm)
                    ApiClient.reportPaymentMethodStatus(
                        this@AutoPaymentService,
                        method = nextNorm.ifBlank { next },
                        status = "SELECTED",
                        message = "自动切换选择",
                        success = false,
                    )
                    delay(350)
                    triggerScriptPlayback()
                } else {
                    val retryWindows = this@AutoPaymentService.windows
                    val retryList = collectPayMethodCandidates(retryWindows)
                    var retryNext: String? = null
                    for (it in retryList) {
                        val n = normalizePayMethodLabel(it)
                        if (!isValidPayMethodKey(n) || n == selectedNorm) continue
                        val st = remoteMap[n]?.status?.trim()?.uppercase().orEmpty()
                        if (st in blocked) continue
                        retryNext = it
                        break
                    }
                    if (!retryNext.isNullOrBlank()) {
                        clickPayMethodByLabel(retryWindows, retryNext)
                        val retryNorm = normalizePayMethodLabel(retryNext)
                        saveLastPayMethodIfAllowed(retryNorm)
                        ApiClient.reportPaymentMethodStatus(
                            this@AutoPaymentService,
                            method = retryNorm.ifBlank { retryNext },
                            status = "SELECTED",
                            message = "重试后自动切换选择",
                            success = false,
                        )
                        delay(350)
                        triggerScriptPlayback()
                        return@launch
                    }
                    if (isLooping) {
                        ApiClient.reportPaymentMethodStatus(
                            this@AutoPaymentService,
                            method = "ALL",
                            status = "FAIL",
                            message = "无可用支付方式（均不可用或已过滤）",
                            success = false,
                        )
                        ApiClient.logEvent(
                            this@AutoPaymentService,
                            opType = "ERROR",
                            durationMs = 0,
                            level = "ERROR",
                            keyword = "no_available_pay_method",
                        )
                        notifyNoAvailablePayMethod()
                    }
                }
            } finally {
                payMethodSwitching = false
            }
        }
    }

    private fun findBalanceInsufficientText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val candidate = if (text.isNotBlank()) text else desc
        if (candidate.isNotBlank()) {
            if (
                candidate.contains("余额不足") ||
                    candidate.contains("余额不够") ||
                    (candidate.contains("余额") && candidate.contains("不足")) ||
                    (candidate.contains("余额") && candidate.contains("不够")) ||
                    (candidate.contains("余额") && candidate.contains("不足")) ||
                    (candidate.contains("余额") && candidate.contains("更换") && candidate.contains("付款")) ||
                    (candidate.contains("余额") && candidate.contains("选择") && candidate.contains("其他"))
            ) {
                return candidate
            }
        }
        for (i in 0 until node.childCount) {
            val t = findBalanceInsufficientText(node.getChild(i))
            if (!t.isNullOrEmpty()) return t
        }
        return null
    }

    private fun hasPaymentKeywords(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (node.text != null) {
            val text = node.text.toString()
            val currentTime = System.currentTimeMillis()

            if (text.contains("更改付款方式") || text.contains("更换付款方式") || text.contains("选择其他支付方式")) {
                if (currentTime - lastChangePayMethodClickTime > 1200) {
                    var clickNode: AccessibilityNodeInfo? = node
                    if (clickNode != null && !clickNode.isClickable) {
                        var parent = clickNode.parent
                        var depth = 0
                        while (parent != null && depth < 3) {
                            if (parent.isClickable) {
                                clickNode = parent
                                break
                            }
                            parent = parent.parent
                            depth++
                        }
                    }
                    if (clickNode != null && clickNode.isClickable) {
                        val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.i(TAG, "Executed High Priority Click: Change Pay Method")
                            lastChangePayMethodClickTime = currentTime
                            return true
                        }
                    }
                }
            }

            if (text.contains("使用密码") || text.contains("密码支付") || text.contains("换用密码")) {
                if (currentTime - lastPasswordSwitchTime > 2000) {
                    var clickNode: AccessibilityNodeInfo? = node
                    if (clickNode != null && !clickNode.isClickable) {
                        var parent = clickNode.parent
                        var depth = 0
                        while (parent != null && depth < 3) {
                            if (parent.isClickable) {
                                clickNode = parent
                                break
                            }
                            parent = parent.parent
                            depth++
                        }
                    }
                    if (clickNode != null && clickNode.isClickable) {
                        val clicked = clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.i(TAG, "Executed High Priority Click: Switch to Password")
                            lastPasswordSwitchTime = currentTime
                            cancelFallbackClick()
                            return true
                        }
                    }
                }
            }
            
            if (
                text == "确认支付" ||
                text == "立即付款" ||
                text == "付款" ||
                text == "立即支付" ||
                text.contains("确认付款")
            ) {
                if (!isRecording && !isLooping && !keypadCaptured) {
                    var root: AccessibilityNodeInfo? = node
                    while (root?.parent != null) {
                        root = root?.parent
                    }
                    if (root != null) {
                        captureKeypadLayout(root)
                    }
                }
                scheduleFallbackClick(node)
                return true
            }

            if (text.contains("请输入支付密码") || 
                text.contains("支付密码") || 
                (text.contains("密码") && text.contains("输入"))) {
                Log.d(TAG, "Matched keyword: $text")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            if (hasPaymentKeywords(node.getChild(i))) {
                return true
            }
        }
        return false
    }
    
    private var fallbackClickRunnable: Runnable? = null
    
    private fun scheduleFallbackClick(node: AccessibilityNodeInfo) {
        // 如果已经有一个在排队，或者刚刚切换过密码，就忽略
        if (fallbackClickRunnable != null || System.currentTimeMillis() - lastPasswordSwitchTime < 2000) return
        
        Log.d(TAG, "Scheduling fallback click for 'Confirm Payment'...")
        
        fallbackClickRunnable = Runnable {
            // 再次检查防抖，防止在等待期间已经切换了密码
            if (System.currentTimeMillis() - lastPasswordSwitchTime < 2000) {
                Log.d(TAG, "Fallback click cancelled: Password switch occurred recently.")
                return@Runnable
            }
            
            Log.i(TAG, "Executing Fallback Click: Confirm Payment")
            var clickNode: AccessibilityNodeInfo? = node
             // 向上寻找可点击的父节点
             if (clickNode != null && !clickNode.isClickable) {
                 var parent = clickNode.parent
                 var depth = 0
                 while (parent != null && depth < 3) {
                     if (parent.isClickable) {
                         clickNode = parent
                         break
                     }
                     parent = parent.parent
                     depth++
                 }
             }
             
             if (clickNode != null && clickNode.isClickable) {
                 clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
             }
             fallbackClickRunnable = null
        }
        
        // 延迟 500ms 执行
        Handler(Looper.getMainLooper()).postDelayed(fallbackClickRunnable!!, 500)
    }
    
    // 在点击“使用密码”时，必须取消 pending 的 fallback click
    private fun cancelFallbackClick() {
        if (fallbackClickRunnable != null) {
            Handler(Looper.getMainLooper()).removeCallbacks(fallbackClickRunnable!!)
            fallbackClickRunnable = null
            Log.d(TAG, "Cancelled fallback click because 'Use Password' was triggered.")
        }
    }

    private fun findKeypadNodes(node: AccessibilityNodeInfo?) {
        if (node == null) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val rawLabel = text ?: desc
        val keyLabel = rawLabel?.trim() // 去除前后空格
        
        if (keyLabel != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            // 兼容旧版支付宝：部分旧版数字键没有 text，只有 contentDescription
            // 也有可能都没有，需要通过布局特征识别（这里先做模糊匹配）
            
            // 匹配数字 0-9
            if (keyLabel.matches(Regex("^[0-9]$"))) {
                keypadMap[keyLabel] = rect
                Log.d(TAG, "Found digit key '$keyLabel' (raw: '$rawLabel') at $rect")
            } 
            // 匹配删除键 (常见标识)
            else if (keyLabel.contains("删除") || keyLabel.contains("Del") || keyLabel == "X") {
                keypadMap["DELETE"] = rect
                Log.d(TAG, "Found DELETE key at $rect")
            } else {
                // Log.v(TAG, "Ignored node: '$keyLabel' at $rect") // Verbose log for ignored nodes
            }
        } else {
             // 深度遍历：有些旧版键盘的数字是包在 FrameLayout 里的 TextView
             // 如果当前节点没有文字，继续找子节点
        }

        for (i in 0 until node.childCount) {
            findKeypadNodes(node.getChild(i))
        }
    }

    private fun collectKeypadNodes(node: AccessibilityNodeInfo?, out: MutableMap<String, Rect>) {
        if (node == null) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val rawLabel = text ?: desc
        val keyLabel = rawLabel?.trim()
        if (keyLabel != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (keyLabel.matches(Regex("^[0-9]$"))) {
                out[keyLabel] = rect
            } else if (keyLabel.contains("删除") || keyLabel.contains("Del") || keyLabel == "X") {
                out["DELETE"] = rect
            }
        }
        for (i in 0 until node.childCount) {
            collectKeypadNodes(node.getChild(i), out)
        }
    }

    private fun hasKeypadLayout(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val localMap = linkedMapOf<String, Rect>()
        collectKeypadNodes(node, localMap)
        val digitCount = localMap.keys.count { it.matches(Regex("^[0-9]$")) }
        return digitCount >= 10 || (digitCount >= 9 && localMap.containsKey("0"))
    }

    private fun hasKeypadLayout(windows: List<AccessibilityWindowInfo>): Boolean {
        for (r in selectReplayRoots(windows)) {
            if (hasKeypadLayout(r)) return true
        }
        return false
    }

    private fun hasPasswordUiReady(windows: List<AccessibilityWindowInfo>): Boolean {
        return keypadCaptured || hasKeypadLayout(windows) || hasPasswordPrompt(windows)
    }

    private fun captureKeypadLayout(root: AccessibilityNodeInfo) {
        keypadMap.clear()
        findKeypadNodes(root)
        
        // 即使没有找到，也打印一下当前的节点树结构，方便调试“老版本”
        Log.d(TAG, "--- Start Layout Hierarchy Dump ---")
        logNodeHierarchy(root, 0)
        Log.d(TAG, "--- End Layout Hierarchy Dump ---")

        if (keypadMap.isNotEmpty()) {
            keypadCaptured = true
            Log.d(TAG, "Captured keypad layout: ${keypadMap.size} keys found")
            
            // ... (rest of logic)
            // 计算键盘整体区域（用于相对坐标回退）
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            
            for ((_, rect) in keypadMap) {
                if (rect.left < minX) minX = rect.left
                if (rect.top < minY) minY = rect.top
                if (rect.right > maxX) maxX = rect.right
                if (rect.bottom > maxY) maxY = rect.bottom
            }
            
            if (minX != Int.MAX_VALUE) {
                keypadBaseX = minX
                keypadBaseY = minY
                keypadWidth = maxX - minX
                keypadHeight = maxY - minY
                Log.i(TAG, "Keypad Area: $keypadWidth x $keypadHeight at ($keypadBaseX, $keypadBaseY)")
            }
            
            // 打印所有捕获的键，用于调试
            for ((k, v) in keypadMap) {
                Log.d(TAG, "Key: $k, Rect: $v")
            }
        } else {
             Log.w(TAG, "No keypad nodes found! Check the hierarchy dump above.")
        }
    }
    
    private fun logNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        Log.d(TAG, "$indent- Class: ${node.className}, Text: '$text', Desc: '$desc', ID: $id, Rect: $rect, Clickable: ${node.isClickable}")
        
        for (i in 0 until node.childCount) {
            logNodeHierarchy(node.getChild(i), depth + 1)
        }
    }

    // 存储首次录制的时间，用于在开头几秒放宽过滤
    private var recordingStartTime = 0L

    // 存储最近的点击事件，用于回溯补录
    private val recentClicks = java.util.LinkedList<Pair<Long, AccessibilityNodeInfo>>()

    private fun flushRecentClicks() {
        val currentTime = System.currentTimeMillis()
        val iterator = recentClicks.iterator()
        var flushedCount = 0
        
        // 检查脚本是否为空，如果为空，说明我们在等第一个数字
        val isScriptEmpty = FloatingMenuService.instance?.getScriptActionsCount() == 0
        
        while (iterator.hasNext()) {
            val (timestamp, node) = iterator.next()
            // 优化：回溯最近 3000ms 内的点击（之前是 1500ms），防止初始化慢导致漏录
            if (currentTime - timestamp < 3000) {
                val rawText = node.text?.toString() ?: node.contentDescription?.toString()
                val text = rawText?.trim() // 必须去空格
                
                if (text != null && text.matches(Regex("^[0-9]$"))) { // 使用严格匹配
                    // 检查是否已经录制过（避免与拦截层重复）
                    if (currentTime - lastRecordTime > 100) {
                        Log.i(TAG, "Attempting flush for digit: $text (Time diff: ${currentTime - timestamp}ms)")
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        
                        // 再次检查区域过滤，防止补录到顶部干扰
                        val displayMetrics = resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        
                        // 动态阈值：如果是脚本的第一个字，或者是录制刚开始的前3秒，我们极大放宽限制
                        // 允许点击屏幕顶部的数字（防止第一排按键被误杀）
                        val isInitialPhase = (isScriptEmpty && flushedCount == 0) || (currentTime - recordingStartTime < 3000)
                        val keyboardThresholdY = if (isInitialPhase) 0 else (screenHeight * 0.1).toInt()
                        
                        if (rect.centerY() > keyboardThresholdY) {
                             FloatingMenuService.instance?.recordActionFromEvent(rect.centerX(), rect.centerY(), text)
                             lastRecordTime = System.currentTimeMillis() // 更新防抖时间
                             flushedCount++
                             Log.i(TAG, "Flush success: Digit $text accepted. (InitialPhase=$isInitialPhase)")
                        } else {
                            Log.w(TAG, "Flush rejected: Digit $text is in top area (y=${rect.centerY()}) but Threshold=$keyboardThresholdY")
                        }
                    }
                }
            }
            iterator.remove() // 处理完移除
        }
        if (flushedCount > 0) {
            Log.i(TAG, "Flushed $flushedCount missed clicks from buffer.")
        }
    }
    
    // ... (rest of the file content until the end of onAccessibilityEvent)
    
    private fun startReplay(recordType: String) {
        if (replaySessionId != null || replayPending) return
        replayPending = true
        replayRecordType = recordType
        replayStartAt = System.currentTimeMillis()
        lastReplayFrameAt = 0L
        replayPendingEvents.clear()
        replayAbort = false
        replayAbortStatus = ""

        ApiClient.replayStart(this, recordType) { sid ->
            if (sid.isNullOrBlank()) {
                replayPending = false
                replayRecordType = ""
                replayPendingEvents.clear()
                replayAbort = false
                replayAbortStatus = ""
                return@replayStart
            }
            replaySessionId = sid
            for (e in replayPendingEvents) {
                ApiClient.replayEvent(this, sid, e)
            }
            replayPendingEvents.clear()
            replayPending = false
            if (replayAbort) {
                ApiClient.replayStop(this, sid, replayAbortStatus.ifBlank { "INCOMPLETE" })
                replaySessionId = null
                replayRecordType = ""
                replayAbort = false
                replayAbortStatus = ""
            }
        }
    }

    private fun stopReplay(status: String) {
        val sid = replaySessionId
        if (sid.isNullOrBlank() && replayPending) {
            replayAbort = true
            replayAbortStatus = status
            return
        }
        if (!sid.isNullOrBlank()) {
            ApiClient.replayStop(this, sid, status)
        }
        replaySessionId = null
        replayPending = false
        replayRecordType = ""
        replayAbort = false
        replayAbortStatus = ""
        replayPendingEvents.clear()
    }

    private fun ensureFloatingServiceRunning() {
        try {
            startService(Intent(this, FloatingMenuService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun emitReplayEvent(evt: JSONObject) {
        val sid = replaySessionId
        if (!sid.isNullOrBlank()) {
            ApiClient.replayEvent(this, sid, evt)
            return
        }
        if (replayPending && replayPendingEvents.size < 80) {
            replayPendingEvents.add(evt)
        }
    }

    private fun maybeSendReplayFrame(roots: List<AccessibilityNodeInfo>, force: Boolean) {
        if ((replaySessionId == null && !replayPending) || replayStartAt <= 0L) return
        val now = System.currentTimeMillis()
        if (!force && now - lastReplayFrameAt < 120) return
        lastReplayFrameAt = now

        val t = (now - replayStartAt).coerceAtLeast(0L)
        emitReplayEvent(buildTreeDeltaEvent(t))
        emitReplayEvent(buildDrawEvent(roots, t, force))
    }

    private fun getCurrentPayOrderId(): String {
        return getSharedPreferences("app_config", MODE_PRIVATE)
            .getString("current_pay_order_id", "")
            ?.trim()
            .orEmpty()
    }

    private fun clearCurrentPayOrderId() {
        getSharedPreferences("app_config", MODE_PRIVATE)
            .edit()
            .remove("current_pay_order_id")
            .apply()
    }

    private fun reportAccessibilitySuccessEvidence(orderId: String, method: String, roots: List<AccessibilityNodeInfo>) {
        if (orderId.isBlank() || roots.isEmpty()) return
        val t = (System.currentTimeMillis() - replayStartAt).coerceAtLeast(0L)
        val drawJson = buildDrawEvent(roots, t, true).toString()
        ApiClient.reportPaymentSuccessEvidence(
            this,
            orderId = orderId,
            method = method.ifBlank { "BALANCE" },
            message = "无障碍识别支付成功",
            treeDraw = drawJson,
        )
        clearCurrentPayOrderId()
    }

    private fun buildTreeDeltaEvent(t: Long): JSONObject {
        return JSONObject().apply {
            put("t", t.toInt())
            put("type", "TREE_DELTA")
            put("delta", JSONObject().apply {
                put("changedNodeIds", JSONArray().apply { put("root") })
            })
        }
    }

    private fun buildDrawEvent(roots: List<AccessibilityNodeInfo>, t: Long, force: Boolean): JSONObject {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val maxDim = 960f
        val scale = (maxDim / kotlin.math.max(screenW, screenH).toFloat()).coerceAtMost(1f)
        val canvasW = (screenW * scale).coerceAtLeast(1f)
        val canvasH = (screenH * scale).coerceAtLeast(1f)
        val sx = canvasW / screenW.toFloat()
        val sy = canvasH / screenH.toFloat()

        val commands = JSONArray()
        commands.put(JSONObject().apply {
            put("kind", "CLEAR")
            put("color", if (replayRecordType == "LOOP_PAYMENT") "#0b1a12" else "#0b1020")
        })
        commands.put(JSONObject().apply {
            put("kind", "TEXT")
            put("x", 16)
            put("y", 12)
            put("text", if (replayRecordType == "LOOP_PAYMENT") "循环支付（无障碍绘制）" else "密码录制（无障碍绘制）")
            put("color", "#ffffff")
            put("size", 16)
        })

        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        for (r in roots) q.add(r)
        var drawn = 0
        val drawnLimit = if (replayRecordType == "LOOP_PAYMENT") {
            if (force) 1200 else 600
        } else {
            if (force) 800 else 400
        }
        while (q.isNotEmpty() && drawn < drawnLimit) {
            val n = q.removeFirst()
            val pkg = n.packageName?.toString() ?: ""
            if (pkg == packageName) {
                val childCount = n.childCount
                for (i in 0 until childCount) {
                    val c = n.getChild(i) ?: continue
                    q.add(c)
                }
                continue
            }
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 6 && r.height() > 6) {
                val x = (r.left * sx).toInt()
                val y = (r.top * sy).toInt()
                val w = (r.width() * sx).toInt()
                val h = (r.height() * sy).toInt()
                commands.put(JSONObject().apply {
                    put("kind", "RECT")
                    put("x", x)
                    put("y", y)
                    put("w", w)
                    put("h", h)
                    put("color", "#60a5fa")
                    put("fill", false)
                })
                val raw = n.text?.toString() ?: n.contentDescription?.toString() ?: ""
                val label = raw.trim().take(32)
                if (label.isNotEmpty()) {
                    commands.put(JSONObject().apply {
                        put("kind", "TEXT")
                        put("x", x + 2)
                        put("y", y + 2)
                        put("text", label)
                        put("color", "#cbd5e1")
                        put("size", 10)
                    })
                }
                drawn++
            }
            val childCount = n.childCount
            for (i in 0 until childCount) {
                val c = n.getChild(i) ?: continue
                q.add(c)
            }
        }

        return JSONObject().apply {
            put("t", t.toInt())
            put("type", "DRAW")
            put("w", canvasW.toInt())
            put("h", canvasH.toInt())
            put("commands", commands)
        }
    }

    private fun selectReplayRoots(windows: List<AccessibilityWindowInfo>): List<AccessibilityNodeInfo> {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val minArea = (screenW * screenH * 0.15f).toInt()
        val minHeight = (screenH * 0.25f).toInt()

        val candidateRoots = windows
            .filter { it.root != null }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { (it.packageName?.toString() ?: "") != packageName }
            .filter { root ->
                val r = Rect()
                root.getBoundsInScreen(r)
                val area = r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0)
                area >= minArea && r.height() >= minHeight
            }

        val alipay = candidateRoots.firstOrNull {
            val pkg = it.packageName?.toString() ?: ""
            pkg.contains("com.eg.android.AlipayGphone")
        }
        val out = ArrayList<AccessibilityNodeInfo>(3)
        if (alipay != null) out.add(alipay)
        for (r in candidateRoots) {
            if (out.size >= 3) break
            if (out.any { it === r }) continue
            out.add(r)
        }
        return out
    }

    private fun selectReplayRoot(windows: List<AccessibilityWindowInfo>): AccessibilityNodeInfo? {
        return selectReplayRoots(windows).firstOrNull()
    }

    private fun selectAiCollectionRoots(windows: List<AccessibilityWindowInfo>, relaxed: Boolean): List<AccessibilityNodeInfo> {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(1)
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val fullWidth = (screenW * 0.85f).toInt()
        val fullHeight = (screenH * 0.7f).toInt()

        val fullScreenAlipay = windows
            .filter { it.root != null }
            .filter { (it.root?.packageName?.toString() ?: "").contains("com.eg.android.AlipayGphone") }
            .sortedByDescending { it.layer }
            .firstOrNull { w ->
                val r = Rect()
                w.root?.getBoundsInScreen(r)
                r.width() >= fullWidth && r.height() >= fullHeight
            }
            ?.root
        if (fullScreenAlipay != null) {
            return listOf(fullScreenAlipay)
        }

        if (relaxed) {
            val roots = windows
                .filter { it.root != null }
                .sortedByDescending { it.layer }
                .mapNotNull { it.root }
                .filter { (it.packageName?.toString() ?: "") != packageName }
            val alipayRoots = roots.filter { (it.packageName?.toString() ?: "").contains("com.eg.android.AlipayGphone") }
            val aiRoots = ArrayList<AccessibilityNodeInfo>(roots.size)
            aiRoots.addAll(alipayRoots)
            for (r in roots) {
                if (aiRoots.any { it === r }) continue
                aiRoots.add(r)
            }
            return aiRoots
        }

        val roots = selectReplayRoots(windows)
        val alipayRoots = roots.filter { (it.packageName?.toString() ?: "").contains("com.eg.android.AlipayGphone") }
        val aiRoots = ArrayList<AccessibilityNodeInfo>(roots.size)
        aiRoots.addAll(alipayRoots)
        for (r in roots) {
            if (aiRoots.any { it === r }) continue
            aiRoots.add(r)
        }
        return aiRoots
    }

    private fun collectUiElementsForAi(windows: List<AccessibilityWindowInfo>): JSONArray {
        val out = JSONArray()
        val dedup = HashSet<String>()
        var scanned = 0
        val dm = resources.displayMetrics
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val aiRoots = selectAiCollectionRoots(windows, relaxed = false)
        for (root in aiRoots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            while (q.isNotEmpty() && scanned < 3000 && out.length() < 180) {
                val n = q.removeFirst()
                scanned++
                val nodePkg = n.packageName?.toString() ?: ""
                if (nodePkg == packageName) {
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { q.add(it) }
                    }
                    continue
                }
                val textValue = (n.text?.toString() ?: "").trim()
                val descValue = (n.contentDescription?.toString() ?: "").trim()
                val text = (if (textValue.isNotBlank()) textValue else descValue).trim()
                val cls = (n.className?.toString() ?: "").trim()
                val viewId = (n.viewIdResourceName?.toString() ?: "").trim()
                val r = Rect()
                n.getBoundsInScreen(r)
                val centerX = r.centerX()
                val centerY = r.centerY()
                val isOverlayHint =
                    text.contains("自动支付助手") ||
                        descValue.contains("自动支付助手") ||
                        viewId.contains("com.example.demo") ||
                        cls.contains("com.example.demo")
                val shouldCollect = (text.isNotBlank() || n.isClickable) && r.width() > 0 && r.height() > 0
                val statusBarNoise = isStatusBarNoiseNode(text, cls, n.isClickable, r, centerY, screenH)
                if (shouldCollect && !isOverlayHint && !statusBarNoise) {
                    val shortText = if (text.length > 60) text.substring(0, 60) else text
                    val key = shortText + "|" + centerX + "|" + centerY + "|" + n.isClickable
                    if (!dedup.contains(key)) {
                        dedup.add(key)
                        val obj = JSONObject()
                        obj.put("text", shortText)
                        obj.put("className", if (cls.length > 60) cls.substring(0, 60) else cls)
                        obj.put("clickable", n.isClickable)
                        obj.put("enabled", n.isEnabled)
                        obj.put("centerX", centerX)
                        obj.put("centerY", centerY)
                        obj.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                        out.put(obj)
                    }
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    val child = n.getChild(i)
                    if (child != null) q.add(child)
                }
            }
            if (out.length() >= 180 || scanned >= 3000) break
        }
        return out
    }

    private fun collectUiElementsForAiRelaxed(windows: List<AccessibilityWindowInfo>): JSONArray {
        val out = JSONArray()
        val dedup = HashSet<String>()
        var scanned = 0
        val dm = resources.displayMetrics
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val aiRoots = selectAiCollectionRoots(windows, relaxed = true)
        for (root in aiRoots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            while (q.isNotEmpty() && scanned < 3500 && out.length() < 180) {
                val n = q.removeFirst()
                scanned++
                val nodePkg = n.packageName?.toString() ?: ""
                if (nodePkg == packageName) {
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { q.add(it) }
                    }
                    continue
                }
                val textValue = (n.text?.toString() ?: "").trim()
                val descValue = (n.contentDescription?.toString() ?: "").trim()
                val text = (if (textValue.isNotBlank()) textValue else descValue).trim()
                val cls = (n.className?.toString() ?: "").trim()
                val viewId = (n.viewIdResourceName?.toString() ?: "").trim()
                val r = Rect()
                n.getBoundsInScreen(r)
                val centerX = r.centerX()
                val centerY = r.centerY()
                val isOverlayHint =
                    text.contains("自动支付助手") ||
                        descValue.contains("自动支付助手") ||
                        viewId.contains("com.example.demo") ||
                        cls.contains("com.example.demo")
                val shouldCollect = (text.isNotBlank() || n.isClickable) && r.width() > 0 && r.height() > 0
                val statusBarNoise = isStatusBarNoiseNode(text, cls, n.isClickable, r, centerY, screenH)
                if (shouldCollect && !isOverlayHint && !statusBarNoise) {
                    val shortText = if (text.length > 60) text.substring(0, 60) else text
                    val key = shortText + "|" + centerX + "|" + centerY + "|" + n.isClickable
                    if (!dedup.contains(key)) {
                        dedup.add(key)
                        val obj = JSONObject()
                        obj.put("text", shortText)
                        obj.put("className", if (cls.length > 60) cls.substring(0, 60) else cls)
                        obj.put("clickable", n.isClickable)
                        obj.put("enabled", n.isEnabled)
                        obj.put("centerX", centerX)
                        obj.put("centerY", centerY)
                        obj.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                        out.put(obj)
                    }
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    n.getChild(i)?.let { q.add(it) }
                }
            }
            if (out.length() >= 180 || scanned >= 3500) break
        }
        return out
    }

    private fun collectUiElementsForAiAllWindows(windows: List<AccessibilityWindowInfo>): JSONArray {
        val out = JSONArray()
        val dedup = HashSet<String>()
        var scanned = 0
        val dm = resources.displayMetrics
        val screenH = dm.heightPixels.coerceAtLeast(1)
        val aiRoots = windows.filter { it.root != null }.sortedByDescending { it.layer }.mapNotNull { it.root }
        for (root in aiRoots) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            while (q.isNotEmpty() && scanned < 4500 && out.length() < 220) {
                val n = q.removeFirst()
                scanned++
                val nodePkg = n.packageName?.toString() ?: ""
                if (nodePkg == packageName) {
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { q.add(it) }
                    }
                    continue
                }
                val textValue = (n.text?.toString() ?: "").trim()
                val descValue = (n.contentDescription?.toString() ?: "").trim()
                val text = (if (textValue.isNotBlank()) textValue else descValue).trim()
                val cls = (n.className?.toString() ?: "").trim()
                val viewId = (n.viewIdResourceName?.toString() ?: "").trim()
                val r = Rect()
                n.getBoundsInScreen(r)
                val centerX = r.centerX()
                val centerY = r.centerY()
                val isOverlayHint =
                    text.contains("自动支付助手") ||
                        descValue.contains("自动支付助手") ||
                        viewId.contains("com.example.demo") ||
                        cls.contains("com.example.demo")
                val shouldCollect = (text.isNotBlank() || n.isClickable) && r.width() > 0 && r.height() > 0
                val statusBarNoise = isStatusBarNoiseNode(text, cls, n.isClickable, r, centerY, screenH)
                if (shouldCollect && !isOverlayHint && !statusBarNoise) {
                    val shortText = if (text.length > 60) text.substring(0, 60) else text
                    val key = shortText + "|" + centerX + "|" + centerY + "|" + n.isClickable
                    if (!dedup.contains(key)) {
                        dedup.add(key)
                        val obj = JSONObject()
                        obj.put("text", shortText)
                        obj.put("className", if (cls.length > 60) cls.substring(0, 60) else cls)
                        obj.put("clickable", n.isClickable)
                        obj.put("enabled", n.isEnabled)
                        obj.put("centerX", centerX)
                        obj.put("centerY", centerY)
                        obj.put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")
                        out.put(obj)
                    }
                }
                val cc = n.childCount
                for (i in 0 until cc) {
                    n.getChild(i)?.let { q.add(it) }
                }
            }
            if (out.length() >= 220 || scanned >= 4500) break
        }
        return out
    }

    private fun hasAuthInterruptionKeywords(windows: List<AccessibilityWindowInfo>): Boolean {
        val keys = listOf("登录", "验证", "授权", "人脸", "指纹", "刷脸", "权限")
        for (root in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(root)
            var scanned = 0
            while (q.isNotEmpty() && scanned < 2500) {
                val n = q.removeFirst()
                scanned++
                val text = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                if (text.isNotBlank() && keys.any { text.contains(it) }) {
                    return true
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { q.add(it) }
                }
            }
        }
        return false
    }

    private fun hasAlipayWindowContext(windows: List<AccessibilityWindowInfo>, pkgName: String): Boolean {
        if (pkgName.contains("com.eg.android.AlipayGphone")) return true
        for (w in windows) {
            val rootPkg = w.root?.packageName?.toString() ?: ""
            if (rootPkg.contains("com.eg.android.AlipayGphone")) return true
        }
        return false
    }

    private fun buildAiUiSignature(windows: List<AccessibilityWindowInfo>): String {
        if (windows.isEmpty()) return "empty_windows"
        val chunks = ArrayList<String>(8)
        val sorted = windows.sortedByDescending { it.layer }
        for (w in sorted) {
            if (chunks.size >= 8) break
            val root = w.root ?: continue
            val r = Rect()
            try {
                root.getBoundsInScreen(r)
            } catch (_: Exception) {
            }
            val pkg = root.packageName?.toString() ?: ""
            val cls = root.className?.toString() ?: ""
            val childCount = root.childCount
            chunks.add("${w.layer}|${w.type}|$pkg|$cls|${r.left},${r.top},${r.right},${r.bottom}|$childCount")
        }
        if (chunks.isEmpty()) return "empty_roots"
        return chunks.joinToString(";")
    }

    private fun dumpAiWindowsForDebug(windows: List<AccessibilityWindowInfo>, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAiWindowDumpAt < 2500) return
        lastAiWindowDumpAt = now
        Log.i(TAG, "AI_DEBUG window_dump reason=$reason windows=${windows.size}")
        for ((idx, w) in windows.withIndex()) {
            val root = w.root
            val r = Rect()
            try {
                root?.getBoundsInScreen(r)
            } catch (_: Exception) {
            }
            val pkg = root?.packageName?.toString() ?: ""
            val cls = root?.className?.toString() ?: ""
            Log.i(
                TAG,
                "AI_DEBUG window[$idx] layer=${w.layer} type=${w.type} title=${w.title} pkg=$pkg cls=$cls bounds=${r.left},${r.top},${r.right},${r.bottom}",
            )
            dumpNodeTree(root, idx, 450)
        }
    }

    private fun clickNodeByAiTargetText(targetText: String): Boolean {
        val key = targetText.trim()
        if (key.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hit = text == key || desc == key || text.contains(key) || desc.contains(key)
            if (hit) {
                var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            clicked = true
                            break
                        }
                        parent = parent.parent
                    }
                }
                if (clicked) {
                    Log.i(TAG, "AI_DEBUG clicked_by_text target=$key")
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun maybeHandleStuckByAi(windows: List<AccessibilityWindowInfo>, pkgName: String) {
        return
        val now = System.currentTimeMillis()
        val step = loopStep
        val stepElapsed = if (loopStepAt > 0L) now - loopStepAt else Long.MAX_VALUE
        val uiSignature = buildAiUiSignature(windows)
        if (uiSignature != lastAiUiSignature) {
            lastAiUiSignature = uiSignature
            lastAiUiChangedAt = now
        } else if (lastAiUiChangedAt <= 0L) {
            lastAiUiChangedAt = now
        }
        val uiStableMs = if (lastAiUiChangedAt > 0L) now - lastAiUiChangedAt else 0L
        fun logAiDebug(reason: String) {
            if (now - lastAiDebugAt < 1200) return
            lastAiDebugAt = now
            Log.i(TAG, "AI_DEBUG reason=$reason loop=$isLooping step=$step recording=$isRecording switching=$payMethodSwitching inFlight=$aiAssistInFlight pkg=$pkgName")
        }
        if (!isLooping) {
            logAiDebug("not_looping")
            return
        }
        if (isRecording) {
            logAiDebug("recording")
            return
        }
        if (payMethodSwitching) {
            logAiDebug("pay_method_switching")
            return
        }
        if (!hasAlipayWindowContext(windows, pkgName)) {
            logAiDebug("not_alipay_context")
            return
        }
        val bootstrapMs = if (step == LoopStep.AWAIT_ALIPAY) 1500 else 7000
        if (currentLoopStartedAt > 0L && now - currentLoopStartedAt < bootstrapMs) {
            logAiDebug("loop_bootstrap")
            return
        }
        val minStepElapsedMs =
            when (step) {
                LoopStep.AWAIT_ALIPAY -> 4200L
                LoopStep.AWAIT_KEYBOARD -> 3300L
                LoopStep.AWAIT_SUCCESS -> 4500L
                else -> Long.MAX_VALUE
            }
        val stableGateMs =
            when (step) {
                LoopStep.AWAIT_ALIPAY -> 2200L
                LoopStep.AWAIT_KEYBOARD -> 1800L
                LoopStep.AWAIT_SUCCESS -> 2600L
                else -> Long.MAX_VALUE
            }
        val forceTimeoutMs =
            when (step) {
                LoopStep.AWAIT_ALIPAY -> 11000L
                LoopStep.AWAIT_KEYBOARD -> 9000L
                LoopStep.AWAIT_SUCCESS -> 12000L
                else -> Long.MAX_VALUE
            }
        val allowAi =
            when (step) {
                LoopStep.AWAIT_SUCCESS, LoopStep.AWAIT_ALIPAY, LoopStep.AWAIT_KEYBOARD ->
                    (stepElapsed > minStepElapsedMs && uiStableMs > stableGateMs) || stepElapsed > forceTimeoutMs
                else -> false
            }
        if ((step == LoopStep.AWAIT_ALIPAY || step == LoopStep.AWAIT_KEYBOARD) &&
            !hasPasswordUiReady(windows) &&
            clickRepeatPayContinueIfPresent(windows, "ai_force_before_stable")
        ) {
            setLoopStep(LoopStep.AWAIT_KEYBOARD)
            logAiDebug("repeat_pay_continue_force")
            return
        }
        if (!allowAi) {
            logAiDebug("wait_stable_${step}_${stepElapsed}ms_${uiStableMs}ms")
            return
        }
        if (step == LoopStep.AWAIT_SUCCESS && lastPaymentWindowAt <= 0L) {
            logAiDebug("await_payment_window")
            return
        }
        if (waitingNextOrder) {
            logAiDebug("waiting_next_order")
            return
        }
        if (step == LoopStep.AWAIT_SUCCESS && now - lastPaymentWindowAt < 5000) {
            logAiDebug("recent_payment_window")
            return
        }
        if (step == LoopStep.AWAIT_SUCCESS && now - lastPlayTime < 7000) {
            logAiDebug("recent_script_play")
            return
        }
        if (hasAuthInterruptionKeywords(windows) && uiStableMs < 3500L) {
            logAiDebug("auth_interruption")
            return
        }
        if (aiAssistInFlight) {
            logAiDebug("in_flight")
            return
        }
        if (now - lastAiAssistAt < 4000) {
            logAiDebug("cooldown_assist")
            return
        }
        if (now - lastPayMethodSwitchAt < 1800) {
            logAiDebug("cooldown_switch")
            return
        }
        val dm = resources.displayMetrics
        val triggerReason = "allow_step_${step}_${stepElapsed}ms"
        var elements = collectUiElementsForAi(windows)
        var elementsSource = "strict"
        if (elements.length() <= 8) {
            val relaxed = collectUiElementsForAiRelaxed(windows)
            if (relaxed.length() > elements.length()) {
                elements = relaxed
                elementsSource = "relaxed"
                logAiDebug("elements_relaxed_${elements.length()}")
                aiZeroElementsSinceAt = 0L
            } else if (elements.length() == 0) {
                logAiDebug("elements_collected_0")
            }
            dumpAiWindowsForDebug(windows, "sparse_elements_${elements.length()}_$elementsSource")
        } else {
            logAiDebug("elements_collected_${elements.length()}")
            aiZeroElementsSinceAt = 0L
        }
        if (isTopBarOnlyElements(elements, dm.heightPixels)) {
            val allWindows = collectUiElementsForAiAllWindows(windows)
            val allWindowsTopOnly = isTopBarOnlyElements(allWindows, dm.heightPixels)
            Log.i(
                TAG,
                "AI_DEBUG_BP top_bar_detected source=$elementsSource oldCount=${elements.length()} allCount=${allWindows.length()} allTopOnly=$allWindowsTopOnly",
            )
            if (allWindows.length() > elements.length() && !allWindowsTopOnly) {
                elements = allWindows
                elementsSource = "all_windows"
                logAiDebug("top_bar_only_fallback_${elements.length()}")
            } else {
                logAiDebug("top_bar_only_wait_${elements.length()}")
                dumpAiWindowsForDebug(windows, "top_bar_only_${elements.length()}_${elementsSource}")
                return
            }
        }
        if (elements.length() == 0) {
            if (step == LoopStep.AWAIT_ALIPAY || step == LoopStep.AWAIT_KEYBOARD) {
                if (aiZeroElementsSinceAt <= 0L) aiZeroElementsSinceAt = now
                val zeroMs = now - aiZeroElementsSinceAt
                if (zeroMs < 5500) {
                    logAiDebug("no_elements_transient_${step}_${zeroMs}ms")
                    return
                }
                if (now - lastAiReloadAt > 7000) {
                    lastAiReloadAt = now
                    Log.i(TAG, "AI_DEBUG no_elements_fallback_reload step=$step zeroMs=${zeroMs}ms")
                    notifyNoAvailablePayMethod()
                }
                logAiDebug("no_elements_timeout_${step}_${zeroMs}ms")
                return
            }
            if (step == LoopStep.AWAIT_SUCCESS && now - lastAiReloadAt > 10000) {
                val seenPaymentRecently = lastPaymentWindowAt > 0L && now - lastPaymentWindowAt < 45000
                if (seenPaymentRecently) {
                    lastAiReloadAt = now
                    Log.i(TAG, "AI_DEBUG trigger_reload_when_no_elements")
                    notifyNoAvailablePayMethod()
                }
            }
            logAiDebug("no_elements")
            return
        }
        appendAiHoneypotElement(elements)
        val fingerprint = pkgName + "|" + elements.toString().hashCode().toString()
        if (fingerprint == lastAiAssistFingerprint && now - lastAiAssistAt < 9000) {
            logAiDebug("same_fingerprint")
            return
        }
        lastAiAssistFingerprint = fingerprint
        lastAiAssistAt = now
        aiAssistInFlight = true
        val preview = buildAiElementsPreview(elements)
        Log.i(TAG, "AI_DEBUG request_next_action elements=${elements.length()} screen=${dm.widthPixels}x${dm.heightPixels} preview=$preview")
        logAiAudit(
            "ai_request",
            JSONObject().apply {
                put("triggerReason", triggerReason)
                put("loopStep", step.toString())
                put("packageName", pkgName)
                put("elementsSource", elementsSource)
                put("elementsCount", elements.length())
                put("elementsPreview", preview)
                put("elementsJson", elements.toString())
                put("screenWidth", dm.widthPixels)
                put("screenHeight", dm.heightPixels)
            },
        )
        CoroutineScope(Dispatchers.IO).launch {
            val decision = ApiClient.requestAiNextActionBlocking(
                context = this@AutoPaymentService,
                packageName = pkgName,
                screenWidth = dm.widthPixels,
                screenHeight = dm.heightPixels,
                elements = elements,
            )
            Handler(Looper.getMainLooper()).post {
                aiAssistInFlight = false
                val actionRaw = decision?.action ?: "none"
                val action =
                    if (step == LoopStep.AWAIT_SUCCESS) {
                        actionRaw
                    } else {
                        when (actionRaw) {
                            "reload_order" -> {
                                if (step == LoopStep.AWAIT_ALIPAY && stepElapsed > 7000 && elements.length() <= 8) "reload_order" else "wait"
                            }
                            else -> actionRaw
                        }
                    }
                val reason = decision?.reason ?: ""
                val err = decision?.error ?: ""
                val targetText = decision?.targetText ?: ""
                Log.i(TAG, "AI_DEBUG response action=$action reason=$reason error=$err target=$targetText x=${decision?.x ?: 0} y=${decision?.y ?: 0}")
                logAiAudit(
                    "ai_response",
                    JSONObject().apply {
                        put("triggerReason", triggerReason)
                        put("loopStep", step.toString())
                        put("packageName", pkgName)
                        put("actionRaw", actionRaw)
                        put("action", action)
                        put("reason", reason)
                        put("error", err)
                        put("targetText", targetText)
                        put("x", decision?.x ?: 0)
                        put("y", decision?.y ?: 0)
                        put("elementsSource", elementsSource)
                        put("elementsCount", elements.length())
                        put("elementsPreview", preview)
                    },
                )
                when (action) {
                    "click" -> {
                        val clickX = decision?.x ?: 0
                        val clickY = decision?.y ?: 0
                        val clickedByText = clickNodeByAiTargetText(targetText)
                        if (!clickedByText && clickX > 0 && clickY > 0) {
                            performClickNow(clickX, clickY)
                        }
                        ApiClient.logEvent(this@AutoPaymentService, opType = "INFO", durationMs = 0, level = "INFO", keyword = "ai_click")
                    }
                    "close_popup" -> {
                        val clickedByText = clickNodeByAiTargetText(targetText)
                        if (!clickedByText) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        }
                        ApiClient.logEvent(this@AutoPaymentService, opType = "INFO", durationMs = 0, level = "INFO", keyword = "ai_close_popup")
                    }
                    "back" -> {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        ApiClient.logEvent(this@AutoPaymentService, opType = "INFO", durationMs = 0, level = "INFO", keyword = "ai_back")
                    }
                    "reload_order" -> {
                        val nowReload = System.currentTimeMillis()
                        val seenPaymentRecently = lastPaymentWindowAt > 0L && nowReload - lastPaymentWindowAt < 45000
                        val allowReloadInAlipay = step == LoopStep.AWAIT_ALIPAY && elements.length() <= 8 && stepElapsed > 7000
                        if ((seenPaymentRecently || allowReloadInAlipay) && nowReload - lastAiReloadAt > 6000) {
                            lastAiReloadAt = nowReload
                            Log.i(TAG, "AI_DEBUG reload_order_exec step=$step elements=${elements.length()} stepElapsed=${stepElapsed}ms seenPaymentRecently=$seenPaymentRecently")
                            notifyNoAvailablePayMethod()
                        }
                        ApiClient.logEvent(this@AutoPaymentService, opType = "INFO", durationMs = 0, level = "WARN", keyword = "ai_reload_order")
                    }
                    "wait" -> {
                        ApiClient.logEvent(this@AutoPaymentService, opType = "INFO", durationMs = 0, level = "INFO", keyword = "ai_wait")
                    }
                    else -> {
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // 性能优化：降低非必要的检测频率
        // 只有当包名包含支付宝或我们自己的应用时才进行深度检测
        val pkgName = event.packageName?.toString()
        if (pkgName != null && !pkgName.contains("com.eg.android.AlipayGphone") && !pkgName.contains(packageName)) {
             // 忽略其他无关应用的事件
             return
        }

        // 增强补录逻辑：始终监听点击事件并暂存
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val node = event.source
            if (node != null) {
                val rawText = node.text?.toString() ?: node.contentDescription?.toString()
                val text = rawText?.trim()
                
                if (text != null && text.matches(Regex("^[0-9]$"))) { // 使用严格匹配
                    // 始终加入缓冲区，带有时间戳
                    recentClicks.add(Pair(System.currentTimeMillis(), node))
                    // 扩大缓冲区大小，防止快速连点丢失
                    if (recentClicks.size > 50) recentClicks.removeFirst()
                    
                    // 如果正在录制中，且漏掉了，尝试直接补录
                    // 但当触摸拦截层开启时，点击是我们 performAction 触发的，不能在这里二次录入
                    if (isRecording && touchOverlay == null) {
                         val currentTime = System.currentTimeMillis()
                         if (currentTime - lastRecordTime > 100) {
                             Log.w(TAG, "Real-time catch missed click: $text")
                             val rect = Rect()
                             node.getBoundsInScreen(rect)
                             lastRecordTime = currentTime
                             FloatingMenuService.instance?.recordActionFromEvent(rect.centerX(), rect.centerY(), text)
                             // 从缓冲区移除刚刚处理的，避免重复 flush
                             recentClicks.removeLast() 
                         }
                    }
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: ""
            val className = event.className?.toString() ?: ""
            Log.d(TAG, "Window Changed: Pkg: $packageName, Class: $className")
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            dumpAlipayWindowsIfNeeded(event)

            val windows = windows
            if (keypadCaptured && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                
            }
            var foundPaymentWindow = false
            var foundPasswordPromptWindow = false
            var foundSuccessWindow = false
            var foundPasswordError = false
            var balanceInsufficientText: String? = null

            for (window in windows) {
                val root = window.root ?: continue
                
                if (!foundPaymentWindow && hasPaymentKeywords(root)) {
                    foundPaymentWindow = true
                    if (!keypadCaptured) {
                        Log.d(TAG, "Found payment keywords in window: ${window.title}")
                    }
                    
                    if (!keypadCaptured) {
                        captureKeypadLayout(root)
                    }
                }

                if (!foundPasswordPromptWindow && (hasKeypadLayout(root) || hasPasswordPrompt(root))) {
                    foundPasswordPromptWindow = true
                    if (!keypadCaptured) {
                        Log.d(TAG, "Found keypad/password window: ${window.title}")
                        captureKeypadLayout(root)
                    }
                }

                if (balanceInsufficientText == null) {
                    balanceInsufficientText = findBalanceInsufficientText(root)
                }
                
                if (isRecording && !foundSuccessWindow && hasSuccessKeywords(root)) {
                    foundSuccessWindow = true
                }

                if ((isRecording || isLooping) && !foundPasswordError && hasPasswordErrorKeywords(root)) {
                    foundPasswordError = true
                }
                
                // 即使不在录制模式下，也要检测支付成功页面，用于触发循环支付的下一轮
                if (!isRecording && isLooping && !foundSuccessWindow && hasSuccessKeywords(root)) {
                    foundSuccessWindow = true
                    Log.i(TAG, "Detected payment success in LOOP mode.")
                }

                if (!isRecording && !isLooping && !foundSuccessWindow &&
                    (replaySessionId != null || replayPending) &&
                    replayRecordType == "PASSWORD" &&
                    hasSuccessKeywords(root)
                ) {
                    foundSuccessWindow = true
                    Log.i(TAG, "Detected payment success in REPLAY mode.")
                }
                
                if ((foundPaymentWindow || foundPasswordPromptWindow) && (!isRecording || foundSuccessWindow)) break
            }

            if (!foundPaymentWindow && foundPasswordPromptWindow) {
                foundPaymentWindow = true
            }

            val nowEvt = System.currentTimeMillis()
            if (foundPaymentWindow) {
                lastPaymentWindowAt = nowEvt
                if (isLooping && (loopStep == LoopStep.AWAIT_ALIPAY || loopStep == LoopStep.WAIT_NEXT_ORDER)) {
                    setLoopStep(LoopStep.AWAIT_KEYBOARD)
                }
            }
            if (foundSuccessWindow) {
                lastSuccessWindowAt = nowEvt
            }

            if (isRecording) {
                if (foundPasswordError && !passwordErrorVisible) {
                    passwordErrorVisible = true
                    FloatingMenuService.instance?.resetPasswordAttempt("password_error_detected")
                } else if (!foundPasswordError && passwordErrorVisible) {
                    passwordErrorVisible = false
                }
            }

            if (isLooping && foundPasswordError && (foundPaymentWindow || loopStep == LoopStep.AWAIT_KEYBOARD || loopStep == LoopStep.AWAIT_SUCCESS)) {
                val now = System.currentTimeMillis()
                if (now - lastPasswordErrorReportAt > 5000) {
                    lastPasswordErrorReportAt = now
                    val selected = currentSelectedPayMethod(windows).ifBlank { SecureStorage.loadLastPayMethod(this) }
                    val selectedNorm = normalizePayMethodLabel(selected)
                    ApiClient.reportPaymentMethodStatus(
                        this,
                        method = selectedNorm.ifBlank { "UNKNOWN" },
                        status = "FAIL",
                        message = "支付密码错误/验证失败（脚本可能不匹配）",
                        success = false,
                    )
                    ApiClient.logEvent(this, opType = "ERROR", durationMs = 0, level = "ERROR", keyword = "password_error")
                    isLooping = false
                    loopingState = false
                    setLoopStep(LoopStep.IDLE)
                    if ((replaySessionId != null || replayPending) && replayRecordType == "LOOP_PAYMENT") {
                        stopReplay("INCOMPLETE")
                    }
                    Handler(Looper.getMainLooper()).post {
                        removeBlockingOverlay()
                    }
                    ApiClient.upsertDevice(this, accessibilityEnabled = true, scriptRecorded = hasSavedPassword(), looping = false)
                }
            }

            if (!balanceInsufficientText.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastBalanceInsufficientReportAt > 5000) {
                    lastBalanceInsufficientReportAt = now
                    val selected = currentSelectedPayMethod(windows).ifBlank { SecureStorage.loadLastPayMethod(this) }
                    val selectedNorm = normalizePayMethodLabel(selected)
                    ApiClient.reportPaymentMethodStatus(
                        this,
                        method = selectedNorm.ifBlank { "BALANCE" },
                        status = "INSUFFICIENT",
                        message = balanceInsufficientText ?: "",
                        success = false,
                    )
                    ApiClient.logEvent(this, opType = "ERROR", durationMs = 0, level = "WARN", keyword = "balance_insufficient")
                }
            }

            if (isLooping && !balanceInsufficientText.isNullOrEmpty()) {
                foundSuccessWindow = false
                switchToNextPayMethod(windows, balanceInsufficientText ?: "余额不足")
            }
            
            if (foundPaymentWindow) {
                lastAiAssistFingerprint = ""
                var floatingService = FloatingMenuService.instance
                if (floatingService == null) {
                    ensureFloatingServiceRunning()
                    floatingService = FloatingMenuService.instance
                }
                if (floatingService != null && floatingService.hasScript()) {
                    if (!isRecording) {
                         if (isLooping && !hasPasswordUiReady(windows) &&
                             clickRepeatPayContinueIfPresent(windows, "payment_window_detected")
                         ) {
                             setLoopStep(LoopStep.AWAIT_KEYBOARD)
                             return
                         }
                         if (isLooping && hasPasswordUiReady(windows)) {
                             setLoopStep(LoopStep.AWAIT_KEYBOARD)
                         }
                         Log.d(TAG, "Found payment page and script exists. Triggering auto-play.")
                         maybeEnsurePayMethodAndPlay(windows)
                         if (!isLooping && replaySessionId == null && !replayPending) {
                             startReplay("PASSWORD")
                         }
                         if (isLooping && replaySessionId == null && !replayPending) {
                             startReplay("LOOP_PAYMENT")
                         }
                    }
                } else {
                    if (!isRecording) {
                        if (isLooping) {
                            Log.w(TAG, "Looping enabled but script is missing; skip PASSWORD auto-record.")
                        } else {
                            // 1. 立即开启录制状态和拦截层，解决启动延迟问题
                            isRecording = true
                            notifyStartRecordPay()
                            Handler(Looper.getMainLooper()).post {
                                createTouchLayer()
                            }
                            
                            // 2. 尝试回溯补录刚刚发生的点击
                            flushRecentClicks()
                            
                            // 3. 异步通知 UI 更新
                            startFloatingServiceWithAction(FloatingMenuService.ACTION_START_AUTO_RECORD)
                        }
                    }
                }
            } else if (foundSuccessWindow) {
                lastAiAssistFingerprint = ""
                if (isRecording) {
                    Log.d(TAG, "Found success page. Stopping recording.")
                    FloatingMenuService.instance?.commitScript() // 使用 commitScript 确保只保存最后6位
                    startFloatingServiceWithAction(FloatingMenuService.ACTION_STOP_AUTO_RECORD)
                    isRecording = false
                    val roots = selectReplayRoots(windows)
                    if (roots.isNotEmpty()) {
                        maybeSendReplayFrame(roots, true)
                    }
                    stopReplay("COMPLETED")
                    Handler(Looper.getMainLooper()).postDelayed({
                        bringAppToFront()
                    }, 1500)
                }
                
                // 无论是录制模式结束，还是纯粹的循环模式，只要检测到成功，都触发通知
                if (isLooping) {
                    Log.i(TAG, "Payment success confirmed. Broadcasting to MainActivity.")
                    val now = System.currentTimeMillis()
                    waitingNextOrder = true
                    waitingNextOrderAt = now
                    setLoopStep(LoopStep.WAIT_NEXT_ORDER)
                    if (now - lastPaySuccessReportAt > 5000) {
                        lastPaySuccessReportAt = now
                        val selected = currentSelectedPayMethod(windows).ifBlank { SecureStorage.loadLastPayMethod(this) }
                        val selectedNorm = normalizePayMethodLabel(selected)
                        saveLastSuccessPayMethodIfAllowed(selectedNorm)
                        val roots = selectReplayRoots(windows)
                        val orderId = getCurrentPayOrderId()
                        ApiClient.reportPaymentMethodStatus(
                            this,
                            method = selectedNorm.ifBlank { "BALANCE" },
                            status = "OK",
                            message = "支付成功",
                            success = true,
                        )
                        if (orderId.isNotBlank() && roots.isNotEmpty()) {
                            reportAccessibilitySuccessEvidence(orderId, selectedNorm, roots)
                        }
                    }
                    notifyPaymentSuccess()
                    val roots = selectReplayRoots(windows)
                    if (roots.isNotEmpty()) {
                        maybeSendReplayFrame(roots, true)
                    }
                    if ((replaySessionId != null || replayPending) && replayRecordType == "LOOP_PAYMENT") {
                        stopReplay("COMPLETED")
                    }
                }

                if (
                    !isRecording &&
                    !isLooping &&
                    (replaySessionId != null || replayPending) &&
                    replayRecordType == "PASSWORD"
                ) {
                    val roots = selectReplayRoots(windows)
                    if (roots.isNotEmpty()) {
                        maybeSendReplayFrame(roots, true)
                    }
                    stopReplay("COMPLETED")
                }
            } else {
                if (!foundPaymentWindow) {
                     if (keypadCaptured) {
                        keypadCaptured = false
                        Log.d(TAG, "Payment window lost, resetting keypad capture status.")
                    }
                    // 移除自动清空逻辑，防止因 Toast 或短暂窗口切换导致录制中断
                    // if (isRecording && !foundSuccessWindow) {
                    //    Log.d(TAG, "Payment window lost during recording. Clearing temp script.")
                    //    FloatingMenuService.instance?.clearTempScript()
                    // }
                }
            }
            if ((replaySessionId != null || replayPending) && windows.isNotEmpty()) {
                val roots = selectReplayRoots(windows)
                if (roots.isNotEmpty()) {
                    maybeSendReplayFrame(roots, event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                }
            }
        }
    }

    private var lastPlayTime = 0L

    // 检查键盘是否可见
    private fun isKeypadVisible(): Boolean {
        return keypadMap.isNotEmpty()
    }

    fun performScript(actions: List<ScriptAction>) {
        if (System.currentTimeMillis() - lastPlayTime < 3000) {
            Log.d(TAG, "Script playback debounced.")
            return
        }
        if (isLooping) {
            setLoopStep(LoopStep.AWAIT_SUCCESS)
        }
        lastPlayTime = System.currentTimeMillis()
        
        Handler(Looper.getMainLooper()).post {
            removeTouchLayer()
        }

        CoroutineScope(Dispatchers.Default).launch {
            val playbackActions = run {
                val clickActions = actions.filter { it.type == ScriptActionType.CLICK }
                val maskedCount = clickActions.count { a ->
                    val d = a.targetDigit?.trim().orEmpty()
                    d.isNotEmpty() && d.all { it == '*' }
                }
                if (clickActions.isNotEmpty() && maskedCount > 0) {
                    val pwd = SecureStorage.loadPaymentRecord(this@AutoPaymentService)?.password
                        ?.trim()
                        .orEmpty()
                        .filter { it.isDigit() }
                    if (pwd.isNotBlank()) {
                        val rebuilt = ArrayList<ScriptAction>(pwd.length)
                        for (i in pwd.indices) {
                            val base = clickActions.getOrNull(i) ?: clickActions.last()
                            rebuilt.add(base.copy(targetDigit = pwd[i].toString()))
                        }
                        ApiClient.logEvent(this@AutoPaymentService, opType = "INFO", durationMs = 0, level = "WARN", keyword = "script_masked_use_password")
                        rebuilt
                    } else {
                        actions
                    }
                } else {
                    actions
                }
            }

            // 阶段 1: 等待键盘完全就绪 (最多等待 3 秒)
            // 有时候虽然进入了支付页，但键盘可能还在动画中，或者尚未捕获到布局
            Log.i(TAG, "Waiting for keypad to become ready...")
            var waitCount = 0
            while (!isKeypadVisible() && waitCount < 30) { // 30 * 100ms = 3s
                delay(100)
                // 尝试重新捕获一次（万一之前错过了）
                val root = rootInActiveWindow
                if (root != null) {
                    captureKeypadLayout(root)
                }
                waitCount++
            }
            
            if (!isKeypadVisible()) {
                Log.w(TAG, "Keypad not detected after waiting! Attempting blind execution.")
                // 即使没检测到，也尝试盲输（依赖录制坐标）
            } else {
                Log.i(TAG, "Keypad ready. Starting input.")
            }
            
            // 稍微等待一下，确保界面完全就绪
            delay(200)
            
            for ((index, action) in playbackActions.withIndex()) {
                if (action.type == ScriptActionType.CLICK) {
                    var targetX = action.x
                    var targetY = action.y
                    
                    val digit = action.targetDigit
                    var clickedByNode = false
                    
                    // 优先尝试节点直连点击
                    if (digit != null) {
                        // 简单的重试机制：如果第一次没点到，等待 50ms 再试一次
                        for (retry in 0..1) {
                            clickedByNode = clickNodeByDigit(digit)
                            if (clickedByNode) {
                                Log.i(TAG, "Smart Input [$index]: Clicked node for digit $digit")
                                // 实时显示当前输入的数字
                                break
                            } else {
                                if (retry == 0) delay(50)
                            }
                        }
                    }
                    
                    // 如果节点点击失败，降级使用坐标模拟点击
                    if (!clickedByNode) {
                        if (digit != null && keypadMap.containsKey(digit)) {
                            val rect = keypadMap[digit]!!
                            targetX = rect.centerX()
                            targetY = rect.centerY()
                            Log.i(TAG, "Smart Mapping [$index]: Remapped digit $digit to ($targetX,$targetY)")
                        } else if (keypadCaptured && digit != null) {
                             // 如果键盘已经捕获了，但没有找到这个数字（奇怪的情况），尝试模糊匹配
                             // 比如 '1' 没找到，但找到了 '1 ' 或 ' 1'
                             val fuzzyKey = keypadMap.keys.find { it.trim() == digit }
                             if (fuzzyKey != null) {
                                 val rect = keypadMap[fuzzyKey]!!
                                 targetX = rect.centerX()
                                 targetY = rect.centerY()
                                 Log.i(TAG, "Fuzzy Mapping [$index]: Remapped digit $digit to ($targetX,$targetY)")
                             } else {
                                 // 相对坐标回退：如果完全找不到数字节点，但我们知道键盘区域
                                 // 假设标准 3x4 键盘布局
                                 if (keypadWidth > 0 && keypadHeight > 0) {
                                     // 计算相对位置 (0-9, *, #)
                                     // 1 2 3
                                     // 4 5 6
                                     // 7 8 9
                                     //   0  
                                     val keyIndex = when (digit) {
                                         "1" -> 0
                                         "2" -> 1
                                         "3" -> 2
                                         "4" -> 3
                                         "5" -> 4
                                         "6" -> 5
                                         "7" -> 6
                                         "8" -> 7
                                         "9" -> 8
                                         "0" -> 10 // 0在最后一行中间
                                         else -> -1
                                     }
                                     
                                     if (keyIndex != -1) {
                                         val row = keyIndex / 3
                                         val col = keyIndex % 3
                                         
                                         // 键盘总高度通常是按键区域的高度，每行高度 = 总高度 / 4
                                         val cellWidth = keypadWidth / 3
                                         val cellHeight = keypadHeight / 4
                                         
                                         val centerX = keypadBaseX + (col * cellWidth) + (cellWidth / 2)
                                         val centerY = keypadBaseY + (row * cellHeight) + (cellHeight / 2)
                                         
                                         targetX = centerX
                                         targetY = centerY
                                         Log.i(TAG, "Relative Mapping Success [$index]: Calculated ($targetX, $targetY) for digit $digit")
                                     } else {
                                         Log.w(TAG, "Relative Mapping Failed [$index]: Unknown digit $digit")
                                     }
                                 }
                                 Log.w(TAG, "Smart Mapping Failed [$index]: Using original coordinates for digit $digit")
                             }
                        } else {
                            Log.w(TAG, "Smart Mapping Failed [$index]: Using original coordinates for digit $digit")
                        }
                        
                        // 实时显示当前输入的数字（模拟点击分支）
                        click(targetX, targetY)
                    }
                }
                // 使用固定极速延迟 (150ms -> 200ms)，增加稳定性，避免丢字
                delay(200)
            }
            
            // 支付动作执行完毕后，尝试自动返回 App
            // 等待足够的时间让支付结果页出现（比如 2 秒），然后执行返回操作
            // 循环模式下不要强制拉起 App。
            // 否则会在支付宝还没来得及展示“余额不足/可更换付款方式”等结果页时被打断，
            // 导致无法切换付款方式，也无法正确上报失败原因。
            
            // 支付动作执行完毕后，延迟一小会儿自动关闭遮罩
            // 如果是循环模式，则不关闭遮罩，而是等待成功回调去触发下一次
            // 但如果支付失败了呢？这里可以做一个兜底，比如 10秒后没有成功则刷新
            if (!isLooping) {
                delay(1000)
                Handler(Looper.getMainLooper()).post {
                    removeBlockingOverlay()
                }
            } else {
                Log.d(TAG, "Loop mode active, keeping overlay for next round.")
            }
        }
    }

    fun performClickNow(x: Int, y: Int, onComplete: (() -> Unit)? = null) {
        click(x, y, onComplete)
    }

    private fun click(x: Int, y: Int, onComplete: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val builder = GestureDescription.Builder()
        val gestureDescription = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
            
        dispatchGesture(gestureDescription, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture dispatch succeeded for ($x, $y)")
                onComplete?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "Gesture dispatch cancelled for ($x, $y)")
                onComplete?.invoke()
            }
        }, null)
        Log.d(TAG, "Attempting click at $x, $y")
    }
}
