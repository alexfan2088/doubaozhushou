package com.fwp.doubaonewline.bridge

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat

class AudioRouteManager(
    private val context: Context,
    preferenceNamespace: String? = null
) {

    enum class Kind { USB, BLUETOOTH, NONE }

    data class BluetoothCandidate(
        val key: String,
        val displayLabel: String,
        val device: AudioDeviceInfo?
    )

    data class Selection(
        val kind: Kind,
        val label: String,
        val routeAccepted: Boolean
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val prefs = context.getSharedPreferences(
        preferenceNamespace?.let { "${it}_audio_preferences" } ?: BridgeContract.PREFS,
        Context.MODE_PRIVATE
    )
    private val bluetoothSelectionStore = BluetoothSelectionStore(
        context,
        preferenceNamespace?.let { "bluetooth_selection_$it.json" }
            ?: "bluetooth_selection.json"
    )
    @Volatile
    private var headsetProxy: BluetoothHeadset? = null
    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null
    private var previousUsbReady: Boolean? = null

    init {
        requestProfileProxy(BluetoothProfile.HEADSET) { headsetProxy = it as? BluetoothHeadset }
        requestProfileProxy(BluetoothProfile.A2DP) { a2dpProxy = it as? BluetoothA2dp }
    }

    fun select(snapshot: AudioDeviceMonitor.Snapshot): Selection {
        if (previousUsbReady == false && snapshot.ready) {
            prefs.edit()
                .putString(BridgeContract.PREF_LAST_AUDIO_ROUTE, BridgeContract.ROUTE_USB)
                .apply()
        }
        previousUsbReady = snapshot.ready
        val usbEnabled = prefs.getBoolean(BridgeContract.PREF_USB_ENABLED, true)
        val bluetoothEnabled = prefs.getBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, false)
        val lastRoute = prefs.getString(
            BridgeContract.PREF_LAST_AUDIO_ROUTE,
            BridgeContract.ROUTE_USB
        )

        if (
            lastRoute == BridgeContract.ROUTE_USB &&
            usbEnabled &&
            snapshot.ready
        ) {
            val device = availableCommunicationDevices().firstOrNull(::isUsb)
            return activate(device, Kind.USB, device?.productName?.toString() ?: "Type-C USB 音频")
        }

        if (bluetoothEnabled && hasBluetoothPermission()) {
            val selectedKey = selectedBluetoothKey()
            val candidate = bluetoothCandidates().firstOrNull { it.key == selectedKey }
            if (candidate != null) {
                val selectedHfpConnected = selectedBluetoothHfpConnected()
                if (!selectedHfpConnected && candidate.device == null) {
                    releaseBluetoothCommunication()
                    if (!usbEnabled || !snapshot.ready) {
                        return Selection(
                            Kind.BLUETOOTH,
                            candidate.displayLabel,
                            false
                        )
                    }
                } else {
                    val communicationDevice = candidate.device
                        ?: if (selectedHfpConnected) {
                            availableCommunicationDevices().firstOrNull(::isBluetoothCommunication)
                        } else {
                            null
                        }
                    return activate(
                        communicationDevice,
                        Kind.BLUETOOTH,
                        candidate.displayLabel
                    )
                }
            }
        }

        if (usbEnabled && snapshot.ready) {
            val device = availableCommunicationDevices().firstOrNull(::isUsb)
            return activate(device, Kind.USB, device?.productName?.toString() ?: "Type-C USB 音频")
        }

        clear()
        return Selection(Kind.NONE, "无可用双向音频设备", false)
    }

    fun bluetoothCandidates(): List<BluetoothCandidate> {
        if (!hasBluetoothPermission()) return emptyList()
        val pairedDevices = pairedBluetoothDevices()
        if (pairedDevices.isEmpty()) return emptyList()

        // Some OEMs only publish the HFP/SCO endpoint while communication mode is active.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val audioDevices = availableCommunicationDevices()
            .filter(::isBluetoothCommunication)
        val connectedAddresses = connectedBluetoothAddresses()

        return pairedDevices
            .sortedWith(
                compareByDescending<BluetoothDevice> { it.address.lowercase() in connectedAddresses }
                    .thenBy { it.name.orEmpty().lowercase() }
                    .thenBy { it.address.lowercase() }
            )
            .map { bluetoothDevice ->
                val audioDevice = audioDevices.firstOrNull {
                    representsSameDevice(it, bluetoothDevice)
                }
                BluetoothCandidate(
                    key = stableKey(bluetoothDevice),
                    displayLabel = buildDisplayLabel(
                        bluetoothDevice,
                        bluetoothDevice.address.lowercase() in connectedAddresses
                    ),
                    device = audioDevice
                )
            }
            .distinctBy { it.key }
    }

    fun saveBluetoothCandidate(candidate: BluetoothCandidate) {
        bluetoothSelectionStore.save(
            BluetoothSelectionStore.Record(
                key = candidate.key,
                displayLabel = candidate.displayLabel
            )
        )
        prefs.edit()
            .putString(BridgeContract.PREF_BLUETOOTH_DEVICE, candidate.key)
            .putBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, true)
            .putString(BridgeContract.PREF_LAST_AUDIO_ROUTE, BridgeContract.ROUTE_BLUETOOTH)
            .apply()
    }

    fun selectedBluetoothKey(): String? =
        bluetoothSelectionStore.load()?.key
            ?: prefs.getString(BridgeContract.PREF_BLUETOOTH_DEVICE, null)?.also { legacyKey ->
                bluetoothSelectionStore.save(
                    BluetoothSelectionStore.Record(
                        key = legacyKey,
                        displayLabel = legacyKey.substringAfter('|').ifBlank {
                            legacyKey.substringBefore('|').ifBlank { legacyKey }
                        }
                    )
                )
            }

    fun selectedBluetoothConnected(): Boolean {
        val key = selectedBluetoothKey() ?: return false
        val address = key.substringBefore('|').trim().lowercase()
        return address.isNotEmpty() && address in connectedBluetoothAddresses()
    }

    private fun selectedBluetoothHfpConnected(): Boolean {
        val key = selectedBluetoothKey() ?: return false
        val address = key.substringBefore('|').trim()
        if (address.isEmpty()) return false
        return runCatching {
            headsetProxy?.connectedDevices.orEmpty()
                .any { it.address.equals(address, ignoreCase = true) }
        }.getOrDefault(false)
    }

    fun selectedBluetoothName(): String? {
        val record = bluetoothSelectionStore.load()
        val key = selectedBluetoothKey() ?: return null
        val candidate = bluetoothCandidates().firstOrNull { it.key == key }
        return candidate?.displayLabel
            ?: record?.displayLabel
            ?: key.substringAfterLast('|').ifBlank { null }
    }

    fun clear() {
        runCatching { audioManager.clearCommunicationDevice() }
        releaseBluetoothCommunication()
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun releaseBluetoothCommunication() {
        runCatching { audioManager.clearCommunicationDevice() }
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
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
        if (kind == Kind.BLUETOOTH && accepted) {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
        }
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

    private fun pairedBluetoothDevices(): Set<BluetoothDevice> =
        runCatching { bluetoothManager.adapter?.bondedDevices.orEmpty() }
            .getOrDefault(emptySet())

    private fun connectedBluetoothDevices(): List<BluetoothDevice> =
        buildList {
            addAll(runCatching { headsetProxy?.connectedDevices.orEmpty() }.getOrDefault(emptyList()))
            addAll(runCatching { a2dpProxy?.connectedDevices.orEmpty() }.getOrDefault(emptyList()))
        }.distinctBy { it.address.lowercase() }

    private fun connectedBluetoothAddresses(): Set<String> =
        connectedBluetoothDevices().map { it.address.lowercase() }.toSet()

    private fun buildDisplayLabel(device: BluetoothDevice, isConnected: Boolean): String {
        val name = device.name?.ifBlank { null } ?: device.address
        val status = if (isConnected) "已连接" else "已配对"
        return "$name（$status）"
    }

    private fun requestProfileProxy(
        profile: Int,
        onConnected: (BluetoothProfile?) -> Unit
    ) {
        bluetoothManager.adapter?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                    if (profileId == profile) onConnected(proxy)
                }

                override fun onServiceDisconnected(profileId: Int) {
                    if (profileId == profile) onConnected(null)
                }
            },
            profile
        )
    }

    private fun representsSameDevice(
        audioDevice: AudioDeviceInfo,
        bluetoothDevice: BluetoothDevice
    ): Boolean {
        val audioAddress = audioDevice.address.trim()
        if (audioAddress.isNotEmpty() &&
            audioAddress.equals(bluetoothDevice.address, ignoreCase = true)
        ) {
            return true
        }

        val audioName = audioDevice.productName?.toString()?.trim().orEmpty()
        val bluetoothName = bluetoothDevice.name?.trim().orEmpty()
        return audioName.isNotEmpty() &&
            bluetoothName.isNotEmpty() &&
            audioName.equals(bluetoothName, ignoreCase = true)
    }

    private fun deviceIdentity(device: AudioDeviceInfo): String =
        "${device.type}|${device.address}|${device.productName}"

    private fun stableKey(device: AudioDeviceInfo): String = deviceIdentity(device)

    private fun stableKey(device: BluetoothDevice): String =
        "${device.address.lowercase()}|${device.name.orEmpty()}"
}
