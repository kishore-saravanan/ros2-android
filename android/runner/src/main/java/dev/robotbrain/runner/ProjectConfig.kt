package dev.robotbrain.runner

import android.content.Intent
import org.json.JSONArray

data class ProjectConfig(val domain: Int, val namespace: String, val peer: String, val local: String, val arguments: Array<String>) {
    init {
        require(domain in 0..101) { "Domain must be 0–101" }
        require(namespace == "/" || namespace.matches(Regex("/(?:[a-zA-Z_][a-zA-Z0-9_]*)(?:/[a-zA-Z_][a-zA-Z0-9_]*)*"))) { "Invalid ROS namespace" }
        for (ip in listOf(peer, local)) require(ip.isEmpty() || (ip.split('.').size == 4 && ip.split('.').all { it.toIntOrNull() in 0..255 })) { "Use an IPv4 address or leave blank" }
        require(arguments.size <= 128 && arguments.all { it.length <= 4096 && '\u0000' !in it }) { "ROS arguments too large or invalid" }
    }
    companion object {
        fun from(intent: Intent): ProjectConfig {
            val array = JSONArray(intent.getStringExtra("ros_args") ?: "[]")
            return ProjectConfig(intent.getIntExtra("domain", 0), intent.getStringExtra("namespace") ?: "/demo",
                intent.getStringExtra("peer") ?: "", intent.getStringExtra("local") ?: "", Array(array.length()) { array.getString(it) })
        }
    }
}
