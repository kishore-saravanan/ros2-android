package dev.ros2android.runner

import android.content.Intent
import org.json.JSONArray

data class ProjectConfig(
    val domain: Int,
    val namespace: String,
    val peer: String,
    val local: String,
    val arguments: Array<String>,
    val nodes: List<ProjectNode>,
) {
    init {
        require(domain in 0..101) { "Domain must be 0–101" }
        validateNamespace(namespace)

        for (ip in listOf(peer, local)) {
            require(
                ip.isEmpty() ||
                    (ip.split('.').size == 4 &&
                        ip.split('.').all { part ->
                            part.length in 1..3 && part.all(Char::isDigit) && part.toInt() in 0..255
                        })
            ) {
                "Use an IPv4 address or leave blank"
            }
        }

        require(arguments.size <= 128 && arguments.all { it.length <= 4096 && '\u0000' !in it }) {
            "ROS arguments too large or invalid"
        }
    }

    companion object {
        fun validateNamespace(value: String) {
            require(
                value == "/" ||
                    value.matches(Regex("/(?:[a-zA-Z_][a-zA-Z0-9_]*)(?:/[a-zA-Z_][a-zA-Z0-9_]*)*"))
            ) {
                "Invalid ROS namespace"
            }
        }

        fun from(intent: Intent): ProjectConfig {
            val array = JSONArray(intent.getStringExtra("ros_args") ?: "[]")
            val namespace = intent.getStringExtra("namespace") ?: BuildConfig.PROJECT_NAMESPACE
            val nodes =
                ProjectLaunch.parse(
                    intent.getStringExtra("launch_json") ?: BuildConfig.PROJECT_LAUNCH_JSON,
                    namespace,
                )
            return ProjectConfig(
                intent.getIntExtra("domain", 0),
                intent.getStringExtra("namespace") ?: BuildConfig.PROJECT_NAMESPACE,
                intent.getStringExtra("peer") ?: "",
                intent.getStringExtra("local") ?: "",
                Array(array.length()) { array.getString(it) },
                nodes,
            )
        }
    }
}
