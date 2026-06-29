package com.fwp.doubaonewline.bridge

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class AudioDeviceMonitor(
    context: Context,
    private val onChanged: (Snapshot) -> Unit
) {
    data class Snapshot(
        val usbInputs: List<String>,
        val usbOutputs: List<String>
    ) {
        val ready: Boolean get() = usbInputs.isNotEmpty() && usbOutputs.isNotEmpty()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = emit()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = emit()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        emit()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun snapshot(): Snapshot {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter(::isUsbAudio)
            .map(::displayName)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isUsbAudio)
            .map(::displayName)
        return Snapshot(inputs, outputs)
    }

    private fun emit() = onChanged(snapshot())

    private fun isUsbAudio(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun displayName(device: AudioDeviceInfo): String =
        "${device.productName} (id=${device.id}, type=${device.type})"
}
