package com.fwp.doubaonewline

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.CheckBox
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.AudioRouteManager
import com.fwp.doubaonewline.bridge.NewlineBridgeService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var selectedBluetoothText: TextView
    private lateinit var accessibilityStatusText: TextView
    private lateinit var audioRouteManager: AudioRouteManager
    private var receiverRegistered = false
    private var pendingBluetoothSelection = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            statusText.text = intent?.getStringExtra(BridgeContract.EXTRA_STATUS)
                ?: "服务运行中"
            detailText.text = intent?.getStringExtra(BridgeContract.EXTRA_DETAIL).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        selectedBluetoothText = findViewById(R.id.selectedBluetoothText)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        audioRouteManager = AudioRouteManager(this)
        setupAudioSettings()

        findViewById<Button>(R.id.selectBluetoothButton).setOnClickListener {
            chooseBluetoothDevice()
        }
        findViewById<LinearLayout>(R.id.accessibilitySettingRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (hasNotificationPermission()) {
            enableAndStartService()
        } else {
            requestNotificationPermission()
            statusText.text = "请先允许通知，然后启动连接服务"
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(BridgeContract.ACTION_STATUS)
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        if (hasNotificationPermission()) {
            startServiceSafely()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun enableAndStartService() {
        getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(BridgeContract.PREF_ENABLED, true)
            .apply()
        startServiceSafely()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            enableAndStartService()
        } else if (requestCode == REQUEST_BLUETOOTH) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (pendingBluetoothSelection) chooseBluetoothDevice()
                startServiceSafely()
            } else {
                statusText.text = "需要附近设备权限"
                detailText.text = "没有该权限无法列出或使用蓝牙通话设备。"
            }
            pendingBluetoothSelection = false
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun startServiceSafely() {
        runCatching { NewlineBridgeService.start(this) }
            .onSuccess { statusText.text = "连接服务已启动" }
            .onFailure {
                statusText.text = "连接服务启动失败"
                detailText.text = it.message ?: it.javaClass.simpleName
            }
    }

    private fun setupAudioSettings() {
        val prefs = getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
        val usbCheck = findViewById<CheckBox>(R.id.usbEnabledCheck)
        val bluetoothCheck = findViewById<CheckBox>(R.id.bluetoothEnabledCheck)

        usbCheck.isChecked = prefs.getBoolean(BridgeContract.PREF_USB_ENABLED, true)
        bluetoothCheck.isChecked =
            prefs.getBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, true)

        usbCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeContract.PREF_USB_ENABLED, checked).apply()
            startServiceSafely()
        }
        bluetoothCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BridgeContract.PREF_BLUETOOTH_ENABLED, checked).apply()
            if (checked && !hasBluetoothPermission()) {
                requestBluetoothPermission(false)
            } else {
                startServiceSafely()
            }
        }
        updateSelectedBluetoothText()
    }

    private fun chooseBluetoothDevice() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission(true)
            return
        }

        val candidates = audioRouteManager.bluetoothCandidates()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("没有可列出的蓝牙设备")
                .setMessage(
                    "请先在系统蓝牙设置中配对至少一个设备。" +
                        "列表会显示所有已配对设备，并标出当前是否已连接。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val selectedKey = audioRouteManager.selectedBluetoothKey()
        val names = candidates.map { candidate ->
            if (candidate.key == selectedKey) {
                selectedBluetoothListLabel(candidate.displayLabel)
            } else {
                candidate.displayLabel
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择蓝牙通话设备")
            .setItems(names) { _, index ->
                audioRouteManager.saveBluetoothCandidate(candidates[index])
                findViewById<CheckBox>(R.id.bluetoothEnabledCheck).isChecked = true
                updateSelectedBluetoothText()
                startServiceSafely()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectedBluetoothListLabel(deviceLabel: String): CharSequence {
        val selectedText = "  已选择"
        return SpannableString(deviceLabel + selectedText).apply {
            setSpan(
                ForegroundColorSpan(Color.rgb(21, 101, 192)),
                0,
                deviceLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                deviceLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(Color.rgb(211, 47, 47)),
                deviceLabel.length,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun updateSelectedBluetoothText() {
        selectedBluetoothText.text = audioRouteManager.selectedBluetoothName()?.let {
            "已选择：$it"
        } ?: "尚未选择蓝牙设备"
    }

    private fun updateAccessibilityStatus() {
        val component = ComponentName(this, DoubaoAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val enabled = enabledServices.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
        accessibilityStatusText.text = if (enabled) "已开启" else "未开启 · 点击设置"
        accessibilityStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.success else R.color.warning
            )
        )
    }

    private fun requestBluetoothPermission(selectAfterGrant: Boolean) {
        pendingBluetoothSelection = selectAfterGrant
        if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH
            )
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
        private const val REQUEST_BLUETOOTH = 101
    }
}
