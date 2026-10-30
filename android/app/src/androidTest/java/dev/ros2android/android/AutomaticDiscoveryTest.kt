package dev.ros2android.android

import android.app.KeyguardManager
import android.content.*
import android.os.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The independent PC observer must prove discovery; local counts alone cannot. */
class AutomaticDiscoveryTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    @Test fun batteryWithoutAnyConfiguredPeerOrLocalAddress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in live network test", args.getString("networkDiscovery") == "true")
        val seconds = args.getString("discoverySeconds", "45").toInt().also { require(it in 15..120) }
        val context = instrumentation.targetContext
        lateinit var session: RobotSessionService
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { session = (binder as RobotSessionService.LocalBinder).session; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        activity.scenario.onActivity { app ->
            check(!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            context.bindService(Intent(context, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        fun waitUntil(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < end) {
                var value = false; instrumentation.runOnMainSync { value = check() }
                if (value) return
                SystemClock.sleep(100)
            }
            fail("Session did not reach expected state")
        }
        try {
            instrumentation.runOnMainSync { session.stopSession() }
            waitUntil { !session.connected && !session.busy }
            activity.scenario.onActivity { app ->
                check(app.hasWindowFocus() && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                session.startPublisher(CapabilityCatalog.publishers(app).single { it.id == "battery" },
                    EndpointConfig("/phone/battery_percent", 1.0), RobotSessionService.Profile(SessionConfig("/phone", 0), "", ""))
            }
            waitUntil { !session.busy && "battery" in session.active }
            SystemClock.sleep(seconds * 1000L)
            assertTrue(NativeRuntime.publishedCount("battery") >= seconds / 2)
            instrumentation.runOnMainSync { assertTrue(session.errors.isEmpty()) }
        } finally {
            instrumentation.runOnMainSync { session.stopSession() }
            waitUntil { !session.connected && !session.busy }
            context.unbindService(connection)
        }
    }
}
