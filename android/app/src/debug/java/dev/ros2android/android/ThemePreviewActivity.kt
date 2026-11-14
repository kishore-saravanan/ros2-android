package dev.ros2android.android

import android.content.Context
import android.content.res.Configuration

/** App-local test contexts; the user's device appearance is never changed. */
class LightPreviewActivity : MainActivity() {
    override fun attachBaseContext(base: Context) {
        val config =
            Configuration(base.resources.configuration).apply {
                uiMode =
                    (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_NO
            }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
}

class DarkPreviewActivity : MainActivity() {
    override fun attachBaseContext(base: Context) {
        val config =
            Configuration(base.resources.configuration).apply {
                uiMode =
                    (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_YES
            }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
}
