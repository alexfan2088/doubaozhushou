package com.fwp.doubaonewline.bridge

object BridgeContract {
    const val ACTION_START = "com.fwp.doubaonewline.action.START"
    const val ACTION_USB_DETACHED = "com.fwp.doubaonewline.action.USB_DETACHED"
    const val ACTION_STATUS = "com.fwp.doubaonewline.action.STATUS"
    const val ACTION_END_DOUBAO_CALL = "com.fwp.doubaonewline.action.END_DOUBAO_CALL"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DETAIL = "detail"

    const val PREFS = "bridge_preferences"
    const val PREF_ENABLED = "service_enabled"
    const val PREF_DOUBAO_URI = "doubao_conversation_uri"
    const val PREF_USB_ENABLED = "usb_audio_enabled"
    const val PREF_BLUETOOTH_ENABLED = "bluetooth_audio_enabled"
    const val PREF_BLUETOOTH_DEVICE = "bluetooth_audio_device"
    const val PREF_READY_GREETING = "ready_greeting"
    const val PREF_MODE = "app_mode"
    const val MODE_V1 = "v1"
    const val MODE_V2 = "v2"
    const val MODE_V3 = "v3"

    const val DOUBAO_PACKAGE = "com.larus.nova"
    const val DEFAULT_READY_GREETING = "豆包豆包，你好啊"

    fun normalizeReadyGreeting(value: String?): String {
        val greeting = value?.trim().orEmpty()
        return if (greeting.isEmpty() || greeting in LEGACY_DEFAULT_READY_GREETINGS) {
            DEFAULT_READY_GREETING
        } else {
            greeting
        }
    }

    private val LEGACY_DEFAULT_READY_GREETINGS = setOf(
        "你好啊，我准备好了，今天我们聊点什么",
        "我是豆包，我准备好了，今天我们聊点什么",
        "樊叔叔，我是豆包，今天我们聊点什么啊"
    )
}
