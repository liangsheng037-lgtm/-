package com.example.demo

enum class ScriptActionType {
    CLICK,
    SWIPE,
    WAIT
}

data class ScriptAction(
    val x: Int = 0,
    val y: Int = 0,
    val delay: Long = 1000,
    val type: ScriptActionType = ScriptActionType.CLICK,
    val description: String = "",
    val targetDigit: String? = null // 新增字段：目标数字
)
