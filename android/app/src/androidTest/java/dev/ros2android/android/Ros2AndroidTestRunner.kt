package dev.ros2android.android

import android.Manifest
import android.os.Build
import androidx.test.runner.AndroidJUnitRunner

/**
 * Grants this app's own notification permission on Android 13+ before any test, so the app's
 * one-time prompt cannot pause the Activity under test. Tests never drive system permission UI; the
 * prompt itself is checked manually on Android 13+ devices.
 */
class Ros2AndroidTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                uiAutomation.grantRuntimePermission(
                    targetContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }

        super.onStart()
    }
}
