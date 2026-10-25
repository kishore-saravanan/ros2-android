package dev.ros2android.android

import android.content.Context
import android.content.res.Configuration

/** Instrumentation-only large-text context. Never changes system font/display settings. */
class LargeTextPreviewActivity : MainActivity() {
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { fontScale = 2f }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
}
