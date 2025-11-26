package com.firefinchdev.swipetosearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

@SuppressLint("AccessibilityPolicy")
class KeyboardFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyboardFocusService"
        private const val TARGET_ID_NAME = "search_src_text"
        private const val FOCUS_COOLDOWN_MS = 500L
    }
    private var currentLauncherPackage: String? = null

    private var lastFocusAttemptTime = 0L

    // STATE TRACKING:
    private var isDrawerSessionActive = false

    private val stabilityHandler = Handler(Looper.getMainLooper())
    private val stabilityRunnable = Runnable { onUiStabilized() }

    override fun onServiceConnected() {
        Log.d(TAG, "Accessibility service connected")
        val info = serviceInfo
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info

        identifyDefaultLauncher()
    }

    /**
     * CHANGED: Uses resolveActivity to find the SINGLE active default home app.
     */
    private fun identifyDefaultLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }

        // resolveActivity returns the "best match" (the one the user selected as default)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            currentLauncherPackage = resolveInfo.activityInfo.packageName
            Log.d(TAG, "Active Default Launcher found: $currentLauncherPackage")
        } else {
            Log.w(TAG, "Could not identify a default launcher.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // CHANGED: Compare against the single active launcher string
        if (packageName == currentLauncherPackage) {
            stabilityHandler.removeCallbacks(stabilityRunnable)
            stabilityHandler.post(stabilityRunnable)
        }
    }

    private fun onUiStabilized() {

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFocusAttemptTime > FOCUS_COOLDOWN_MS) {
            attemptFocus(currentTime)
        }


    }

    private fun attemptFocus(currentTime: Long) {
        val rootNode = rootInActiveWindow ?: return

        try {
            val packageName = rootNode.packageName?.toString()
            if (packageName == currentLauncherPackage) {
                val fullViewId = "$packageName:id/$TARGET_ID_NAME"
                Log.d(TAG, "searching")
                val foundNodes = rootNode.findAccessibilityNodeInfosByViewId(fullViewId)
                if (!foundNodes.isNullOrEmpty()) {
                    if (!isDrawerSessionActive) {
                        // CASE 1: First time detection
                        val targetNode = foundNodes[0]
                        Log.d(TAG, "Drawer opened: Clicking search bar")
                        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        lastFocusAttemptTime = currentTime
                        isDrawerSessionActive = true
                    }
                    // CASE 2: Already active, do nothing
                } else {
                    Log.d(TAG, "not found")
                    // CASE 3: Not found (likely closed drawer or moved away)
                    if (isDrawerSessionActive) {
                        Log.d(TAG, "Search bar lost: Resetting session")
                        isDrawerSessionActive = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding search bar", e)
        }
    }

    override fun onInterrupt() {
        stabilityHandler.removeCallbacks(stabilityRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stabilityHandler.removeCallbacks(stabilityRunnable)
    }
}