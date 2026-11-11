package dev.ros2android.android

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ros2android.protocol.ProjectProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in, stopped disposable talker only. Never sends a valid start/stop/configure. */
@RunWith(AndroidJUnit4::class)
class RunnerProtocolTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test fun legacyAndVersionedStatusWorkAndIncompatibleRequestsHaveNoSideEffects() {
        val target = InstrumentationRegistry.getArguments().getString("protocolTestPackage")
        assumeTrue("Requires the disposable protocol fixture", target != null)
        require(target == "dev.ros2android.android.runner.ws_auth_workspace")
        lateinit var app: Context
        lateinit var trust: ProjectTrust
        var originallyApproved = false
        var trustReady = false
        var bindingAttempted = false
        val connected = CountDownLatch(1)
        val remote = AtomicReference<Messenger>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(Messenger(service)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        fun request(command: Int, data: Bundle = Bundle()): Bundle {
            val received = CountDownLatch(1)
            val response = AtomicReference<Bundle>()
            val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (!trust.accepts(msg.sendingUid)) return
                    if (msg.what != command) return
                    response.set(Bundle(msg.data)); received.countDown()
                }
            })
            checkNotNull(remote.get()).send(Message.obtain(null, command).apply { replyTo = reply; this.data = data })
            assertTrue("No authenticated reply to command $command", received.await(4, TimeUnit.SECONDS))
            return checkNotNull(response.get())
        }
        fun snapshot(): JSONObject {
            val reply = request(ProjectProtocol.STATUS, Bundle().apply { putInt(ProjectProtocol.VERSION_KEY, 1) })
            assertNull(reply.getString("error"))
            assertTrue("Rebuild disposable fixture with the current SDK first", reply.containsKey(ProjectProtocol.VERSION_KEY))
            assertEquals(1, reply.getInt(ProjectProtocol.VERSION_KEY))
            assertEquals(ProjectProtocol.commands, reply.getIntArray(ProjectProtocol.COMMANDS_KEY)!!.toSet())
            return JSONObject(checkNotNull(reply.getString("status")))
        }
        try {
            activity.scenario.onActivity { screen ->
                check(!screen.getSystemService(KeyguardManager::class.java).isKeyguardLocked && screen.hasWindowFocus())
                app = screen.applicationContext
                val project = InstalledProjects.list(app).single { it.packageName == target }
                trust = ProjectTrust(app, project)
                originallyApproved = trust.authorized()
                trustReady = true
                if (!originallyApproved) trust.approve(checkNotNull(trust.fingerprint()))
                val service = if (trust.paired) "PairedProjectControlService" else "ProjectControlService"
                bindingAttempted = true
                val bound = app.bindService(Intent().setComponent(ComponentName(target!!,
                    "dev.ros2android.runner.$service")), connection, Context.BIND_AUTO_CREATE)
                assertTrue("Cannot bind protocol fixture", bound)
            }
            assertTrue("Protocol fixture did not connect", connected.await(4, TimeUnit.SECONDS))
            val original = snapshot()
            assertEquals("Stop disposable fixture before running this test", "Stopped", original.getString("state"))
            val legacy = request(ProjectProtocol.STATUS)
            assertNull(legacy.getString("error"))
            assertEquals(1, legacy.getInt(ProjectProtocol.VERSION_KEY))
            assertEquals(ProjectProtocol.commands, legacy.getIntArray(ProjectProtocol.COMMANDS_KEY)!!.toSet())
            assertEquals("Stopped", JSONObject(legacy.getString("status")!!).getString("state"))
            val incompatible = listOf(
                Bundle().apply { putInt(ProjectProtocol.VERSION_KEY, 2) },
                Bundle().apply { putString(ProjectProtocol.VERSION_KEY, "1") },
                Bundle().apply { putString(ProjectProtocol.VERSION_KEY, null) }
            )
            for (version in incompatible) for (command in ProjectProtocol.commands) {
                val reply = request(command, Bundle(version).apply { putString("domain", "102") })
                assertTrue(reply.getString("error")!!.contains("Unsupported runner protocol"))
                assertNull(reply.getString("status"))
                val after = snapshot()
                assertEquals(original.getString("state"), after.getString("state"))
                assertEquals(original.getJSONObject("config").toString(), after.getJSONObject("config").toString())
            }
            val unknown = request(99, Bundle().apply { putInt(ProjectProtocol.VERSION_KEY, 1) })
            assertTrue(unknown.getString("error")!!.contains("Unsupported project control request"))
            assertEquals(original.getJSONObject("config").toString(), snapshot().getJSONObject("config").toString())
        } finally {
            activity.scenario.onActivity {
                if (bindingAttempted) runCatching { app.unbindService(connection) }
                if (trustReady && !originallyApproved) trust.revoke()
            }
        }
    }
}
