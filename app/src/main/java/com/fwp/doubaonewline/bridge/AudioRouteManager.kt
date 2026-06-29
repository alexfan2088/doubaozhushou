package com.fwp.doubaonewline.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat

class AudioRouteManager(private val context: Context) {

    enum class Kind { USB, BLUETOOTH, NONE }

    data class BluetoothCandidate(
        val key: String,
        val name: String,
        val device: AudioDeviceInfo
    )

    data class Selection(
        val kind: Kind,
        val label: String,
        val routeAccepted: Boolean
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)

    fun select(snapshot: AudioDeviceMonitor.Snapshot): Selection {
        val usbEnabled = prefs.getBoolean(BridgeContract.PREF_USB_ENABLED, true)
        val bluetoothEnabled = prefs.getBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, false)

        if (usbEnabled && snapshot.ready) {
            val device = availableCommunicationDevices().firstOrNull(::isUsb)
            return activate(device, Kind.USB, device?.productName?.toString() ?: "Type-C USB 音频")
        }

        if (bluetoothEnabled && hasBluetoothPermission()) {
            val selectedKey = prefs.getString(BridgeContract.PREF_BLUETOOTH_DEVICE, null)
            val candidate = bluetoothCandidates().firstOrNull { it.key == selectedKey }
            if (candidate != null) {
                return activate(candidate.device, Kind.BLUETOOTH, candidate.name)
            }
        }

        clear()
        return Selection(Kind.NONE, "无可用双向音频设备", false)
    }

    fun bluetoothCandidates(): List<BluetoothCandidate> {
        if (!hasBluetoothPermission()) return emptyList()
        return availableCommunicationDevices()
            .filter(::isBluetoothCommunication)
            .distinctBy(::stableKey)
            .map {
                BluetoothCandidate(
                    key = stableKey(it),
                    name = it.productName?.toString()?.ifBlank { "蓝牙通话设备" }
                        ?: "蓝牙通话设备",
                    device = it
                )
            }
    }

    fun saveBluetoothCandidate(candidate: BluetoothCandidate) {
        prefs.edit()
            .putString(BridgeContract.PREF_BLUETOOTH_DEVICE, candidate.key)
            .putBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, true)
            .apply()
    }

    fun selectedBluetoothKey(): String? =
        prefs.getString(BridgeContract.PREF_BLUETOOTH_DEVICE, null)

    fun selectedBluetoothName(): String? {
        val key = selectedBluetoothKey() ?: return null
        return key.substringAfterLast('|').ifBlank { null }
    }

    fun clear() {
        runCatching { audioManager.clearCommunicationDevice() }
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun activate(
        device: AudioDeviceInfo?,
        kind: Kind,
        fallbackLabel: String
    ): Selection {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val accepted = device != null && runCatching {
            audioManager.setCommunicationDevice(device)
        }.getOrDefault(false)
        return Selection(kind, device?.productName?.toString() ?: fallbackLabel, accepted)
    }

    private fun availableCommunicationDevices(): List<AudioDeviceInfo> =
        runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())

    private fun hasBluetoothPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun isUsb(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun isBluetoothCommunication(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun deviceIdentity(device: AudioDeviceInfo): String =
        "${device.type}|${device.address}|${device.productName}"

    private fun stableKey(device: AudioDeviceInfo): String = deviceIdentity(device)
}
