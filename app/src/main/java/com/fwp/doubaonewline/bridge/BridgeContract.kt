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

    const val DOUBAO_PACKAGE = "com.larus.nova"
}
