package dev.robotbrain.runner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.view.WindowManager
import android.widget.*

/** Development runner console. The main ROS2 Android app remains the hardware/operator UI. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var domain: EditText
    private lateinit var namespace: EditText
    private lateinit var peer: EditText
    private lateinit var local: EditText
    private lateinit var rosArgs: EditText
    private val prefs by lazy { getSharedPreferences("project_profile", MODE_PRIVATE) }
    private val update = object : Runnable {
        override fun run() {
            status.text = ProjectService.statusJson()
            main.postDelayed(this, 500)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 48); setBackgroundColor(Color.rgb(16, 23, 29)) }
        val scroll = ScrollView(this).apply { fitsSystemWindows = true; addView(box) }
        setContentView(scroll)
        fun label(value: String, size: Float = 15f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12) }
        box.addView(label("ROS2 Android · ${BuildConfig.PROJECT_NAME}", 24f))
        box.addView(label("Development project runner · ${BuildConfig.PROJECT_ID}\nCode runs on this phone. The PC is needed only to develop and deploy."))
        fun field(key: String, title: String, fallback: String): EditText {
            box.addView(label(title))
            return EditText(this).apply {
                setSingleLine(); setTextColor(Color.WHITE); setText(prefs.getString(key, fallback)); contentDescription = key; box.addView(this)
            }
        }
        domain = field("domain", "ROS domain", "0")
        namespace = field("namespace", "Namespace", "/demo")
        peer = field("peer", "PC IPv4 peer (optional)", "")
        local = field("local", "Phone interface IPv4 (optional)", "")
        rosArgs = field("ros_args", "ROS argument array (JSON, no shell)", "[]")
        status = label("Stopped").apply { contentDescription = "project_status" }; box.addView(status)
        box.addView(Button(this).apply {
            text = "Start project"; isAllCaps = false
            setOnClickListener {
                runCatching {
                    val intent = Intent(this@MainActivity, ProjectService::class.java).setAction(ProjectService.START)
                        .putExtra("domain", domain.text.toString().toInt()).putExtra("namespace", namespace.text.toString().trim())
                        .putExtra("peer", peer.text.toString().trim()).putExtra("local", local.text.toString().trim()).putExtra("ros_args", rosArgs.text.toString())
                    ProjectConfig.from(intent)
                    prefs.edit().putString("domain", domain.text.toString()).putString("namespace", namespace.text.toString())
                        .putString("peer", peer.text.toString()).putString("local", local.text.toString()).putString("ros_args", rosArgs.text.toString()).apply()
                    startForegroundService(intent)
                }.onFailure { status.text = "Cannot start: ${it.message}" }
            }
        })
        box.addView(Button(this).apply {
            text = "Stop project"; isAllCaps = false; setOnClickListener { ProjectService.current?.stopProject() }
        })
    }
    override fun onResume() { super.onResume(); main.post(update) }
    override fun onPause() { main.removeCallbacks(update); super.onPause() }
}
