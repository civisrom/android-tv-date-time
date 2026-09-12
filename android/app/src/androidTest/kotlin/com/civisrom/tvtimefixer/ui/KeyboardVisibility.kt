package com.civisrom.tvtimefixer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.view.accessibility.AccessibilityWindowInfo

internal fun hasLegacyImeWindow(automation: UiAutomation): Boolean {
    // Old Android can retain a window list from before the IME appeared. The public
    // setServiceInfo API clears that accessibility cache before the next query.
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    val windows = automation.windows
    try {
        return windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } finally {
        @Suppress("DEPRECATION")
        windows.forEach { it.recycle() }
    }
}
