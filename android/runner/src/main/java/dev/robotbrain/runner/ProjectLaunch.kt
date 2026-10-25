package dev.robotbrain.runner

import org.json.JSONArray
import org.json.JSONObject

/** Bounded launch schema, not a Python/ROS launch interpreter. */
data class ProjectNode(val component: String, val arguments: Array<String>)

object ProjectLaunch {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val topic = Regex("(?:/|~/)?[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*")
    private fun keys(value: JSONObject, allowed: Set<String>) {
        require(value.keys().asSequence().all { it in allowed }) { "Unsupported launch field; supported: $allowed" }
    }
    fun parse(source: String, namespace: String): List<ProjectNode> {
        if (source.isEmpty()) {
            require(BuildConfig.PROJECT_RUNTIME_ABI == 1) { "This project requires a launch configuration" }
            return listOf(ProjectNode("default", emptyArray()))
        }
        require(BuildConfig.PROJECT_RUNTIME_ABI == 2) { "Launch components require runtime ABI 2" }
        require(source.length <= 65536) { "Launch configuration exceeds 64 KiB" }
        val root = JSONObject(source)
        keys(root, setOf("schemaVersion", "nodes"))
        require(root.get("schemaVersion") == 1) { "Unsupported launch schema" }
        val nodes = root.getJSONArray("nodes")
        require(nodes.length() in 1..16) { "Launch requires 1–16 nodes" }
        val names = mutableSetOf<String>()
        return List(nodes.length()) { i ->
            val node = nodes.getJSONObject(i)
            keys(node, setOf("component", "name", "namespace", "parameters", "remappings"))
            val component = node.getString("component")
            val name = node.getString("name")
            val ns = if (node.has("namespace")) node.getString("namespace") else namespace
            require(identifier.matches(component) && identifier.matches(name)) { "Invalid component or node name" }
            ProjectConfig.validateNamespace(ns)
            require(names.add(ns.trimEnd('/') + "/" + name)) { "Duplicate launched node name" }
            val args = mutableListOf("-r", "__node:=$name", "-r", "__ns:=$ns")
            if (node.has("parameters")) {
                val params = node.getJSONObject("parameters")
                params.keys().forEach { key ->
                    require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) { "Invalid parameter name" }
                    val value = params.get(key)
                    require(value is String || value is Boolean || value is Number) { "Only scalar parameters are supported" }
                    require(value !is Number || value.toDouble().isFinite()) { "Non-finite parameter" }
                    val encoded = JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
                    args.addAll(listOf("-p", "$key:=$encoded"))
                }
            }
            if (node.has("remappings")) {
                val remaps = node.getJSONObject("remappings")
                remaps.keys().forEach { from ->
                    val to = remaps.getString(from)
                    require(from !in setOf("__node", "__name", "__ns") && topic.matches(from) && topic.matches(to)) { "Only explicit topic/service remappings are supported" }
                    args.addAll(listOf("-r", "$from:=$to"))
                }
            }
            require(args.size <= 128 && args.all { it.length <= 4096 && '\u0000' !in it }) { "Node arguments too large or invalid" }
            ProjectNode(component, args.toTypedArray())
        }
    }
}
