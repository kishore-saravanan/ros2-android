package dev.ros2android.health

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

/**
 * Detects process-wide execution pauses. A dedicated thread sleeps for [periodMs]; when it wakes at
 * least [thresholdMs] later than requested, every thread in the process was stopped, for example by
 * a vendor app freezer, device suspend or an attached debugger. A slow ROS callback does not delay
 * this thread, so a blocked executor is not reported as a pause.
 */
class PauseDetector(val periodMs: Long = 250, private val thresholdMs: Long = 1000) {
    data class Snapshot(
        val pauses: Int = 0,
        val longestMs: Long = 0,
        val totalMs: Long = 0,
        val lastEndedAt: Long = -1,
    )

    @Volatile
    var snapshot = Snapshot()
        private set

    private var last = -1L

    @Synchronized
    fun reset(now: Long) {
        snapshot = Snapshot()
        last = now
    }

    /** Record one wake-up of the sleeping thread at [now] (boot-clock milliseconds). */
    @Synchronized
    fun tick(now: Long) {
        if (last >= 0) {
            val late = now - last - periodMs
            if (late >= thresholdMs) {
                snapshot = snapshot.run {
                    copy(
                        pauses = pauses + 1,
                        longestMs = maxOf(longestMs, late),
                        totalMs = totalMs + late,
                        lastEndedAt = now,
                    )
                }
            }
        }

        last = now
    }
}

/** Owns the sampling thread for one monitored run; [start] resets the previous run's counts. */
class PauseMonitor(
    private val detector: PauseDetector = PauseDetector(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var thread: Thread? = null
    val snapshot: PauseDetector.Snapshot
        get() = detector.snapshot

    val monitoring: Boolean
        @Synchronized get() = thread != null

    @Synchronized
    fun start() {
        if (thread != null) return
        detector.reset(clock())
        thread =
            Thread(
                    {
                        while (true) {
                            try {
                                Thread.sleep(detector.periodMs)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }

                            detector.tick(clock())
                        }
                    },
                    "ros2android-pause-monitor",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}

/** Standard Android background policy for a package; vendor-specific switches are not readable. */
object BackgroundPolicy {
    /**
     * True when battery optimization applies, so Android or the vendor may pause background work.
     */
    fun batteryOptimized(context: Context, packageName: String = context.packageName): Boolean? =
        runCatching {
            !context
                .getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
        }
        .getOrNull()

    /** True when the user restricted this app's background use (API 28+; own package only). */
    fun backgroundRestricted(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT < 28) {
            false
        } else {
            runCatching {
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            }
                .getOrNull()
        }
}

/** Concise operator wording shared by the main app and runners. */
object HealthText {
    fun seconds(milliseconds: Long): String =
        String.format(Locale.ROOT, "%.1f s", milliseconds / 1000.0)

    /** Null when no pause was observed in the current or most recent monitored run. */
    fun pauseWarning(subject: String, pauses: Int, longestMs: Long): String? =
        if (pauses <= 0) {
            null
        } else {
            "Android paused $subject ${if (pauses == 1) "once" else "$pauses times"} " +
                "(longest ${seconds(longestMs)}). " +
                "Allow background activity in Android app settings."
        }

    fun policy(batteryOptimized: Boolean?, backgroundRestricted: Boolean?): String =
        when {
            backgroundRestricted == true ->
                "Background use is restricted: Android stops ROS work when this app is not visible."
            batteryOptimized == true ->
                "Battery optimized: Android may pause ROS work in the background."
            batteryOptimized == false -> "Background activity allowed: battery optimization is off."
            else -> "Background policy unavailable on this device."
        }
}
