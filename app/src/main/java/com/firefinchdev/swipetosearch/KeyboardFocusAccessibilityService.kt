package com.firefinchdev.swipetosearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

@SuppressLint("AccessibilityPolicy")
class KeyboardFocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyboardFocusService"
        private const val TARGET_ID_NAME = "search_src_text"
        private const val FOCUS_COOLDOWN_MS = 500L
        private const val DEBOUNCE_DELAY_MS = 10L
        const val PREF_NAME = "app_prefs"
        const val KEY_SERVICE_ENABLED = "service_enabled"
    }

    private var currentLauncherPackage: String? = null
    private var cachedFullViewId: String? = null
    private var lastFocusAttemptTime = 0L
    private var isDrawerSessionActive = false
    private var isServiceEnabledInApp = true

    private lateinit var stabilityHandler: Handler
    private val stabilityRunnable = Runnable { onUiStabilized() }

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == KEY_SERVICE_ENABLED) {
            isServiceEnabledInApp = sharedPreferences.getBoolean(KEY_SERVICE_ENABLED, true)
        }
    }

    override fun onServiceConnected() {
        stabilityHandler = Handler(mainLooper)

        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        isServiceEnabledInApp = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        identifyDefaultLauncher()

        // Configure service info dynamically to filter only launcher events
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // Filter events to only the launcher package — huge perf win
            packageNames = currentLauncherPackage?.let { arrayOf(it) }
        }
    }

    private fun identifyDefaultLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = resolveInfo?.activityInfo?.packageName
        if (pkg != null) {
            currentLauncherPackage = pkg
            cachedFullViewId = "$pkg:id/$TARGET_ID_NAME"
        } else {
            Log.w(TAG, "Could not identify a default launcher.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Package filtering is already done by the system via serviceInfo.packageNames,
        // but guard against edge cases where launcher hasn't been identified.
        if (currentLauncherPackage == null) return

        // Debounce: coalesce rapid-fire events into a single stabilized callback
        stabilityHandler.removeCallbacks(stabilityRunnable)
        stabilityHandler.postDelayed(stabilityRunnable, DEBOUNCE_DELAY_MS)
    }

    private fun onUiStabilized() {
        if (!isServiceEnabledInApp) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFocusAttemptTime > FOCUS_COOLDOWN_MS) {
            attemptFocus(currentTime)
        }
    }

    private fun attemptFocus(currentTime: Long) {
        val rootNode = rootInActiveWindow ?: return
        val viewId = cachedFullViewId ?: return

        try {
            val foundNodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (!foundNodes.isNullOrEmpty()) {
                if (!isDrawerSessionActive) {
                    val targetNode = foundNodes[0]
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    lastFocusAttemptTime = currentTime
                    isDrawerSessionActive = true
                }
            } else {
                if (isDrawerSessionActive) {
                    isDrawerSessionActive = false
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
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
    }
}