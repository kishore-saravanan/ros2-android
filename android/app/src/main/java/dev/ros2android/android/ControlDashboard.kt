package dev.ros2android.android

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

internal class ControlDashboard(context: Context, private val robot: () -> RobotSessionService?, private val profile: () -> RobotSessionService.Profile,
    private val beforeStart: () -> Unit = {}) : LinearLayout(context) {
    private val prefs = context.getSharedPreferences("control_dashboard_v1", Context.MODE_PRIVATE)
    private val widgets = mutableListOf<ControlWidget>()
    private val interactive = mutableListOf<View>()
    private var syncingSwitches = false
    private val switchStatuses = mutableMapOf<String, TextView>()
    private val readings = mutableMapOf<String, Pair<TextView, TextView>>()
    private val status = label("")
    private val enable = action("Start dashboard") {}
    private val add = action("Add widget") { edit(null) }
    init {
        orientation = VERTICAL
        runCatching {
            val saved = JSONArray(prefs.getString("widgets", "[]"))
            require(saved.length() <= 32)
            for (i in 0 until saved.length()) {
                val item = saved.getJSONObject(i)
                widgets += ControlWidget(item.getString("id"), item.getString("label"), item.getString("type"), item.getString("topic"), item.getString("value"), item.getDouble("min"), item.getDouble("max"), item.getDouble("linear"), item.getDouble("angular"), item.optString("messageType", "std_msgs/msg/String"))
            }
        }.onFailure { widgets.clear(); status.text = "Saved controls could not be loaded" }
        drawWidgets()
    }
    private fun save() {
        val items = JSONArray()
        widgets.forEach { w -> items.put(JSONObject().put("id", w.id).put("label", w.label).put("type", w.type).put("topic", w.topic).put("value", w.value).put("min", w.minimum).put("max", w.maximum).put("linear", w.linear).put("angular", w.angular).put("messageType", w.messageType)) }
        prefs.edit().putString("widgets", items.toString()).apply()
    }
    private fun label(value: String) = TextView(context).apply { text = value; textSize = 15f; setTextColor(context.getColor(R.color.ros_text)); setPadding(0, dp(6), 0, dp(6)) }
    private fun action(value: String, run: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; minHeight = dp(48); setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(context.getColor(R.color.ros_muted), context.getColor(R.color.ros_accent)))); backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.ros_selected)); setOnClickListener { run() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun failure(error: Throwable) { status.text = error.message ?: "Control failed" }
    private fun drawWidgets() {
        (enable.parent as? ViewGroup)?.removeView(enable)
        (add.parent as? ViewGroup)?.removeView(add)
        removeAllViews(); interactive.clear(); readings.clear(); switchStatuses.clear(); addView(status)
        enable.setOnClickListener {
            runCatching {
                val service = robot() ?: error("Connecting to phone runtime…")
                if (service.controlsEnabled) service.stopControls() else { beforeStart(); service.startControls(widgets, profile()) }
            }.onFailure(::failure)
            refresh()
        }
        addView(LinearLayout(context).apply {
            addView(enable, LayoutParams(0, -2, 1f)); addView(add, LayoutParams(0, -2, 1f))
        })
        if (widgets.isEmpty()) addView(label("Add a control or a subscribed value."))
        widgets.forEach { w ->
            val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(context.getColor(R.color.ros_surface)) }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(label(w.label), LayoutParams(0, -2, 1f))
            val menu = action("⋮") {
                if (robot()?.controlsEnabled == true || robot()?.busy == true) return@action
                PopupMenu(context, header).apply {
                    listOf("Edit", "Move up", "Move down", "Delete").forEach { menu.add(it) }
                    setOnMenuItemClickListener { item ->
                        val index = widgets.indexOf(w)
                        when (item.title) {
                            "Edit" -> edit(w)
                            "Delete" -> AlertDialog.Builder(context).setTitle("Delete ${w.label}?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> widgets.remove(w); save(); drawWidgets() }.show()
                            "Move up" -> if (index > 0) { java.util.Collections.swap(widgets, index, index - 1); save(); drawWidgets() }
                            "Move down" -> if (index < widgets.lastIndex) { java.util.Collections.swap(widgets, index, index + 1); save(); drawWidgets() }
                        }; true
                    }; show()
                }
            }.apply { contentDescription = "Edit ${w.label}" }
            header.addView(menu, LayoutParams(dp(56), -2)); box.addView(header)
            box.addView(label(w.topic).apply { textSize = 12f })
            fun send(a: Double = 0.0, b: Double = 0.0, text: String = "") { runCatching { robot()?.control(w, a, b, text) }.onFailure(::failure) }
            val control: View = if (w.subscriber) LinearLayout(context).apply {
                orientation = VERTICAL
                val value = label("—").apply { textSize = 26f; maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "reading-value:${w.id}" }
                val state = label("Stopped").apply { textSize = 12f; tag = "reading-state:${w.id}" }
                if (w.image) {
                    value.visibility = GONE
                    addView(DashboardImageView(context, { robot()?.dashboardReadings?.get(w.id) }, { robot()?.controlsEnabled == true }).apply {
                        tag = "reading-image:${w.id}"; contentDescription = w.label
                    }, LayoutParams(-1, dp(200)))
                }
                addView(value); addView(state); readings[w.id] = value to state
            } else when (w.type) {
                "Button · Bool" -> action(w.label) { send(if (w.value == "true") 1.0 else 0.0) }
                "Button · String" -> action(w.label) { send(text = w.value) }
                "Switch" -> Switch(context).apply { text = w.label; isChecked = robot()?.switchValue(w.id) ?: false; minHeight = dp(48); setTextColor(context.getColor(R.color.ros_text)); setOnCheckedChangeListener { _, checked -> if (!syncingSwitches) send(if (checked) 1.0 else 0.0) } }
                "Slider" -> LinearLayout(context).apply {
                    orientation = VERTICAL
                    val number = label(w.minimum.toString()); addView(number)
                    addView(SeekBar(context).apply {
                        max = 1000; minHeight = dp(48); contentDescription = w.label
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(bar: SeekBar) {}
                            override fun onStopTrackingTouch(bar: SeekBar) {}
                            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                                val value = w.minimum + (w.maximum - w.minimum) * progress / 1000.0
                                number.text = "%.3f".format(value); if (fromUser) send(value)
                            }
                        })
                    })
                }
                else -> JoystickView(context) { x, y -> send(y * w.linear, -x * w.angular) }
            }
            control.tag = "control:${w.id}"; if (!w.subscriber) interactive += control; box.addView(control, LayoutParams(-1, if (!w.subscriber && w.kind == 12) dp(if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 120 else 220) else -2))
            if (w.type == "Switch") box.addView(label("").apply {
                textSize = 12f; tag = "control-status:${w.id}"; switchStatuses[w.id] = this
            })
            addView(box, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        refresh()
    }
    fun refresh() {
        val service = robot(); val armed = service?.controlsEnabled == true
        enable.text = if (armed) "Stop dashboard" else "Start dashboard"
        enable.isEnabled = service != null && !service.busy && widgets.isNotEmpty()
        add.isEnabled = !armed && service?.busy != true && widgets.size < 32
        val message = service?.errors?.get("controls") ?: if (armed) "Dashboard running" else "Dashboard stopped"
        if (status.text.toString() != message) status.text = message
        widgets.filter { it.subscriber }.forEach { widget ->
            val row = readings[widget.id] ?: return@forEach
            val sample = service?.dashboardReadings?.get(widget.id)
            val state = if (!armed) "Stopped" else if (widget.image && sample != null && sample.bitmap == null) sample.value else sample?.state(android.os.SystemClock.elapsedRealtime()) ?: "Waiting"
            val value = if (!armed || sample == null) "—" else if (widget.kind == 10) {
                if (sample.value == "true") "● On" else "○ Off"
            } else sample.value.ifEmpty { "(empty)" }
            if (row.first.text.toString() != value) row.first.text = value
            if (row.second.text.toString() != state) row.second.text = state
            row.first.setTextColor(context.getColor(if (state == "Stale" || state == "Stopped") R.color.ros_muted else R.color.ros_text))
        }
        switchStatuses.forEach { (id, label) ->
            val message = service?.switchDescription(id) ?: "Phone runtime unavailable · remote state unknown"
            if (label.text.toString() != message) label.text = message
        }
        fun enabled(view: View) { view.isEnabled = armed; if (view is ViewGroup) for (i in 0 until view.childCount) enabled(view.getChildAt(i)) }
        interactive.forEach { view ->
            if (view is Switch && service != null) {
                val desired = service.switchValue(view.tag.toString().removePrefix("control:"))
                if (view.isChecked != desired) {
                    syncingSwitches = true
                    try { view.isChecked = desired } finally { syncingSwitches = false }
                }
            }
            enabled(view)
        }
    }
    private fun edit(old: ControlWidget?) {
        val form = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun input(title: String, value: String): EditText { form.addView(label(title)); return EditText(context).apply { setSingleLine(); setText(value); contentDescription = title; form.addView(this) } }
        fun selector(title: String, options: List<String>, selected: String): Spinner {
            form.addView(label(title))
            return Spinner(context).apply {
                contentDescription = title
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(options.indexOf(selected).coerceAtLeast(0)); form.addView(this)
            }
        }
        val name = input("Label", old?.label ?: "Widget")
        val direction = selector("Direction", listOf("Publish", "Subscribe"), if (old?.subscriber == true) "Subscribe" else "Publish")
        val type = selector("Widget", ControlWidget.PUBLISH_TYPES, old?.type ?: ControlWidget.PUBLISH_TYPES.first())
        val messageType = selector("Message type", ControlWidget.SUB_TYPES.keys.toList(), old?.messageType ?: "std_msgs/msg/String")
        val topic = input("Topic", old?.topic ?: "/phone/control/button")
        val value = input("Button value", old?.value ?: "true")
        val min = input("Minimum", (old?.minimum ?: 0.0).toString())
        val max = input("Maximum", (old?.maximum ?: 1.0).toString())
        val linear = input("Maximum linear speed (m/s)", (old?.linear ?: 0.3).toString())
        val angular = input("Maximum angular speed (rad/s)", (old?.angular ?: 0.8).toString())
        val error = label("").apply { setTextColor(context.getColor(R.color.ros_error)) }; form.addView(error)
        fun visible(field: View, show: Boolean) { val visibility = if (show) VISIBLE else GONE; field.visibility = visibility; form.getChildAt(form.indexOfChild(field) - 1).visibility = visibility }
        fun updateFields() {
            val sub = direction.selectedItem == "Subscribe"; val selected = type.selectedItem.toString()
            visible(type, !sub); visible(messageType, sub)
            visible(value, !sub && selected.startsWith("Button")); visible(min, !sub && selected == "Slider"); visible(max, !sub && selected == "Slider")
            visible(linear, !sub && selected == "Joystick"); visible(angular, !sub && selected == "Joystick")
        }
        val changes = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateFields() }
        }
        direction.onItemSelectedListener = changes; type.onItemSelectedListener = changes; updateFields()
        val dialog = AlertDialog.Builder(context).setTitle(if (old == null) "Add widget" else "Edit widget")
            .setView(ScrollView(context).apply { addView(form) }).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val sub = direction.selectedItem == "Subscribe"
                    val selected = if (sub) "Subscribed value" else type.selectedItem.toString()
                    val widget = ControlWidget(old?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(), selected, topic.text.toString().trim(),
                        value.text.toString(), if (selected == "Slider") min.text.toString().toDouble() else 0.0,
                        if (selected == "Slider") max.text.toString().toDouble() else 1.0,
                        if (selected == "Joystick") linear.text.toString().toDouble() else 0.3,
                        if (selected == "Joystick") angular.text.toString().toDouble() else 0.8, messageType.selectedItem.toString())
                    require(widgets.count { it.id != widget.id && it.image } + (if (widget.image) 1 else 0) <= 4) { "Use at most four image widgets" }
                    require(widgets.none { it.id != widget.id && it.topic == widget.topic }) { "Another widget already uses this topic" }
                    if (old == null) widgets += widget else widgets[widgets.indexOf(old)] = widget
                    save(); drawWidgets(); dialog.dismiss()
                }.onFailure { error.text = it.message }
            }
        }; dialog.show()
    }

}

internal class JoystickView(context: Context, private val command: (Double, Double) -> Unit) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var xValue = 0.0; private var yValue = 0.0; private var held = false; private var cancelledGesture = false
    private val repeat = object : Runnable { override fun run() { if (held && isEnabled && hasWindowFocus()) { command(xValue, yValue); handler.postDelayed(this, 100) } else stop() } }
    init { isFocusable = true; isClickable = true; contentDescription = "Joystick. Drag to drive; release to stop. Arrow keys drive; center stops." }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width, height) * 0.42f; val cx = width / 2f; val cy = height / 2f
        paint.color = context.getColor(R.color.ros_selected); canvas.drawCircle(cx, cy, radius, paint)
        paint.color = context.getColor(R.color.ros_accent); canvas.drawCircle(cx + xValue.toFloat() * radius, cy - yValue.toFloat() * radius, radius * 0.25f, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelledGesture = false
        if (event.pointerCount > 1) cancelledGesture = true
        if (cancelledGesture) { stop(); return true }
        if (min(width, height) <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val radius = min(width, height) * 0.42
                val x = (event.x - width / 2.0) / radius; val y = (height / 2.0 - event.y) / radius
                val scale = max(1.0, hypot(x, y)); xValue = x / scale; yValue = y / scale
                if (hypot(xValue, yValue) < 0.08) { xValue = 0.0; yValue = 0.0 }
                if (!held) { held = true; handler.post(repeat) }; invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { stop(); parent.requestDisallowInterceptTouchEvent(false); if (event.actionMasked == MotionEvent.ACTION_UP) performClick() }
        }; return true
    }
    override fun performClick(): Boolean { super.performClick(); stop(); return true }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { xValue = 0.0; yValue = 1.0 }
            KeyEvent.KEYCODE_DPAD_DOWN -> { xValue = 0.0; yValue = -1.0 }
            KeyEvent.KEYCODE_DPAD_LEFT -> { xValue = -1.0; yValue = 0.0 }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { xValue = 1.0; yValue = 0.0 }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (!held) { held = true; handler.post(repeat) }; invalidate(); return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean { if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) { stop(); return true }; return super.onKeyUp(keyCode, event) }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) stop() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    private fun stop() { held = false; xValue = 0.0; yValue = 0.0; handler.removeCallbacks(repeat); command(0.0, 0.0); invalidate() }
}
