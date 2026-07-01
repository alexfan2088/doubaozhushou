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

class AudioRouteManager(private val context: Context) {

    enum class Kind { USB, BLUETOOTH, NONE }

    data class BluetoothCandidate(
        val key: String,
        val displayLabel: String,
        val device: AudioDeviceInfo?,
        val bluetoothDevice: BluetoothDevice
    )

    data class Selection(
        val kind: Kind,
        val label: String,
        val routeAccepted: Boolean,
        val inputDevice: AudioDeviceInfo?,
        val deviceKey: String?
    )

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val prefs = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
    private val bluetoothSelectionStore = BluetoothSelectionStore(context)
    @Volatile
    private var headsetProxy: BluetoothHeadset? = null
    @Volatile
    private var a2dpProxy: BluetoothA2dp? = null
    private var activatedCommunicationIdentity: String? = null

    init {
        requestProfileProxy(BluetoothProfile.HEADSET) { headsetProxy = it as? BluetoothHeadset }
        requestProfileProxy(BluetoothProfile.A2DP) { a2dpProxy = it as? BluetoothA2dp }
    }

    fun select(snapshot: AudioDeviceMonitor.Snapshot): Selection {
        val usbEnabled = prefs.getBoolean(BridgeContract.PREF_USB_ENABLED, true)
        val bluetoothEnabled = prefs.getBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, false)

        if (usbEnabled && snapshot.ready) {
            releaseBluetoothCommunication()
            suppressBluetoothA2dp()
            val device = availableCommunicationDevices().firstOrNull(::isUsb)
            return activate(device, Kind.USB, device?.productName?.toString() ?: "Type-C USB 音频")
        }

        if (bluetoothEnabled && hasBluetoothPermission()) {
            restoreBluetoothA2dp()
            val selectedKey = selectedBluetoothKey()
            val candidate = bluetoothCandidates().firstOrNull { it.key == selectedKey }
            if (candidate != null) {
                requestSelectedBluetoothAudio(candidate.bluetoothDevice)
                val communicationDevice = candidate.device
                    ?: availableCommunicationDevices()
                        .firstOrNull(::isBluetoothCommunication)
                return activate(
                    communicationDevice,
                    Kind.BLUETOOTH,
                    candidate.displayLabel
                )
            }
        }

        clear()
        return Selection(Kind.NONE, "无可用双向音频设备", false, null, null)
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
                    device = audioDevice,
                    bluetoothDevice = bluetoothDevice
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

    fun selectedBluetoothMicrophoneConnected(): Boolean {
        val key = selectedBluetoothKey() ?: return false
        val address = key.substringBefore('|').trim().lowercase()
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
        activatedCommunicationIdentity = null
        releaseBluetoothCommunication()
        restoreBluetoothA2dp()
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun releaseBluetoothCommunication() {
        @Suppress("DEPRECATION")
        if (
            !audioManager.isBluetoothScoOn &&
            audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    private fun suppressBluetoothA2dp() {
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothA2dpOn) {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn = false
        }
    }

    private fun restoreBluetoothA2dp() {
        @Suppress("DEPRECATION")
        if (!audioManager.isBluetoothA2dpOn) {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothA2dpOn = true
        }
    }

    private fun requestSelectedBluetoothAudio(device: BluetoothDevice) {
        // Do not reject devices based on app-side capability guesses. Ask Android
        // to open audio for exactly the device selected by the user; the platform
        // will expose whichever HFP/SCO/A2DP routes that device actually provides.
        runCatching { headsetProxy?.startVoiceRecognition(device) }
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }
    }

    private fun activate(
        device: AudioDeviceInfo?,
        kind: Kind,
        fallbackLabel: String
    ): Selection {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val current = audioManager.communicationDevice
        val requestedIdentity = device?.let(::deviceIdentity)
        var accepted = device != null && (
            activatedCommunicationIdentity == requestedIdentity &&
                current?.let { representsSameAudioDevice(it, device) } == true ||
                runCatching { audioManager.setCommunicationDevice(device) }.getOrDefault(false)
            )
        if (kind == Kind.BLUETOOTH && !accepted) {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
            accepted = device != null && runCatching {
                audioManager.setCommunicationDevice(device)
            }.getOrDefault(false)
        }
        if (accepted) {
            activatedCommunicationIdentity = requestedIdentity
        }
        val input = externalInputs().firstOrNull { candidate ->
            when (kind) {
                Kind.USB -> isUsb(candidate)
                Kind.BLUETOOTH -> isBluetoothCommunication(candidate)
                Kind.NONE -> false
            }
        }
        val key = input?.let(::deviceIdentity)
        return Selection(
            kind,
            if (kind == Kind.BLUETOOTH) {
                fallbackLabel
            } else {
                input?.productName?.toString() ?: device?.productName?.toString() ?: fallbackLabel
            },
            accepted && input != null,
            input,
            key
        )
    }

    private fun availableCommunicationDevices(): List<AudioDeviceInfo> =
        runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())

    private fun externalInputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { isUsb(it) || isBluetoothCommunication(it) }

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

    private fun representsSameAudioDevice(
        first: AudioDeviceInfo,
        second: AudioDeviceInfo
    ): Boolean {
        if (first.address.isNotBlank() && second.address.isNotBlank()) {
            return first.address.equals(second.address, ignoreCase = true)
        }
        return first.productName.toString()
            .equals(second.productName.toString(), ignoreCase = true)
    }

    private fun stableKey(device: AudioDeviceInfo): String = deviceIdentity(device)

    private fun stableKey(device: BluetoothDevice): String =
        "${device.address.lowercase()}|${device.name.orEmpty()}"
}
