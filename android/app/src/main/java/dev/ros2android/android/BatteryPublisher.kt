package dev.ros2android.android

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/** Battery capacity only: no personal data, runtime permission, or stored samples. */
class BatteryPublisher(context: Context, private val onError: (String) -> Unit) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    var lastPercentage: Int? = null
        private set

    private fun percentage(): Int =
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).also {
            check(it in 0..100) { "Battery percentage is unavailable on this device." }
        }

    fun start(config: EndpointConfig) {
        check(tick == null) { "Battery publisher is already active." }
        require(config.rateHz <= 1.0) { "Choose a battery rate between 0.2 and 1 Hz." }
        percentage() // Fail before marking the endpoint active if the property is unsupported.
        val interval = (1000.0 / config.rateHz).toLong()
        val task =
            object : Runnable {
                override fun run() {
                    if (tick !== this) return
                    runCatching {
                        val value = percentage()
                        check(
                            NativeRuntime.publishSample(
                                "battery",
                                System.currentTimeMillis() * 1_000_000L,
                                floatArrayOf(value.toFloat()),
                            )
                        ) {
                            "Battery ROS publication failed."
                        }

                        lastPercentage = value
                    }
                        .onFailure {
                            stop()
                            onError(it.message ?: "Battery reading failed")
                            return
                        }
                    main.postDelayed(this, interval)
                }
            }
        tick = task
        main.post(task)
    }

    fun stop() {
        tick?.let(main::removeCallbacks)
        tick = null
        lastPercentage = null
    }
}
