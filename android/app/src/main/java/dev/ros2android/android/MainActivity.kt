package dev.ros2android.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ServiceConnection
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*

open class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("endpoints_v1", MODE_PRIVATE) }
    private var dashboard: ControlDashboard? = null
    private var session: RobotSessionService? = null
    private var bound = false
    private val offlineErrors = mutableMapOf<String, String>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            session = (binder as RobotSessionService.LocalBinder).session
            session?.errors?.putAll(offlineErrors); offlineErrors.clear()
            session?.onChange = { if (resumed) refresh() }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) { session = null; refresh() }
    }
    private lateinit var catalog: List<PhoneCapability>
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private var resumed = false
    private val busy: Boolean get() = session?.busy ?: true
    private val connected: Boolean get() = session?.connected ?: false
    private val active: Set<String> get() = session?.active ?: emptySet()
    private var tab = "Overview"
    private var overview: TextView? = null
    private var stopButton: Button? = null
    private var feedback: TextView? = null
    private var preview: ImageView? = null
    private var previewId: String? = null
    private var previewStamp = 0L
    private var pageScroll: ScrollView? = null
    private val expanded = mutableSetOf<String>()
    private val navButtons = mutableMapOf<String, Button>()
    private val ticker = object : Runnable {
        override fun run() { if (resumed) { refresh(); if (tab == "Projects" || tab == "Overview") projectControllers.values.toList().forEach { it.poll() }; main.postDelayed(this, 500) } }
    }
    private val projectControllers = mutableMapOf<String, ProjectController>()
    private data class ProjectRow(val state: TextView, val nodes: TextView, val toggle: Button, val settings: Button, val logs: TextView?)
    private val projectRows = mutableMapOf<String, ProjectRow>()
    private fun closeProjects() { projectControllers.values.forEach { it.close() }; projectControllers.clear() }
    private fun updateProjects() {
        projectRows.forEach { (pkg, row) ->
            val control = projectControllers[pkg] ?: return@forEach
            val value = control.status
            val state = value?.optString("state") ?: "Connecting…"
            val busy = value?.optBoolean("busy") == true
            val running = state == "Running"
            row.state.show(control.error ?: control.commandError ?: state)
            row.toggle.show(if (running) "Stop project" else "Start project")
            row.toggle.isEnabled = value != null && control.error == null && !busy
            row.settings.isEnabled = value != null && control.error == null
            val nodes = value?.optJSONObject("native")?.optJSONArray("nodes")
            row.nodes.show(if (running && nodes != null) (0 until nodes.length()).joinToString("\n") { nodes.getString(it) } else "")
            row.nodes.visibility = if (row.nodes.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            row.logs?.show(value?.optString("logs")?.ifEmpty { "No captured output yet" } ?: "Waiting for runner")
        }
    }
    private fun projectSettings(control: ProjectController) {
        val config = control.status?.optJSONObject("config") ?: return
        val editable = control.status?.let { !it.optBoolean("busy") && it.optString("state") != "Running" } == true
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        val fields = linkedMapOf<String, EditText>()
        for ((key, label) in linkedMapOf("domain" to "ROS domain", "namespace" to "Namespace", "peer" to "PC IPv4 (optional)", "local" to "Phone IPv4 (optional)", "ros_args" to "ROS arguments (JSON array)")) {
            content.addView(text(label, 13, muted))
            fields[key] = field(config.optString(key), label).also { it.isEnabled = editable; content.addView(it) }
        }
        val dialog = AlertDialog.Builder(this).setTitle("Project connection").setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = editable; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val domain = fields.getValue("domain").text.toString().toIntOrNull()
            if (domain == null || domain !in 0..101) { fields.getValue("domain").error = "Use 0–101"; return@setOnClickListener }
            control.send(4, Bundle().apply { fields.forEach { (key, field) -> putString(key, field.text.toString()) } }); dialog.dismiss()
        } }
        dialog.show()
    }
    private var group = "All"
    private var pendingCamera: Pair<PhoneCapability, EndpointConfig>? = null
    private data class Row(val topic: EditText, val rate: EditText?, val button: Button, val state: TextView, val summary: TextView? = null)
    private val rows = mutableMapOf<String, Row>()
    private val profileFields = mutableMapOf<String, EditText>()
    private val errors: MutableMap<String, String> get() = session?.errors ?: offlineErrors
    private val accent get() = getColor(R.color.ros_accent)
    private val muted get() = getColor(R.color.ros_muted)
    private val ink get() = getColor(R.color.ros_text)
    private var selectedSensor: String? = null
    private lateinit var actionHost: LinearLayout
    private var homeConnection: TextView? = null
    private var homeNetworks: TextView? = null
    private var homeNetworkStamp = 0L
    private var programmingStatus: TextView? = null
    private var programmingStop: Button? = null
    private var agentStatus: TextView? = null
    private var agentToggle: Button? = null
    private var agentLogs: TextView? = null
    private val capabilityStates = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                else window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else window.navigationBarColor = getColor(R.color.ros_accent)
        if (!prefs.contains("namespace")) {
            val old = getSharedPreferences("robot_profile", MODE_PRIVATE)
            prefs.edit().putString("namespace", old.getString("namespace", "/phone"))
                .putString("domain", old.getInt("domain", 0).toString()).apply()
        }
        catalog = CapabilityCatalog.publishers(this)
        if (!prefs.getBoolean("continuous_camera_v1", false)) {
            val edit = prefs.edit().putBoolean("continuous_camera_v1", true)
            catalog.filter { it.kind == 8 }.forEach { cap ->
                if (prefs.getString("${cap.id}.rate", null)?.toDoubleOrNull() == 1.0)
                    edit.putString("${cap.id}.rate", "15.0")
            }
            edit.apply()
        }
        tab = savedInstanceState?.getString("screen")?.let { when (it) { "About" -> "Connect"; "Controls" -> "Dashboard"; else -> it } } ?: "Overview"
        selectedSensor = savedInstanceState?.getString("sensor")
        group = savedInstanceState?.getString("filter") ?: "All"
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded").orEmpty())
        render()
        bound = bindService(Intent(this, RobotSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onResume() { super.onResume(); resumed = true; if (tab == "Projects" || tab == "Overview") render(); main.removeCallbacks(ticker); main.post(ticker) }
    override fun onSaveInstanceState(outState: Bundle) {
        save(); outState.putString("sensor", selectedSensor); outState.putString("screen", tab); outState.putString("filter", group)
        outState.putStringArrayList("expanded", ArrayList(expanded)); super.onSaveInstanceState(outState)
    }
    override fun onPause() { session?.stopDashboardMotion(); save(); closeProjects(); resumed = false; main.removeCallbacks(ticker); session?.cameraPreviewEnabled = false; super.onPause() }
    override fun onDestroy() {
        closeProjects()
        session?.cameraPreviewEnabled = false
        session?.onChange = null
        main.removeCallbacks(ticker)
        if (bound) unbindService(connection)
        session = null
        super.onDestroy()
    }

    @Deprecated("Legacy Activity back callback")
    override fun onBackPressed() {
        when {
            tab == "Sensors" && selectedSensor != null -> { save(); selectedSensor = null; render() }
            tab == "Help" -> navigate("Connect")
            tab != "Overview" -> navigate("Overview")
            else -> super.onBackPressed()
        }
    }
    private fun navigate(screen: String) { if (tab == "Dashboard" && screen != "Dashboard") session?.stopDashboardMotion(); save(); if (screen != "Projects") closeProjects(); tab = screen; render() }
    private fun openSensor(id: String) { save(); selectedSensor = id; tab = "Sensors"; render() }
    private fun openBattery() = openSensor("battery")

    private fun render(keepScroll: Boolean = false) {
        val oldScroll = if (keepScroll) pageScroll?.scrollY ?: 0 else 0
        rows.clear(); profileFields.clear(); capabilityStates.clear(); navButtons.clear()
        dashboard = null
        programmingStatus = null; programmingStop = null
        overview = null; homeConnection = null; homeNetworks = null; homeNetworkStamp = 0L; preview = null; previewId = null; previewStamp = 0
        session?.cameraPreviewEnabled = false
        val selected = catalog.firstOrNull { it.id == selectedSensor }
        val detail = tab == "Sensors" && selected != null
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.ros_bg)) }
        shell.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val p = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(p.left, p.top, p.right, p.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val toolbar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        if (detail || tab == "Help") toolbar.addView(button("‹") {
            if (detail) { save(); selectedSensor = null; render() } else navigate("Connect")
        }.apply { contentDescription = "Back"; tag = "detail_back"; textSize = 26f }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = when { detail -> shortName(selected!!); tab == "Overview" -> getString(R.string.app_name); tab == "Connect" -> "Settings"; tab == "Help" -> "Help"; else -> tab }
        if (tab == "Overview") toolbar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_ros_logo); contentDescription = "ROS2 Android logo" }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
        toolbar.addView(text(title, 20).apply { heading() }, LinearLayout.LayoutParams(0, -2, 1f))
        if (tab == "Projects") toolbar.addView(button("Refresh") { render() }.apply { tag = "refresh_projects" })
        stopButton = button("Stop all") { stopSession() }.apply { tag = "stop_session"; contentDescription = "Stop all phone sensors, subscribers and controls" }
        toolbar.addView(stopButton); shell.addView(toolbar)
        status = text("Loading…", 13, muted).apply { tag = "session_status" }
        if (tab == "Overview") {
            status.setPadding(dp(20), 0, dp(20), dp(8)); shell.addView(status)
        }
        val scroll = ScrollView(this).apply { isFillViewport = false; tag = "page_scroll" }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)) }
        pageScroll = scroll; scroll.addView(page); shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        actionHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(4)) }
        feedback = text("", 14, getColor(R.color.ros_error)).apply { visibility = android.view.View.GONE; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        page.addView(feedback)
        when {
            tab == "Overview" -> homeScreen()
            tab == "Dashboard" -> { dashboard = ControlDashboard(this, { session }, ::arguments); page.addView(dashboard) }
            detail -> publisherDetail(selected!!)
            tab == "Sensors" -> sensorList()
            tab == "Projects" -> projectsScreen()
            tab == "Help" -> helpScreen()
            else -> settingsScreen()
        }
        shell.addView(actionHost)
        val overflow = resources.configuration.screenWidthDp / resources.configuration.fontScale < 360
        val navScroll = HorizontalScrollView(this).apply { isFillViewport = true; isHorizontalScrollBarEnabled = overflow; tag = "navigation_scroll"; setBackgroundColor(getColor(R.color.ros_surface)) }
        val nav = LinearLayout(this).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        listOf(Triple("Overview", "Home", R.drawable.ic_home), Triple("Sensors", "Sensors", R.drawable.ic_sensors), Triple("Dashboard", "Dashboard", R.drawable.ic_dashboard), Triple("Projects", "Projects", R.drawable.ic_projects), Triple("Connect", "Settings", R.drawable.ic_settings)).forEach { (key, label, iconId) ->
            val selectedTab = key == tab || (key == "Connect" && tab == "Help")
            val item = button(label) { navigate(key) }.apply {
                tag = "nav:$key"; isSelected = selectedTab; textSize = 12f; setSingleLine(true); letterSpacing = 0f; ellipsize = android.text.TextUtils.TruncateAt.END; minWidth = 0; minimumWidth = 0; minHeight = dp(64)
                setPadding(dp(4), dp(6), dp(4), dp(6)); compoundDrawablePadding = dp(3)
                setTextColor(if (selectedTab) accent else muted)
                backgroundTintList = ColorStateList.valueOf(if (selectedTab) getColor(R.color.ros_selected) else getColor(R.color.ros_surface))
                val icon = getDrawable(iconId)!!.mutate().apply { setTint(if (selectedTab) accent else muted); setBounds(0, 0, dp(22), dp(22)) }
                setCompoundDrawables(null, icon, null, null)
            }
            navButtons[key] = item
            nav.addView(item, if (overflow) LinearLayout.LayoutParams(-2, -2).apply { item.minWidth = dp(88) } else LinearLayout.LayoutParams(0, -2, 1f))
        }
        navScroll.addView(nav); shell.addView(navScroll); setContentView(shell)
        if (overflow) navScroll.post { navButtons[if (tab == "Help") "Connect" else tab]?.let { navScroll.scrollTo((it.left - (navScroll.width - it.width) / 2).coerceAtLeast(0), 0) } }
        refresh()
        if (oldScroll > 0) scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private var homeProjectsUnavailable = false
    private fun connectHomeProjects() {
        val projects = runCatching { InstalledProjects.list(this) }
        homeProjectsUnavailable = projects.isFailure
        val installed = projects.getOrDefault(emptyList())
        val packages = installed.map { it.packageName }.toSet()
        projectControllers.keys.filter { it !in packages }.forEach { projectControllers.remove(it)?.close() }
        installed.forEach { project ->
            projectControllers.getOrPut(project.packageName) {
                ProjectController(this, project) { updateProjects(); refreshHomeActivity() }
            }.connect()
        }
    }
    private fun refreshHomeActivity() {
        val running = projectControllers.values.count { it.error == null && it.status?.optString("state") == "Running" }
        val unknown = projectControllers.values.count { it.error != null || it.status == null }
        val nodes = projectControllers.values.filter { it.error == null && it.status?.optString("state") == "Running" }
            .sumOf { it.status?.optJSONObject("native")?.optJSONArray("nodes")?.length() ?: 0 }
        overview?.show(buildString {
            append("Sensor topics: ${active.size} publishing")
            val robot = session
            append("\nDashboard topics: ${robot?.dashboardPublisherCount ?: 0} publishing · ${robot?.dashboardSubscriberCount ?: 0} subscribed")
            append(if (homeProjectsUnavailable) "\nProjects: status unavailable" else "\nProjects: $running running / ${projectControllers.size} installed · $nodes nodes")
            if (unknown > 0) append(" · $unknown awaiting status")
            append("\nMicro-ROS agent: ${MicroRosAgentService.state}")
        })
    }
    private fun homeScreen() {
        connectHomeProjects()
        card("Connection") { box ->
            homeConnection = text("", 15).apply { tag = "home_connection"; setTextIsSelectable(true) }.also { box.addView(it) }
            homeNetworks = text("", 14, muted).apply { tag = "home_networks"; setTextIsSelectable(true) }.also { box.addView(it) }
            box.addView(button("Connection settings") { navigate("Connect") }.apply { tag = "home_connection_settings" })
        }
        card("ROS activity") { box ->
            overview = text("", 15, muted).apply { tag = "home_endpoints" }.also { box.addView(it) }
        }
        disclosure(page, "home_session", "Session details") { box ->
            box.addView(text("On-device publishing only. Check your PC for received messages.", 14, muted))
            box.addView(text(NativeRuntime.status(), 13, muted))
        }
    }
    private fun shortName(cap: PhoneCapability): String = when (cap.id) { "imu" -> "IMU"; "battery" -> "Battery percentage"; "orientation" -> "Orientation"; else -> cap.label.substringBefore(" ·") }
    private fun sensorList() {
        if (group !in listOf("All", "Motion", "Cameras", "Other")) group = "All"
        val filters = LinearLayout(this)
        listOf("All", "Motion", "Cameras", "Other").forEach { name -> filters.addView(button(name) { save(); group = name; render() }.apply {
            tag = "filter:$name"; isSelected = name == group
            backgroundTintList = ColorStateList.valueOf(if (isSelected) getColor(R.color.ros_selected) else getColor(R.color.ros_bg))
        }) }
        page.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(filters) })
        catalog.filter { when (group) { "Motion" -> it.kind in listOf(0, 1, 2, 7); "Cameras" -> it.kind == 8; "Other" -> it.kind in 3..6 || it.kind == 9; else -> true } }.forEach { cap ->
            val row = listRow(shortName(cap), if (cap.available) "Ready" else "Unavailable", "sensor:${cap.id}", when (cap.kind) { 8 -> R.drawable.ic_camera; 9 -> R.drawable.ic_battery; else -> R.drawable.ic_sensors }) { openSensor(cap.id) }
            capabilityStates[cap.id] = row
        }
    }
    private fun publisherDetail(cap: PhoneCapability) {
        card("") { box ->
            box.tag = "sensor:${cap.id}"
            if (!cap.available) box.addView(text("Not available on this phone", 15, muted))
            if (cap.kind == 8) {
                previewId = cap.id
                preview = ImageView(this).apply { contentDescription = "Local preview of ${shortName(cap)}"; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(getColor(R.color.ros_bg)) }
                box.addView(preview, LinearLayout.LayoutParams(-1, dp(180)))
            }
            box.addView(text("Topic", 13, muted))
            val topic = field(prefs.getString("${cap.id}.topic", cap.defaultTopic)!!, "topic:${cap.id}").apply { contentDescription = "ROS topic for ${shortName(cap)}" }; box.addView(topic)
            box.addView(text(if (cap.kind == 8) "Publish rate (fps)" else "Publish rate (Hz)", 13, muted))
            val rate = field(prefs.getString("${cap.id}.rate", cap.defaultRate.toString())!!, "rate:${cap.id}").apply {
                contentDescription = "Publish rate for ${shortName(cap)}"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }; box.addView(rate)
            val state = text("", 14, muted); box.addView(state)
            val publish = primaryButton("Publish") { togglePublisher(cap) }; actionHost.addView(publish)
            rows[cap.id] = Row(topic, rate, publish, state)
            disclosure(box, "sensor_info:${cap.id}", "Advanced") { info ->
                info.addView(text(cap.messageType, 13, muted)); info.addView(text(cap.detail, 14, muted))
                info.addView(button("Find matching topics") { chooseTopic(topic, cap.messageType) })
                info.addView(text("Best effort · volatile QoS", 13, muted))
                if (cap.kind == 8) info.addView(text("Preview: up to 2 fps, sensor orientation. ROS publishing has its own rate. Images are not saved.", 13, muted))
            }
        }
    }
    private fun settingsScreen() {
        card("Connection") { box ->
            if (connected) box.addView(text("Stop the session to edit.", 13, muted))
            box.addView(text("ROS domain", 13, muted))
            val domain = field(prefs.getString("domain", "0")!!, "domain").apply { contentDescription = "ROS domain ID, zero to 101"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            profileFields["domain"] = domain; box.addView(domain)
            disclosure(box, "connection_advanced", "Advanced connection") { info ->
                listOf(Triple("namespace", "Namespace", "/phone"), Triple("peer", "PC IPv4 (optional)", ""), Triple("local", "Phone IPv4 (optional)", "")).forEach { (key, label, fallback) ->
                    info.addView(text(label, 13, muted)); val value = field(prefs.getString(key, fallback)!!, key).apply { contentDescription = label }
                    profileFields[key] = value; info.addView(value)
                }
            }
            box.addView(button("Save") { runCatching { arguments(); save() }.onSuccess { announce("Connection saved") }.onFailure { announce(it.message) } })
        }
        disclosure(page, "network_info", "Network addresses") { box -> box.addView(text(DeviceDiagnostics.networks(), 14)); box.addView(text(DeviceDiagnostics.usb(this), 13, muted)) }
        disclosure(page, "runtime_info", "Runtime diagnostics") { box -> box.addView(text(NativeRuntime.diagnostic(), 13, muted)); box.addView(text(DeviceDiagnostics.phone(), 13, muted)) }
        disclosure(page, "micro_ros_agent", "Microcontrollers") { box ->
            programmingStatus = text("", 14).apply { tag = "usb_programming_status" }.also { box.addView(it) }
            programmingStop = button("Stop USB programming") {
                startService(Intent(this, Esp32ProgrammingService::class.java).setAction(Esp32ProgrammingService.STOP))
            }.apply { tag = "stop_usb_programming" }.also { box.addView(it) }
            agentStatus = text(MicroRosAgentService.state, 14).also { box.addView(it) }
            agentToggle = button("Start Wi-Fi agent") {
                if (MicroRosAgentService.running) stopService(Intent(this, MicroRosAgentService::class.java))
                else startForegroundService(Intent(this, MicroRosAgentService::class.java))
            }.apply { tag = "toggle_micro_ros_agent" }; box.addView(agentToggle)
            box.addView(button("Start USB agent") {
                if (MicroRosAgentService.running) announce("Stop the current agent before changing transport")
                else startForegroundService(Intent(this, MicroRosAgentService::class.java).putExtra(MicroRosAgentService.USB, true))
            }.apply { tag = "start_usb_micro_ros_agent" })
            box.addView(text("Wi-Fi: UDP 8888. USB: CP2102 ESP32 with serial firmware and OTG cable. Use the same ROS domain as the sensors.", 13, muted))
            disclosure(box, "micro_ros_logs", "Agent logs") { info ->
                agentLogs = text(MicroRosAgentService.logs(), 12, muted).apply { setTextIsSelectable(true) }; info.addView(agentLogs)
            }
        }
        listRow("Appearance", "Device light / dark mode", "appearance", R.drawable.ic_settings, null)
        listRow("Help", "", "nav:Help", R.drawable.ic_display) { navigate("Help") }
        aboutSection()
    }
    private fun projectsScreen() {
        projectRows.clear()
        val projects = runCatching { InstalledProjects.list(this) }.getOrElse { announce("Cannot load projects: ${it.message}"); emptyList() }
        val packages = projects.map { it.packageName }.toSet()
        projectControllers.keys.filter { it !in packages }.forEach { projectControllers.remove(it)?.close() }
        if (projects.isEmpty()) card("No projects yet") { box -> box.addView(text("Deploy a runner from your PC.", 14, muted)) }
        projects.forEach { project -> card(project.id.replace('_', ' ')) { box ->
            val control = projectControllers.getOrPut(project.packageName) { ProjectController(this, project) { updateProjects() } }
            val state = text("Connecting…", 15).apply { tag = "project_state:${project.id}" }; box.addView(state)
            val nodes = text("", 13, muted); box.addView(nodes)
            val toggle = primaryButton("Start project") { control.send(if (control.status?.optString("state") == "Running") 3 else 2) }.apply {
                tag = "toggle_project:${project.id}"; contentDescription = "Start or stop ${project.id}"
            }; box.addView(toggle)
            val settings: Button
            var logs: TextView? = null
            // Keep the connection action accessible without filling the card with fields.
            settings = button("Connection settings") { projectSettings(control) }; box.addView(settings)
            disclosure(box, "logs:${project.id}", "Logs") { info ->
                logs = text(control.status?.optString("logs") ?: "Waiting for runner", 12, muted).apply { tag = "project_logs:${project.id}"; setTextIsSelectable(true) }; info.addView(logs)
            }
            disclosure(box, "project:${project.id}", "Details") { info ->
                info.addView(text("${project.runtimeLabel}\n${project.packageName}\nSource ${project.sourceId}", 12, muted))
                info.addView(button("Open runner") { runCatching { save(); startActivity(project.openIntent()) }.onFailure { announce("Could not open project. Refresh and try again.") } }.apply { tag = "open_project:${project.id}" })
            }
            projectRows[project.packageName] = ProjectRow(state, nodes, toggle, settings, logs)
            control.connect()
        } }
        updateProjects()
        disclosure(page, "project_deploy", "Deploy a project") { box ->
            box.addView(text("On your PC, from the repository folder:", 14, muted))
            box.addView(text("./tools/ros2android-workspace --workspace examples/android_test_ws build\n./tools/ros2android-workspace --workspace examples/android_test_ws deploy", 13).apply { setTextIsSelectable(true) })
            box.addView(text("Full guide: docs/COLCON-WORKSPACES.md", 13, muted))
        }
    }
    private fun helpScreen() {
        disclosure(page, "help_connection", "Connect your PC") { box ->
            box.addView(text("USB: enable USB tethering. USB debugging alone does not carry ROS topics.\n\nWi-Fi: use a network that permits traffic between the phone and PC.\n\nMatch the ROS domain in Settings. Set current IPv4 addresses under Advanced connection if automatic discovery fails.", 14, muted))
            box.addView(button("Connection settings") { navigate("Connect") })
        }
        disclosure(page, "help_battery_guide", "Your first message") { box ->
            box.addView(text("Publish battery percentage, then run these commands on your Linux PC. They match your saved domain and topic.", 14, muted))
            val commands = runCatching { batteryCommands() }
            box.addView(text(commands.getOrElse { "Save a valid domain and battery topic first." }, 13).apply { tag = "battery_pc_commands"; setTextIsSelectable(true) })
            box.addView(button("Copy commands") { commands.onSuccess { value ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("ROS2 Android", value)); announce("Commands copied")
            } }.apply { isEnabled = commands.isSuccess })
            box.addView(button("Open battery publisher") { openBattery() }.apply { tag = "help_battery" })
        }
        disclosure(page, "help_camera", "Camera & display") { box -> box.addView(text("Sensors → Cameras: select a camera, allow camera permission, then Publish.\n\nDashboard → Add widget → Subscribe: choose std_msgs/msg/String and a topic, then Start dashboard to show text.\n\nStop all releases this app's sensors. Project runners have a separate Stop project control.", 14, muted)) }
        disclosure(page, "help_troubleshooting", "Troubleshooting") { box -> box.addView(text("No topic: tap Publish first. Check the domain, topic and network addresses.\n\nCamera denied: allow Camera for ROS2 Android in app permissions, then retry.\n\nLocked settings: stop the session before editing.\n\nA local sent count does not confirm delivery. Check samples on the PC.", 14, muted)) }
    }
    private fun aboutSection() {
        card("") { box ->
            box.addView(ImageView(this).apply { setImageResource(R.drawable.ic_ros_logo); contentDescription = null }, LinearLayout.LayoutParams(dp(40), dp(40)))
            box.addView(text(getString(R.string.app_name), 18))
            @Suppress("DEPRECATION")
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            box.addView(text("Version $version", 14, muted))
            box.addView(text(getString(R.string.about_credit), 17).apply { tag = "about_credit" })
            box.addView(text(getString(R.string.about_support_description), 14, muted))
            box.addView(button(getString(R.string.about_support_button)) {
                openWebLink(getString(R.string.sponsor_url))
            })
        }
    }
    private fun openWebLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { announce(getString(R.string.about_no_browser)) }
    }
    private fun announce(message: String?) { feedback?.apply { show(message ?: "Unknown error"); visibility = android.view.View.VISIBLE } }
    private fun batteryCommands(): String {
        val domain = prefs.getString("domain", "0")?.toIntOrNull() ?: -1
        SessionConfig(prefs.getString("namespace", "/phone")!!, domain)
        val endpoint = EndpointConfig(prefs.getString("battery.topic", "/phone/battery_percent")!!, 1.0)
        return "source /opt/ros/jazzy/setup.bash\nexport ROS_DOMAIN_ID=$domain\nros2 topic list -t\nros2 topic echo ${endpoint.topic} std_msgs/msg/Float64 --qos-reliability best_effort"
    }

    private fun arguments(): RobotSessionService.Profile {
        fun value(key: String, fallback: String) = profileFields[key]?.text?.toString()?.trim() ?: prefs.getString(key, fallback)!!
        fun validIp(v: String) = v.isEmpty() || (v.split('.').size == 4 && v.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true })
        val peer = value("peer", ""); val local = value("local", "")
        require(validIp(peer) && validIp(local)) { "Enter IPv4 addresses or leave the address fields blank." }
        return RobotSessionService.Profile(SessionConfig(value("namespace", "/phone"), value("domain", "0").toIntOrNull() ?: -1), peer, local)
    }
    private fun chooseTopic(field: EditText, type: String) {
        if (busy || !field.isEnabled) return
        runCatching {
            val args = arguments()
            session?.discover(type, args) { result ->
                if (isFinishing || isDestroyed || !resumed || !field.isAttachedToWindow) return@discover
                result.onSuccess { names ->
                    if (names.isEmpty()) announce("No matching topics discovered. Check the connection, enter a topic manually or retry.")
                    else AlertDialog.Builder(this).setTitle("Choose $type topic")
                        .setItems(names.toTypedArray()) { _, which -> field.setText(names[which]) }
                        .setNegativeButton("Cancel", null).show()
                }.onFailure { announce(it.message) }
            }
        }.onFailure { announce(it.message) }
    }
    private fun togglePublisher(cap: PhoneCapability) {
        if (cap.id in active) { stopPublisher(cap.id); return }
        if (busy) return
        runCatching {
            val row = rows.getValue(cap.id)
            val config = EndpointConfig(row.topic.text.toString().trim(), row.rate?.text?.toString()?.toDoubleOrNull() ?: 0.0)
            require(active.none { prefs.getString("$it.topic", "") == config.topic }) { "Another publisher already uses this topic." }
            require(cap.kind != 9 || config.rateHz <= 1) { "Choose a battery rate between 0.2 and 1 Hz." }
            require(cap.kind != 8 || config.rateHz <= 30) { "Choose a camera rate between 0.2 and 30 fps." }
            require(cap.kind != 8 || catalog.none { it.kind == 8 && it.id in active }) { "Stop the other camera first." }
            save()
            if (cap.kind == 8 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingCamera = cap to config; requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            } else startPublisher(cap, config)
        }.onFailure { errors[cap.id] = it.message ?: "Invalid endpoint"; announce(it.message); refresh() }
    }
    private fun startPublisher(cap: PhoneCapability, config: EndpointConfig) {
        session?.startPublisher(cap, config, arguments())
    }
    private fun stopPublisher(id: String) { session?.stopPublisher(id) }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 10) return
        val pending = pendingCamera; pendingCamera = null
        if (pending != null && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            main.post { runCatching { startPublisher(pending.first, pending.second) }.onFailure { announce(it.message) } }
        } else {
            pending?.let { errors[it.first.id] = "Camera permission denied. Allow Camera for ROS2 Android, then retry Publish." }
            announce("Camera permission denied; other sensors remain available."); refresh()
        }
    }
    private fun stopSession() { session?.stopSession() }
    private fun refresh() {
        dashboard?.refresh()
        programmingStatus?.apply {
            show("USB programming: ${Esp32ProgrammingService.state}")
            visibility = if (Esp32ProgrammingService.active || Esp32ProgrammingService.state.startsWith("Failed")) android.view.View.VISIBLE else android.view.View.GONE
        }
        programmingStop?.visibility = if (Esp32ProgrammingService.active) android.view.View.VISIBLE else android.view.View.GONE
        agentStatus?.show(MicroRosAgentService.state)
        agentToggle?.show(if (MicroRosAgentService.running) "Stop agent" else "Start Wi-Fi agent")
        agentLogs?.show(MicroRosAgentService.logs())
        session?.cameraPreviewEnabled = resumed && preview != null && previewId in active
        rows.forEach { (id, row) ->
            val enabled = id in active
            row.button.show(if (enabled) "Stop" else "Publish")
            val available = catalog.first { it.id == id }.available
            row.button.isEnabled = !busy && available
            row.topic.isEnabled = available && !enabled && !busy; row.rate?.isEnabled = available && !enabled && !busy
            val description = "${row.button.text} ${shortName(catalog.first { it.id == id })}"
            if (row.button.contentDescription != description) row.button.contentDescription = description
            row.button.tag = "toggle:$id"
            val state = errors[id] ?: when {
                !available -> "Unavailable"
                !enabled -> "Stopped"
                id == "battery" && selectedSensor == "battery" && tab == "Sensors" -> "${session?.batteryPercent ?: "—"}% · Publishing"
                else -> "Publishing"
            }
            row.state.show(state); row.state.setTextColor(if (id in errors) getColor(R.color.ros_error) else muted)
        }
        capabilityStates.forEach { (id, label) -> label.show(when {
            id in errors -> "Needs attention"
            id in active -> "Publishing"
            catalog.first { it.id == id }.available -> "Ready"
            else -> "Unavailable"
        }) }
        profileFields.values.forEach { it.isEnabled = !connected && !busy }
        status.show(when { session == null -> "Loading…"; busy -> session?.state ?: "Working…"; connected -> "On-device runtime active"; else -> "Phone runtime stopped" })
        refreshHomeActivity()
        homeConnection?.show(buildString {
            append("ROS domain ${prefs.getString("domain", "0")} · ${prefs.getString("namespace", "/phone")}")
            val peer = prefs.getString("peer", "").orEmpty().trim()
            val local = prefs.getString("local", "").orEmpty().trim()
            append(if (peer.isEmpty()) "\nDiscovery: automatic" else "\nDiscovery peer: $peer")
            if (local.isNotEmpty()) append("\nSelected phone address: $local")
        })
        if (homeNetworks != null && (homeNetworkStamp == 0L || SystemClock.elapsedRealtime() - homeNetworkStamp >= 5000)) {
            homeNetworks?.show("Phone IPv4 addresses\n${DeviceDiagnostics.networks(ipv4Only = true)}")
            homeNetworkStamp = SystemClock.elapsedRealtime()
        }
        stopButton?.apply { isEnabled = session != null && !busy && (connected || errors.isNotEmpty()); visibility = if (connected || busy || errors.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE }
        val frame = session?.cameraPreview
        if (previewId !in active) { if (previewStamp != 0L) preview?.setImageDrawable(null); previewStamp = 0 }
        else if (frame != null && frame.id == previewId && frame.stamp != previewStamp) { preview?.setImageBitmap(frame.bitmap); previewStamp = frame.stamp }
    }
    private fun save() {
        val edit = prefs.edit()
        rows.forEach { (id, row) -> edit.putString("$id.topic", row.topic.text.toString().trim()); row.rate?.let { edit.putString("$id.rate", it.text.toString()) } }
        profileFields.forEach { (key, value) -> edit.putString(key, value.text.toString().trim()) }; edit.apply()
    }
    private fun surface() = GradientDrawable().apply { setColor(getColor(R.color.ros_surface)); cornerRadius = dp(12).toFloat() }
    private fun card(title: String, content: (LinearLayout) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = surface(); setPadding(dp(16), dp(12), dp(16), dp(12)) }
        page.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (title.isNotEmpty()) box.addView(text(title, 16).apply { heading() })
        content(box)
    }
    private fun disclosure(parent: LinearLayout, key: String, title: String, content: (LinearLayout) -> Unit) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = surface() }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "body:$key"; setPadding(dp(12), 0, dp(12), dp(12)); visibility = if (key in expanded) android.view.View.VISIBLE else android.view.View.GONE }
        val toggle = button("") {}.apply { tag = "expand:$key"; gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; backgroundTintList = ColorStateList.valueOf(getColor(R.color.ros_surface)) }
        fun update() {
            toggle.show("$title    ${if (key in expanded) "⌃" else "⌄"}")
            toggle.contentDescription = "$title, ${if (key in expanded) "expanded" else "collapsed"}"
            if (Build.VERSION.SDK_INT >= 30) toggle.stateDescription = if (key in expanded) "Expanded" else "Collapsed"
        }
        update(); toggle.setOnClickListener {
            save(); if (!expanded.add(key)) expanded.remove(key)
            body.visibility = if (key in expanded) android.view.View.VISIBLE else android.view.View.GONE; update()
        }
        container.addView(toggle); content(body); container.addView(body)
        parent.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun listRow(title: String, subtitle: String, key: String, iconId: Int, action: (() -> Unit)?): TextView {
        val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = dp(72); setPadding(dp(12), dp(8), dp(12), dp(8)); background = surface(); tag = key }
        row.addView(ImageView(this).apply { setImageResource(iconId); imageTintList = ColorStateList.valueOf(muted); importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24), dp(24)))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
        labels.addView(text(title, 16))
        val secondary = text(subtitle, 13, muted); if (subtitle.isNotEmpty()) labels.addView(secondary)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            row.addView(text("›", 22, muted)); row.isClickable = true; row.isFocusable = true
            row.setOnClickListener { action() }
            val ripple = getDrawable(android.R.drawable.list_selector_background); row.foreground = ripple
        }
        page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }); return secondary
    }
    private fun field(value: String, description: String) = EditText(this).apply {
        setSingleLine(); setTextColor(ink); setText(value); tag = description; contentDescription = description
        minHeight = dp(52); setPadding(dp(8), dp(8), dp(8), dp(8)); backgroundTintList = ColorStateList.valueOf(muted)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun text(value: String, size: Int, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(4), 0, dp(4)); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, accent)))
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.ros_selected)); setOnClickListener { action() }
    }
    private fun primaryButton(label: String, action: () -> Unit) = button(label, action).apply {
        backgroundTintList = ColorStateList.valueOf(accent); setTextColor(getColor(R.color.ros_on_accent))
    }
    private fun TextView.show(value: String) { if (text.toString() != value) text = value }
    private fun android.view.View.heading() { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
