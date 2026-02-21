package com.example.demo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

class AutoPaymentService : AccessibilityService() {

    private val TAG = "AutoPaymentService"
    private val NODE_DUMP_TAG = "AlipayNodeDump"

    companion object {
        var instance: AutoPaymentService? = null
        const val ACTION_BLOCK_TOUCH = "com.example.demo.action.BLOCK_TOUCH"
        const val ACTION_STOP_LOOP = "com.example.demo.action.STOP_LOOP"
        @Volatile var loopingState: Boolean = false
    }

    private var blockingView: FrameLayout? = null
    private var isLooping = false // 标记是否处于循环支付模式
    private var waitingNextOrder: Boolean = false
    private var waitingNextOrderAt: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_BLOCK_TOUCH -> {
                    Log.d(TAG, "Received BLOCK_TOUCH action")
                    isLooping = true // 开启循环模式
                    loopingState = true
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
                    if ((replaySessionId != null || replayPending) && replayRecordType == "LOOP_PAYMENT") {
                        stopReplay("INCOMPLETE")
                    }
                    Handler(Looper.getMainLooper()).post {
                        removeBlockingOverlay()
                        Toast.makeText(this, "循环支付已停止", Toast.LENGTH_SHORT).show()
                    }
                    ApiClient.upsertDevice(this, accessibilityEnabled = true, scriptRecorded = hasSavedPassword(), looping = false)
                    ApiClient.logEvent(this, opType = "LOOP_STOP", durationMs = 0, level = "INFO", keyword = "looping=false")
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun hasSavedPassword(): Boolean {
        val record = SecureStorage.loadPaymentRecord(this)
        return record != null && record.scriptJson.isNotBlank()
    }

    private fun showBlockingOverlay() {
        if (blockingView != null) return

        blockingView = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }

        // 停止按钮 (红色)
        val btnStop = android.widget.Button(this).apply {
            text = "停止循环支付"
            textSize = 18f
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                isLooping = false
                loopingState = false
                if ((replaySessionId != null || replayPending) && replayRecordType == "LOOP_PAYMENT") {
                    stopReplay("INCOMPLETE")
                }
                removeBlockingOverlay()
                Toast.makeText(context, "用户手动停止循环", Toast.LENGTH_SHORT).show()
                ApiClient.upsertDevice(context, accessibilityEnabled = true, scriptRecorded = hasSavedPassword(), looping = false)
                ApiClient.logEvent(context, opType = "LOOP_STOP", durationMs = 0, level = "INFO", keyword = "manual_stop")
            }
        }
        
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 100
        }
        
        blockingView?.addView(btnStop, btnParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(blockingView, params)
            Toast.makeText(this, "全屏遮罩已开启，循环支付中...", Toast.LENGTH_SHORT).show()
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
        bringAppToFront()
    }

    private fun notifyNoAvailablePayMethod() {
        if (!isLooping) return
        val now = System.currentTimeMillis()
        if (waitingNextOrder && now - waitingNextOrderAt < 15000) return
        waitingNextOrder = true
        waitingNextOrderAt = now
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove blocking overlay: ${e.message}")
            }
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
                         // Toast.makeText(this@AutoPaymentService, "已录制6位", Toast.LENGTH_SHORT).show()
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
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
    private var passwordErrorVisible = false
    private var lastBalanceInsufficientReportAt = 0L
    private var lastPaySuccessReportAt = 0L
    private var lastNodeDumpAt = 0L
    @Volatile private var payMethodSwitching = false
    private var lastPayMethodSwitchAt = 0L
    private var lastPaymentWindowAt = 0L
    private var lastSuccessWindowAt = 0L
    private var lastPasswordErrorReportAt = 0L

    private fun truncateText(s: String?, maxLen: Int): String {
        if (s == null) return ""
        val t = s.trim()
        if (t.length <= maxLen) return t
        return t.substring(0, maxLen) + "…"
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

    private fun isValidPayMethodLabel(label: String): Boolean {
        val t = normalizePayMethodLabel(label)
        if (t.isBlank()) return false
        if (t.startsWith("添加")) return false
        if (t.contains("添加银行卡")) return false
        if (t.contains("小荷包")) return false
        return true
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
        var name = withoutPrefix.joinToString(separator = "")
        return normalizePayMethodLabel(name)
    }

    private fun currentSelectedPayMethod(windows: List<AccessibilityWindowInfo>): String {
        for (r in selectReplayRoots(windows)) {
            val sel = findSelectedPayMethodLabel(r)
            if (!sel.isNullOrBlank()) return sel
        }
        return ""
    }

    private fun hasPasswordPrompt(windows: List<AccessibilityWindowInfo>): Boolean {
        val keys = listOf("请输入支付密码", "支付密码", "输入密码")
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
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
                    if (isValidPayMethodLabel(label)) out.add(label)
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
        if (!isValidPayMethodLabel(desired)) return false
        for (r in selectReplayRoots(windows)) {
            val q = ArrayDeque<AccessibilityNodeInfo>()
            q.add(r)
            var visited = 0
            while (q.isNotEmpty() && visited < 9000) {
                val n = q.removeFirst()
                visited++
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                if (n.isClickable && (desc.startsWith("已选中,") || desc.startsWith("未选中,"))) {
                    val label = normalizePayMethodLabel(extractPayMethodFromDesc(desc))
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
        val lastSuccess = SecureStorage.loadLastSuccessPayMethod(this)
        if (lastSuccess.isNotBlank()) return lastSuccess
        val rec = SecureStorage.loadPaymentRecord(this)
        if (rec != null && rec.payMethod.isNotBlank()) return rec.payMethod
        return SecureStorage.loadLastPayMethod(this)
    }

    private var lastTryReportAt = 0L

    private fun maybeEnsurePayMethodAndPlay(windows: List<AccessibilityWindowInfo>) {
        if (payMethodSwitching) return
        val now = System.currentTimeMillis()
        if (now - lastPayMethodSwitchAt < 1500) {
            FloatingMenuService.instance?.playScript()
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
                    val windows2 = windows
                    var picked: String? = null
                    if (isValidPayMethodLabel(desired) && !desiredIsBlocked && clickPayMethodByLabel(windows2, desired)) {
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
                        if (pickedNorm.isNotBlank()) {
                            SecureStorage.saveLastPayMethod(this@AutoPaymentService, pickedNorm)
                        }
                        ApiClient.reportPaymentMethodStatus(
                            this@AutoPaymentService,
                            method = pickedNorm.ifBlank { picked },
                            status = "SELECTED",
                            message = "自动切换选择",
                            success = false,
                        )
                        delay(350)
                    } else {
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
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@AutoPaymentService, "无可用付款方式，准备递减或等待新订单", Toast.LENGTH_LONG).show()
                            }
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
                val needPassword = hasPasswordPrompt(windows)
                if (!needPassword) {
                    if (clickConfirmPaymentIfPresent(windows)) {
                        return@launch
                    }
                }
                FloatingMenuService.instance?.playScript()
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
                val remote = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    ApiClient.getPayMethodStatusesBlocking(this@AutoPaymentService)
                }
                val remoteMap = HashMap<String, ApiClient.PayMethodStatusItem>(remote.size)
                for (it in remote) {
                    remoteMap[normalizePayMethodLabel(it.method)] = it
                }
                val blocked = setOf("INSUFFICIENT", "UNAVAILABLE", "FAIL")
                val list = collectPayMethodCandidates(windows)
                val selectedNorm = normalizePayMethodLabel(selected)
                var next: String? = null
                for (it in list) {
                    val n = normalizePayMethodLabel(it)
                    if (!isValidPayMethodLabel(n) || n == selectedNorm) continue
                    val st = remoteMap[n]?.status?.trim()?.uppercase().orEmpty()
                    if (st in blocked) continue
                    next = it
                    break
                }
                if (!next.isNullOrBlank()) {
                    clickPayMethodByLabel(windows, next)
                    val nextNorm = normalizePayMethodLabel(next)
                    if (nextNorm.isNotBlank()) {
                        SecureStorage.saveLastPayMethod(this@AutoPaymentService, nextNorm)
                    }
                    ApiClient.reportPaymentMethodStatus(
                        this@AutoPaymentService,
                        method = nextNorm.ifBlank { next },
                        status = "SELECTED",
                        message = "自动切换选择",
                        success = false,
                    )
                    delay(350)
                    FloatingMenuService.instance?.playScript()
                } else {
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
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this@AutoPaymentService, "无可用付款方式，准备递减或等待新订单", Toast.LENGTH_LONG).show()
                        }
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
            
            // 优先级策略：
            // 1. 如果同时存在“确认付款”和“使用密码”，必须优先点击“使用密码”
            // 2. 如果只有“确认付款”，则点击“确认付款”
            
            // 扫描整个节点树，先看看有没有“使用密码”
            // 注意：hasPaymentKeywords 是递归调用的，所以这里我们只做当前节点的判断是不够的
            // 我们需要一种机制来在整个页面范围内做决策
            // 但为了保持代码结构简单，我们可以在这里做局部优化：
            
            // 遇到“使用密码”相关关键字，直接触发点击并返回 true（最高优先级）
            if (text.contains("使用密码") || text.contains("密码支付") || text.contains("换用密码")) {
                Log.i(TAG, "High Priority Keyword found: $text")
                
                // 防抖检查
                val currentTime = System.currentTimeMillis()
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
                             // 关键：如果点击了“使用密码”，立即取消任何等待中的“确认付款”点击
                             cancelFallbackClick()
                             return true
                         }
                     }
                } else {
                    Log.d(TAG, "Skipping duplicate click on password switch button")
                    return true // 虽然没点（因为防抖），但也算找到了，防止后续逻辑误判
                }
            }
            
            // 遇到“确认付款”，先不急着点，因为可能“使用密码”在后面
            // 但在当前的递归结构中，很难预知后面有没有。
            // 策略调整：
            // 我们可以只识别关键字返回 true，让外层的 onAccessibilityEvent 去决定？
            // 不，那样太慢。
            // 现行策略：如果当前节点是“确认付款”，我们检查一下它的兄弟节点或者父节点的兄弟节点里有没有“使用密码”？
            // 或者简单点：
            // 支付宝的界面通常是：如果需要切换密码，那个按钮通常很显眼。
            // 如果我们在这里遇到了“确认付款”，说明界面已经弹出了。
            // 如果“使用密码”存在，它通常会在另一个位置。
            
            // 让我们保留原有的逻辑，但在识别“确认付款”时，稍微缓一下？
            // 不，直接点“确认付款”通常也是为了唤起收银台。
            // 用户的需求是：如果“确认付款”和“使用密码”同时出现，先点“使用密码”。
            // 这意味着当前的界面状态是：收银台已弹出，默认可能是指纹/人脸，但用户想用密码。
            // 此时界面上会有一个大的“确认付款”（指纹）和一个小的“使用密码”。
            
            // 所以，当我们遍历到“确认付款”时，我们不能立即认为这就完事了。
            // 我们应该继续遍历，看看能不能找到“使用密码”。
            // 但 performAction 是即时的。
            
            // 修正逻辑：
            // 1. 单独的“使用密码”逻辑保持不变（上面已经处理了，遇到就点，且优先级高）。
            // 2. 对于“确认付款”，我们只将其作为“发现支付页”的信号，而不自动点击它（除非它是唯一的交互入口）。
            //    或者，我们可以让脚本录制者去录制点击“确认付款”？不，我们是自动化的。
            
            // 实际情况：支付宝指纹支付页，主按钮是“立即付款”或“确认支付”。
            // 如果我们不点它，可能无法触发指纹验证（如果我们想用指纹的话）。
            // 但我们要用密码。所以如果界面上有“使用密码”，我们绝对不能点“确认支付”（因为点了就开始指纹验证了）。
            
            // 结论：不要自动点击“确认付款”！
            // 只需要返回 true 告诉系统“支付页到了，准备开始录制/播放”即可。
            // 具体的点击动作，应该交给：
            // A) 自动切换逻辑（上面的代码已经实现了自动点“使用密码”）
            // B) 用户的录制脚本（如果用户录制了点击“确认付款”，那是他的事）
            // C) 或者是自动输入密码的前置动作？
            
            // 等等，用户说：“如果没有使用密码则点确认付款”。
            // 这意味着我们需要自动点击“确认付款”作为兜底。
            
            if (text == "确认支付" || text == "立即付款" || text.contains("确认付款")) {
                Log.d(TAG, "Found generic payment button: $text")
                // 这里我们返回 true，表示找到了支付页。
                // 但是否点击？
                // 我们把点击逻辑延迟到 onAccessibilityEvent 的后续处理中？
                // 或者在这里做一个标记，如果整个树遍历完都没找到“使用密码”，再回来点这个？
                // 这在递归里很难实现。
                
                // 替代方案：
                // 我们假设“使用密码”通常出现在“确认付款”的 *上方* 或 *同级*。
                // 如果我们先遍历到了“确认付款”，说明可能没找到“使用密码”（如果遍历顺序是从上到下）。
                
                // 优化：遇到“确认付款”时，如果当前没有在录制也没有在循环，说明可能是用户首次进入准备录制
                // 此时应该立即准备好键盘捕获，防止用户点太快漏录
                if (!isRecording && !isLooping && !keypadCaptured) {
                    Log.i(TAG, "Pre-emptive keypad capture triggered by 'Confirm Payment'")
                    // 尝试从当前节点的根节点去捕获键盘
                    var root: AccessibilityNodeInfo? = node
                    while (root?.parent != null) {
                        root = root?.parent
                    }
                    if (root != null) {
                        captureKeypadLayout(root)
                    }
                    
                    // 并且提前开启录制状态？不，录制状态需要 FloatingService 来管理
                    // 但我们可以发送一个信号告诉 FloatingService 准备好了
                }

                // 让我们尝试一种“延迟点击”策略：
                // 遇到“确认付款”，记录下来，但不马上点。
                // 启动一个 500ms 的延时任务。
                // 如果在这 500ms 内我们找到了“使用密码”并点击了，那么取消这个延时任务。
                // 如果 500ms 到了还没找到“使用密码”，那就执行点击“确认付款”。
                
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
        val candidateRoots = windows
            .filter { it.root != null }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .filter { (it.packageName?.toString() ?: "") != packageName }

        val alipay = candidateRoots.firstOrNull { (it.packageName?.toString() ?: "").contains("com.eg.android.AlipayGphone") }
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
            
            if (keypadCaptured && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return
            }
            
            val windows = windows
            var foundPaymentWindow = false
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
                
                if (foundPaymentWindow && (!isRecording || foundSuccessWindow)) break
            }

            val nowEvt = System.currentTimeMillis()
            if (foundPaymentWindow) {
                lastPaymentWindowAt = nowEvt
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

            if (isLooping && foundPasswordError) {
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
                    if ((replaySessionId != null || replayPending) && replayRecordType == "LOOP_PAYMENT") {
                        stopReplay("INCOMPLETE")
                    }
                    Handler(Looper.getMainLooper()).post {
                        removeBlockingOverlay()
                        Toast.makeText(this, "支付密码错误，已停止循环支付", Toast.LENGTH_LONG).show()
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
                if (waitingNextOrder) {
                    waitingNextOrder = false
                    waitingNextOrderAt = 0L
                }
                var floatingService = FloatingMenuService.instance
                if (floatingService == null) {
                    ensureFloatingServiceRunning()
                    floatingService = FloatingMenuService.instance
                }
                if (floatingService != null && floatingService.hasScript()) {
                    if (!isRecording) {
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
                }
                
                // 无论是录制模式结束，还是纯粹的循环模式，只要检测到成功，都触发通知
                if (isLooping) {
                    Log.i(TAG, "Payment success confirmed. Broadcasting to MainActivity.")
                    val now = System.currentTimeMillis()
                    if (now - lastPaySuccessReportAt > 5000) {
                        lastPaySuccessReportAt = now
                        val selected = currentSelectedPayMethod(windows).ifBlank { SecureStorage.loadLastPayMethod(this) }
                        val selectedNorm = normalizePayMethodLabel(selected)
                        if (selectedNorm.isNotBlank()) {
                            SecureStorage.saveLastSuccessPayMethod(this, selectedNorm)
                        }
                        ApiClient.reportPaymentMethodStatus(
                            this,
                            method = selectedNorm.ifBlank { "BALANCE" },
                            status = "OK",
                            message = "支付成功",
                            success = true,
                        )
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
        lastPlayTime = System.currentTimeMillis()
        
        Handler(Looper.getMainLooper()).post {
            removeTouchLayer()
            // 支付开始时，如果遮罩还在，可以保留遮罩，防止用户乱点
            // 但如果需要在支付完成后自动关闭遮罩，可以在这里记录状态
            Toast.makeText(this, "正在自动输入密码...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.Default).launch {
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
            
            for ((index, action) in actions.withIndex()) {
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
                                Handler(Looper.getMainLooper()).post {
                                    val toast = Toast.makeText(applicationContext, "输入: $digit", Toast.LENGTH_SHORT)
                                    toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 200)
                                    toast.show()
                                    // 500ms 后取消，避免堆积
                                    Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 500)
                                }
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
                        if (digit != null) {
                            Handler(Looper.getMainLooper()).post {
                                val toast = Toast.makeText(applicationContext, "模拟输入: $digit", Toast.LENGTH_SHORT)
                                toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 200)
                                toast.show()
                                Handler(Looper.getMainLooper()).postDelayed({ toast.cancel() }, 500)
                            }
                        }
                        
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
