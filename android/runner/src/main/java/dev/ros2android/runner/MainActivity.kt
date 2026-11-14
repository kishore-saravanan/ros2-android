package dev.ros2android.runner

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import dev.ros2android.health.BackgroundPolicy
import dev.ros2android.health.HealthText
import org.json.JSONObject

/** Shared operator controls for C++, Python and ordinary-workspace runner APKs. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("project_profile", MODE_PRIVATE) }
    private val fields = mutableMapOf<String, EditText>()
    private var appliedConfigRevision: String? = null
    private val accent get() = getColor(R.color.ros_accent)
    private val muted get() = getColor(R.color.ros_muted)
    private val ink get() = getColor(R.color.ros_text)
    private lateinit var status: TextView
    private lateinit var pauseWarning: TextView
    private lateinit var policy: TextView
    private lateinit var nodes: TextView
    private lateinit var diagnostic: TextView
    private lateinit var feedback: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val update = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 500) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                else window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else window.navigationBarColor = getColor(R.color.ros_accent)
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
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val titleBar = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        titleBar.addView(button("‹") { returnToSensors() }.apply { tag = "return_to_sensors"; contentDescription = "Back to ROS2 Android"; textSize = 24f }, LinearLayout.LayoutParams(dp(48), dp(48)))
        titleBar.addView(label(BuildConfig.PROJECT_NAME, 20), LinearLayout.LayoutParams(0, -2, 1f)); heading.addView(titleBar)
        status = label("Stopped", 16).apply { tag = "project_state" }; heading.addView(status)
        pauseWarning = label("", 14, getColor(R.color.ros_error)).apply {
            tag = "project_pause_warning"; visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }; heading.addView(pauseWarning)
        val actions = LinearLayout(this)
        start = button("Start project") { startProject() }.apply { tag = "start_project"; backgroundTintList = ColorStateList.valueOf(accent); setTextColor(getColor(R.color.ros_on_accent)) }
        stop = button("Stop project") { ProjectService.current?.stopProject(); refresh() }.apply { tag = "stop_project"; backgroundTintList = ColorStateList.valueOf(accent); setTextColor(getColor(R.color.ros_on_accent)) }
        actions.addView(start, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(actions); shell.addView(heading)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(20)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(shell)
        feedback = label("", 14, getColor(R.color.ros_error)).apply {
            visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }; body.addView(feedback)
        card(body, "Nodes") { box ->
            nodes = label("No active nodes reported", 15); box.addView(nodes)
        }
        card(body, "Connection") { settings ->
            field(settings, "domain", "ROS domain", "0").inputType = android.text.InputType.TYPE_CLASS_NUMBER
            field(settings, "namespace", "Namespace", BuildConfig.PROJECT_NAMESPACE)
            field(settings, "peer", "PC IPv4 (optional)", "")
            field(settings, "local", "Phone IPv4 (optional)", "")
            field(settings, "ros_args", "ROS arguments (JSON array)", "[]")
        }
        card(body, "Launch configuration") { box ->
            val plan = runCatching { ProjectLaunch.parse(BuildConfig.PROJECT_LAUNCH_JSON, BuildConfig.PROJECT_NAMESPACE) }
            box.addView(label(plan.fold({ components -> components.joinToString("\n") { it.component } }, { "Cannot read packaged launch: ${it.message}" }), 15, accent))
            box.addView(label("These are packaged components, not a list of live ROS nodes. PC launch overrides can change what runs. Full launch editing and per-node restart controls are pending.", 13, muted))
        }
        card(body, "Diagnostics") { box ->
            box.addView(label("${BuildConfig.PROJECT_ID} · ABI ${BuildConfig.PROJECT_RUNTIME_ABI}\nBuild ${BuildConfig.PROJECT_SOURCE_ID}", 14, muted))
            diagnostic = label("", 12, muted).apply { tag = "project_status"; setTextIsSelectable(true); visibility = View.VISIBLE }
            box.addView(diagnostic)
            box.addView(label("For live logs use the PC tool's logs command. This screen shows runtime diagnostics; it is not a log viewer.", 13, muted))
            box.addView(label("Background operation", 14))
            policy = label("", 13, muted).apply { tag = "project_background_policy" }; box.addView(policy)
            box.addView(button("Android app settings") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))) }
                    .onFailure { showError("Android app settings are unavailable on this device") }
            }.apply { tag = "project_app_settings" })
        }
        appliedConfigRevision = prefs.getString("config_revision", null)
        refresh()
    }

    private fun returnToSensors() {
        runCatching { startActivity(Intent().setComponent(ComponentName("dev.ros2android.android", "dev.ros2android.android.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            .onFailure { showError("Install ROS2 Android to return to the sensor app.") }
    }
    private fun startProject() {
        runCatching {
            val intent = Intent(this, ProjectService::class.java).setAction(ProjectService.START)
                .putExtra("domain", fields.getValue("domain").text.toString().toIntOrNull() ?: -1)
                .putExtra("namespace", fields.getValue("namespace").text.toString().trim())
                .putExtra("peer", fields.getValue("peer").text.toString().trim())
                .putExtra("local", fields.getValue("local").text.toString().trim())
                .putExtra("ros_args", fields.getValue("ros_args").text.toString())
            ProjectConfig.from(intent)
            save(); feedback.visibility = View.GONE
            requestNotificationsOnce()
            startForegroundService(intent); start.isEnabled = false
        }.onFailure { showError("Cannot start: ${it.message}") }
    }
    private fun refresh() {
        val snapshot = runCatching { JSONObject(ProjectService.statusJson(this)) }.getOrElse {
            showError("Cannot read runtime state: ${it.message}"); return
        }
        val state = snapshot.optString("state", "Unknown")
        val busy = snapshot.optBoolean("busy")
        snapshot.optJSONObject("config")?.let { config ->
            val revision = config.optString("revision")
            if (revision.isNotEmpty() && revision != appliedConfigRevision) { // Saved by a start or the app's settings
                fields.forEach { (key, field) -> field.show(config.getString(key)) }
                appliedConfigRevision = revision
            }
        }
        status.show(state)
        status.setTextColor(if (state.startsWith("Failed") || state.startsWith("Stop failed")) getColor(R.color.ros_error) else if (state == "Running") accent else muted)
        start.visibility = if (busy || state == "Running") View.GONE else View.VISIBLE
        stop.visibility = if (busy || state == "Running") View.VISIBLE else View.GONE
        stop.show(if (busy) "$state…" else "Stop project")
        (nodes.parent as View).visibility = if (state == "Running") View.VISIBLE else View.GONE
        start.isEnabled = !busy && state != "Running"
        stop.isEnabled = !busy && ProjectService.current != null
        fields.values.forEach { it.isEnabled = !busy && state != "Running" }
        val native = snapshot.optJSONObject("native")
        val names = native?.optJSONArray("nodes")
        val reported = if (names != null) (0 until names.length()).map { names.optString(it) }.filter { it.isNotEmpty() }
            else listOfNotNull(native?.optString("node")?.takeIf { it.isNotEmpty() })
        nodes.show(if (state == "Running" && reported.isNotEmpty()) "${reported.size} node(s) reported\n${reported.joinToString("\n")}" else "No active nodes reported")
        val health = snapshot.optJSONObject("health")
        val warning = health?.let { HealthText.pauseWarning("this project", it.optInt("pauses"), it.optLong("longestPauseMs")) }
        pauseWarning.show(warning.orEmpty()); pauseWarning.visibility = if (warning == null) View.GONE else View.VISIBLE
        if (policy.isShown) policy.show(HealthText.policy(BackgroundPolicy.batteryOptimized(this), BackgroundPolicy.backgroundRestricted(this)))
        if (diagnostic.isShown) diagnostic.show(snapshot.toString(2).take(16384))
    }
    /** The project notification carries its Stop action; Android 13+ hides it until notifications are allowed. */
    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val asked = getSharedPreferences("runner_ui", MODE_PRIVATE)
        if (asked.getBoolean("notifications_requested", false)) return
        asked.edit().putBoolean("notifications_requested", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
    }
    private fun showError(message: String) { feedback.text = message; feedback.visibility = View.VISIBLE }
    private fun save() { val edit = prefs.edit(); fields.forEach { (key, value) -> edit.putString(key, value.text.toString()) }; edit.apply() }
    override fun onResume() { super.onResume(); main.removeCallbacks(update); main.post(update) }
    override fun onPause() { refresh(); saveIfValid(); main.removeCallbacks(update); super.onPause() }
    /** Keep edits only when they form a valid profile, so the main app's Start never meets a broken saved profile. */
    private fun saveIfValid() {
        val values = fields.mapValues { it.value.text.toString().trim() }
        val valid = runCatching {
            val arguments = org.json.JSONArray(values["ros_args"].orEmpty().ifBlank { "[]" })
            ProjectConfig(values["domain"].orEmpty().toInt(), values["namespace"].orEmpty(), values["peer"].orEmpty(),
                values["local"].orEmpty(), Array(arguments.length()) { arguments.getString(it) }, emptyList())
        }.isSuccess
        if (valid) save()
    }
    override fun onDestroy() { main.removeCallbacks(update); super.onDestroy() }
    private fun field(box: LinearLayout, key: String, title: String, fallback: String): EditText {
        val caption = label(title, 13, muted); box.addView(caption)
        return EditText(this).apply {
            setSingleLine(); setTextColor(ink); minHeight = dp(52); tag = key
            id = View.generateViewId(); caption.labelFor = id // Screen readers announce the visible label.
            setText(prefs.getString(key, fallback)); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            fields[key] = this; box.addView(this)
        }
    }
    private fun card(parent: LinearLayout, title: String, content: (LinearLayout) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(getColor(R.color.ros_surface)); cornerRadius = dp(12).toFloat() }
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (title == "Nodes") { box.addView(label(title, 16)); content(box); return }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val toggle = button("$title    ⌄") {}.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; tag = "runner_section:$title" }
        toggle.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = "$title    ${if (open) "⌃" else "⌄"}"
            if (Build.VERSION.SDK_INT >= 30) toggle.stateDescription = if (open) "Expanded" else "Collapsed"
            refresh()
        }
        box.addView(toggle); content(details); box.addView(details)
    }
    private fun label(value: String, size: Int, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, accent)))
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.ros_selected)); setOnClickListener { action() }
    }
    private fun TextView.show(value: String) { if (text.toString() != value) text = value }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
