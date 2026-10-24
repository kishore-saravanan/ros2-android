package dev.robotbrain.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.*
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val prefs by lazy { getSharedPreferences("endpoints_v1", MODE_PRIVATE) }
    private lateinit var sensors: SensorPublishers
    private lateinit var camera: CameraPublisher
    private lateinit var catalog: List<PhoneCapability>
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private var display: TextView? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var generation = 0
    private var resumed = false
    private var busy = false
    @Volatile private var connected = false
    private val active = mutableSetOf<String>()
    private var subscribed = false
    private var received = "Waiting for a message…"
    private var receivedCount = 0L
    private var tab = "Publishers"
    private var group = "All"
    private var pendingCamera: Pair<PhoneCapability, EndpointConfig>? = null
    private data class Row(val topic: EditText, val rate: EditText?, val button: Button, val state: TextView)
    private val rows = mutableMapOf<String, Row>()
    private val profileFields = mutableMapOf<String, EditText>()
    private val errors = mutableMapOf<String, String>()
    private val accent = Color.rgb(100, 226, 188)
    private val muted = Color.rgb(165, 183, 196)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!prefs.contains("namespace")) {
            val old = getSharedPreferences("robot_profile", MODE_PRIVATE)
            prefs.edit().putString("namespace", old.getString("namespace", "/phone"))
                .putString("domain", old.getInt("domain", 0).toString()).apply()
        }
        catalog = CapabilityCatalog.publishers(this)
        sensors = SensorPublishers(getSystemService(SensorManager::class.java))
        camera = CameraPublisher(this) { message -> main.post {
            catalog.firstOrNull { it.kind == 8 && it.id in active }?.let {
                stopPublisher(it.id); errors[it.id] = message; refresh()
            }
        } }
        render()
        worker.scheduleWithFixedDelay({
            if (!connected || !NativeRuntime.available) return@scheduleWithFixedDelay
            val state = runCatching { NativeRuntime.status() }.getOrDefault("ROS runtime error")
            val message = runCatching { NativeRuntime.pollDisplay() }.getOrNull()
            val counts = catalog.associate { it.id to NativeRuntime.publishedCount(it.id) }
            main.post {
                if (!resumed) return@post
                status.text = state
                if (message != null) { received = message; receivedCount++ }
                display?.text = "$received\n\n$receivedCount received update(s)"
                rows.forEach { (id, row) -> if (id in active) row.state.text = "Publishing · ${counts[id]} messages sent" }
            }
        }, 0, 500, TimeUnit.MILLISECONDS)
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; save(); stopSession(); super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun render() {
        rows.clear(); profileFields.clear(); display = null
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 23, 29)); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val p = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(p.left, p.top, p.right, p.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(24)) }
        scroll.addView(page); setContentView(scroll)
        page.addView(text("ROBOT BRAIN  /  v0.1", 12, accent))
        page.addView(text("Phone ↔ ROS 2 PC", 27))
        page.addView(text("Wi-Fi or USB networking · screen stays awake while open", 12, muted))
        status = text(if (connected) "ROS session active" else "Stopped · choose an endpoint to connect", 14, accent)
        status.contentDescription = "session_status"; page.addView(status)
        val tabs = LinearLayout(this)
        listOf("Publishers", "Subscribers", "Connection").forEach { name ->
            tabs.addView(button(if (name == tab) "● $name" else name) { save(); tab = name; render() }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(tabs)
        when (tab) {
            "Publishers" -> {
                page.addView(text("Choose a capability, edit its topic and maximum rate, then Publish. Missing hardware is disabled. Best-effort, volatile QoS.", 13, muted))
                val filters = LinearLayout(this)
                listOf("All", "Motion", "Cameras", "Other").forEach { name ->
                    filters.addView(button(if (name == group) "● $name" else name) {
                        save(); group = name; render()
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                page.addView(filters)
                catalog.filter { when (group) {
                    "Motion" -> it.kind in listOf(0, 1, 2, 7)
                    "Cameras" -> it.kind == 8
                    "Other" -> it.kind in 3..6
                    else -> true
                } }.forEach(::publisherCard)
            }
            "Subscribers" -> subscriberCard()
            else -> connectionCard()
        }
        page.addView(button("Stop all / disconnect") { stopSession() })
        refresh()
    }
    private fun publisherCard(cap: PhoneCapability) {
        card(cap.label) { box ->
            box.addView(text(cap.messageType, 12, accent))
            box.addView(text(if (cap.available) cap.detail else "Not available on this phone", 13, muted))
            val topic = field(prefs.getString("${cap.id}.topic", cap.defaultTopic)!!, "topic:${cap.id}")
            box.addView(topic)
            box.addView(button("Choose discovered topic") { chooseTopic(topic, cap.messageType) })
            box.addView(text(if (cap.kind == 8) "Maximum frame rate · 0.2–5 fps" else "Maximum publish rate · 0.2–100 Hz", 12, muted))
            val rate = field(prefs.getString("${cap.id}.rate", cap.defaultRate.toString())!!, "rate:${cap.id}")
            rate.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            box.addView(rate)
            val state = text("Stopped", 13, muted)
            val toggle = button("Publish") { togglePublisher(cap) }.apply { contentDescription = "toggle:${cap.id}" }
            box.addView(toggle); box.addView(state); rows[cap.id] = Row(topic, rate, toggle, state)
        }
    }
    private fun subscriberCard() {
        card("Display / debug text") { box ->
            box.addView(text("std_msgs/msg/String · reliable, volatile QoS", 12, accent))
            box.addView(text("Incoming text appears here. Latest message shown, limited to 4,096 bytes.", 13, muted))
            val topic = field(prefs.getString("display.topic", "/phone/display/text")!!, "topic:display")
            box.addView(topic)
            box.addView(button("Choose discovered topic") { chooseTopic(topic, "std_msgs/msg/String") })
            val state = text("Stopped", 13, muted)
            val toggle = button("Subscribe") { toggleDisplay() }.apply { contentDescription = "toggle:display" }
            box.addView(toggle); box.addView(state); rows["display"] = Row(topic, null, toggle, state)
            display = text("$received\n\n$receivedCount received update(s)", 22).apply { contentDescription = "display_message" }
            box.addView(display)
        }
    }
    private fun connectionCard() {
        card("ROS connection profile") { box ->
            box.addView(text("Publish or Subscribe starts the native node. Stop all before changing this profile. Use the same domain on your PC.", 13, muted))
            listOf(
                Triple("namespace", "Namespace", "/phone"), Triple("domain", "Domain ID · 0–101", "0"),
                Triple("peer", "PC IPv4 · optional discovery peer", ""), Triple("local", "Phone IPv4 · optional interface selection", "")
            ).forEach { (key, label, fallback) ->
                box.addView(text(label, 12, muted))
                field(prefs.getString(key, fallback)!!, key).also { profileFields[key] = it; box.addView(it) }
            }
            box.addView(button("Save profile") {
                runCatching { arguments(); save() }.onSuccess { status.text = "Profile saved" }.onFailure { status.text = it.message }
            })
        }
        card("Network / USB") { box ->
            box.addView(text(DeviceDiagnostics.networks(), 14))
            box.addView(text("USB to the PC uses USB tethering as an IP link. USB debugging alone does not carry DDS traffic. Enable tethering on the phone and select its USB IP address if needed.", 13, muted))
            box.addView(text(DeviceDiagnostics.usb(this), 13, muted))
        }
        card("Runtime") { box ->
            box.addView(text(NativeRuntime.diagnostic(), 13, accent)); box.addView(text(DeviceDiagnostics.phone(), 13, muted))
            box.addView(text("v0.1: PC ↔ phone\nv0.2: microcontroller ↔ phone\nEndpoints stop when you leave the app. Microphone and other sensor adapters follow later.", 13, muted))
        }
    }
    private data class Arguments(val profile: SessionConfig, val peer: String, val local: String)
    private fun arguments(): Arguments {
        fun value(key: String, fallback: String) = profileFields[key]?.text?.toString()?.trim() ?: prefs.getString(key, fallback)!!
        fun validIp(v: String) = v.isEmpty() || (v.split('.').size == 4 && v.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true })
        val peer = value("peer", ""); val local = value("local", "")
        require(validIp(peer) && validIp(local)) { "Enter IPv4 addresses or leave the address fields blank." }
        return Arguments(SessionConfig(value("namespace", "/phone"), value("domain", "0").toIntOrNull() ?: -1), peer, local)
    }
    private fun ensureRuntime(args: Arguments) {
        if (connected) return
        check(NativeRuntime.available) { NativeRuntime.diagnostic() }
        NativeRuntime.start(args.profile.domainId, args.profile.namespace, args.peer, args.local, applicationInfo.nativeLibraryDir)
        connected = true
    }
    private fun chooseTopic(field: EditText, type: String) {
        if (busy || !field.isEnabled) return
        runCatching {
            val args = arguments(); val token = generation
            busy = true; refresh(); acquireMulticast(); status.text = "Discovering ROS topics…"
            worker.execute {
                val result = runCatching { ensureRuntime(args); NativeRuntime.topics().filter { it.substringAfter('|') == type }.map { it.substringBefore('|') }.distinct().sorted() }
                main.post {
                    if (token != generation || !resumed) return@post
                    busy = false; refresh()
                    result.onSuccess { names ->
                        if (names.isEmpty()) status.text = "No $type topics discovered yet. Enter a topic manually, or retry after discovery."
                        else AlertDialog.Builder(this).setTitle("Choose $type topic")
                            .setItems(names.toTypedArray()) { _, which -> field.setText(names[which]) }
                            .setNegativeButton("Cancel", null).show()
                    }.onFailure { status.text = it.message }
                }
            }
        }.onFailure { status.text = it.message }
    }
    private fun togglePublisher(cap: PhoneCapability) {
        if (cap.id in active) { stopPublisher(cap.id); return }
        if (busy) return
        runCatching {
            val row = rows.getValue(cap.id)
            val config = EndpointConfig(row.topic.text.toString().trim(), row.rate?.text?.toString()?.toDoubleOrNull() ?: 0.0)
            require(active.none { prefs.getString("$it.topic", "") == config.topic }) { "Another publisher already uses this topic." }
            require(!subscribed || prefs.getString("display.topic", "") != config.topic) { "The display subscriber uses this topic with another type." }
            require(cap.kind != 8 || config.rateHz <= 5) { "Camera is limited to 5 fps in v0.1." }
            require(cap.kind != 8 || catalog.none { it.kind == 8 && it.id in active }) { "Stop the other camera first." }
            save()
            if (cap.kind == 8 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingCamera = cap to config; requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            } else startPublisher(cap, config)
        }.onFailure { errors[cap.id] = it.message ?: "Invalid endpoint"; refresh() }
    }
    private fun startPublisher(cap: PhoneCapability, config: EndpointConfig) {
        val args = arguments(); val token = generation
        busy = true; refresh(); acquireMulticast(); status.text = "Starting ROS publisher…"
        worker.execute {
            val result = runCatching { ensureRuntime(args); NativeRuntime.createPublisher(cap.id, config.topic, cap.kind) }
            main.post {
                if (token != generation || !resumed) return@post
                busy = false
                result.onSuccess {
                    runCatching {
                        if (cap.kind == 8) camera.start(cap, config.rateHz) else sensors.start(cap, config)
                        active += cap.id; errors.remove(cap.id)
                    }.onFailure {
                        errors[cap.id] = it.message ?: "Sensor start failed"
                        worker.execute { NativeRuntime.removePublisher(cap.id) }
                    }
                }.onFailure { errors[cap.id] = it.message ?: "ROS start failed"; status.text = errors[cap.id] }
                refresh()
            }
        }
    }
    private fun stopPublisher(id: String) {
        sensors.stop(id)
        if (catalog.any { it.id == id && it.kind == 8 }) camera.stop()
        active -= id; worker.execute { if (NativeRuntime.available) NativeRuntime.removePublisher(id) }; refresh()
    }
    private fun toggleDisplay() {
        if (busy) return
        if (subscribed) { subscribed = false; worker.execute { NativeRuntime.unsubscribeDisplay() }; refresh(); return }
        runCatching {
            val config = EndpointConfig(rows.getValue("display").topic.text.toString().trim(), 1.0)
            require(active.none { prefs.getString("$it.topic", "") == config.topic }) { "A publisher already uses this topic." }
            val args = arguments(); val token = generation
            save(); busy = true; refresh(); acquireMulticast()
            worker.execute {
                val result = runCatching { ensureRuntime(args); NativeRuntime.subscribeDisplay(config.topic) }
                main.post {
                    if (token != generation || !resumed) return@post
                    busy = false
                    result.onSuccess { subscribed = true; errors.remove("display") }.onFailure { errors["display"] = it.message ?: "Subscribe failed" }
                    refresh()
                }
            }
        }.onFailure { errors["display"] = it.message ?: "Invalid endpoint"; refresh() }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 10) return
        val pending = pendingCamera; pendingCamera = null
        if (pending != null && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            main.post { runCatching { startPublisher(pending.first, pending.second) }.onFailure { status.text = it.message } }
        } else status.text = "Camera permission denied; other sensors remain available."
    }
    private fun stopSession() {
        generation++; busy = true
        val token = generation
        sensors.stopAll(); camera.stop(); active.clear(); subscribed = false
        worker.execute {
            if (NativeRuntime.available) NativeRuntime.stop()
            connected = false
            main.post { if (token == generation) { busy = false; refresh() } }
        }
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        if (::status.isInitialized) { status.text = "Stopped · hardware and ROS endpoints released"; refresh() }
    }
    private fun acquireMulticast() {
        if (multicast?.isHeld == true) return
        multicast = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("RobotBrainDDS").apply { setReferenceCounted(false); acquire() }
    }
    private fun refresh() {
        rows.forEach { (id, row) ->
            val enabled = id in active || (id == "display" && subscribed)
            row.button.text = if (enabled) "Stop" else if (id == "display") "Subscribe" else "Publish"
            row.button.isEnabled = !busy && (id == "display" || catalog.first { it.id == id }.available)
            row.topic.isEnabled = !enabled && !busy; row.rate?.isEnabled = !enabled && !busy
            row.state.text = errors[id] ?: if (enabled) (if (id == "display") "Subscribed" else "Publishing") else "Stopped"
        }
        profileFields.values.forEach { it.isEnabled = !connected && !busy }
    }
    private fun save() {
        val edit = prefs.edit()
        rows.forEach { (id, row) -> edit.putString("$id.topic", row.topic.text.toString().trim()); row.rate?.let { edit.putString("$id.rate", it.text.toString()) } }
        profileFields.forEach { (key, value) -> edit.putString(key, value.text.toString().trim()) }; edit.apply()
    }
    private fun card(title: String, content: (LinearLayout) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.rgb(26, 37, 46)); cornerRadius = dp(16).toFloat() }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        page.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }); box.addView(text(title, 19)); content(box)
    }
    private fun field(value: String, description: String) = EditText(this).apply {
        setSingleLine(); setTextColor(Color.WHITE); setText(value); contentDescription = description
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun text(value: String, size: Int, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(4), 0, dp(4)); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
