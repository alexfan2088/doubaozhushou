package com.fwp.doubaonewline

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.DoubaoLauncher
import com.fwp.doubaonewline.bridge.NewlineBridgeService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private var receiverRegistered = false

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

        findViewById<Button>(R.id.startButton).setOnClickListener {
            enableAndStartService()
        }
        findViewById<Button>(R.id.testDoubaoButton).setOnClickListener {
            DoubaoAccessibilityService.requestCallStart()
            DoubaoLauncher(this).launch()
                .onSuccess { statusText.text = "豆包已启动" }
                .onFailure {
                    statusText.text = "豆包启动失败"
                    detailText.text = it.message
                }
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            statusText.text = "请找到“豆包连续对话自动启动”并开启"
            detailText.text = "该权限仅用于在豆包内点击电话图标，进入实时语音通话。只需开启一次。"
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

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
    }
}
