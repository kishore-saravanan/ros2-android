package dev.ros2android.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Release builds have no development test commands. */
object DevelopmentCommands {
    @Suppress("UNUSED_PARAMETER")
    fun handle(context: Context, intent: Intent, receiver: BroadcastReceiver): Boolean = false
}
