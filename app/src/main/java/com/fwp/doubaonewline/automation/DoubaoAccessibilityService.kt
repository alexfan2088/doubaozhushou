package com.fwp.doubaonewline.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.bridge.BridgeContract

/**
 * Narrowly scoped to the Doubao package by accessibility_service_config.xml.
 *
 * This is a compatibility fallback until a stable, verified Doubao Intent/URI is captured.
 * It never clicks generic "confirm" controls and only acts on explicit voice conversation labels.
 */
class DoubaoAccessibilityService : AccessibilityService() {

    private var lastClickAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var hangupReceiverRegistered = false

    private val hangupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BridgeContract.ACTION_END_DOUBAO_CALL) {
                attemptHangup(retriesRemaining = 5)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!hangupReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                hangupReceiver,
                IntentFilter(BridgeContract.ACTION_END_DOUBAO_CALL),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            hangupReceiverRegistered = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != DOUBAO_PACKAGE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        val match = findByResourceId(root)
            ?: TARGET_LABELS.firstNotNullOfOrNull { findTarget(root, it) }
            ?: return
        val clickable = findClickableAncestor(match.node) ?: return

        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastClickAt = now
            Log.i(TAG, "Clicked Doubao voice entry: ${match.label}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        if (hangupReceiverRegistered) {
            unregisterReceiver(hangupReceiver)
            hangupReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun attemptHangup(retriesRemaining: Int) {
        Log.i(TAG, "Hangup requested; retriesRemaining=$retriesRemaining")
        val root = findDoubaoRoot()
        val target = root?.let(::findHangupTarget)
        val clickable = target?.let { findClickableAncestor(it.node) }
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            Log.i(TAG, "Clicked Doubao hangup control: ${target.label}")
            return
        }
        if (retriesRemaining > 0) {
            handler.postDelayed(
                { attemptHangup(retriesRemaining - 1) },
                HANGUP_RETRY_MS
            )
        } else if (!dispatchHangupGesture()) {
            Log.w(TAG, "Doubao hangup control was not found and gesture failed")
        }
    }

    private fun findDoubaoRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let {
            if (it.packageName?.toString() == DOUBAO_PACKAGE) return it
        }
        return windows
            .asSequence()
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == DOUBAO_PACKAGE }
    }

    private fun dispatchHangupGesture(): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * HANGUP_X_RATIO
        val y = metrics.heightPixels * HANGUP_Y_RATIO
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.removeCallbacksAndMessages(null)
                    Log.i(TAG, "Clicked Doubao hangup by gesture at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Doubao hangup gesture was cancelled")
                }
            },
            null
        )
    }

    private fun findHangupTarget(root: AccessibilityNodeInfo): Match? {
        HANGUP_RESOURCE_IDS.forEach { resourceId ->
            val node = root.findAccessibilityNodeInfosByViewId(resourceId)
                .firstOrNull { it.isEnabled }
            if (node != null) return Match(node, resourceId)
        }
        return HANGUP_LABELS.firstNotNullOfOrNull { findTarget(root, it) }
    }

    private fun findByResourceId(root: AccessibilityNodeInfo): Match? {
        TARGET_RESOURCE_IDS.forEach { resourceId ->
            val node = root.findAccessibilityNodeInfosByViewId(resourceId)
                .firstOrNull { it.isEnabled }
            if (node != null) return Match(node, resourceId)
        }
        return null
    }

    private fun findTarget(root: AccessibilityNodeInfo, targetLabel: String): Match? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = listOfNotNull(node.text, node.contentDescription)
                .joinToString(" ")
                .trim()

            if (label.equals(targetLabel, ignoreCase = true)) {
                return Match(node, label)
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return null
    }

    private fun findClickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        repeat(6) {
            if (current?.isClickable == true && current?.isEnabled == true) return current
            current = current?.parent
        }
        return null
    }

    private data class Match(val node: AccessibilityNodeInfo, val label: String)

    companion object {
        private const val TAG = "DoubaoAutomation"
        private const val DOUBAO_PACKAGE = "com.larus.nova"
        private const val CLICK_COOLDOWN_MS = 10_000L
        private const val HANGUP_RETRY_MS = 500L
        // Derived from the inspected 1080x2400 layout: X center [825,2000]-[1035,2210].
        private const val HANGUP_X_RATIO = 0.861f
        private const val HANGUP_Y_RATIO = 0.877f
        private val TARGET_RESOURCE_IDS = listOf(
            "com.larus.nova:id/real_time_call_ic_container",
            "com.larus.nova:id/real_time_call"
        )
        private val TARGET_LABELS = listOf("实时语音通话", "语音通话", "打电话")
        private val HANGUP_RESOURCE_IDS = listOf(
            "com.larus.nova:id/voice_call_hangup_container",
            "com.larus.nova:id/voice_call_hangup",
            "com.larus.nova:id/hangup",
            "com.larus.nova:id/hang_up",
            "com.larus.nova:id/end_call",
            "com.larus.nova:id/close_call"
        )
        private val HANGUP_LABELS = listOf("挂断通话", "挂断", "结束通话", "退出通话")

        @Volatile
        private var instance: DoubaoAccessibilityService? = null

        fun requestHangup() {
            val service = instance
            if (service == null) {
                Log.w(TAG, "Accessibility service is not connected; cannot hang up")
                return
            }
            service.handler.removeCallbacksAndMessages(null)
            service.attemptHangup(retriesRemaining = 1)
        }

        fun requestCallStart() {
            instance?.apply {
                lastClickAt = 0L
            }
            Log.i(TAG, "Doubao realtime call start requested")
        }
    }
}
