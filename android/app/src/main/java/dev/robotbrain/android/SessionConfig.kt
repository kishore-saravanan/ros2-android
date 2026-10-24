package dev.robotbrain.android

data class SessionConfig(val namespace: String, val domainId: Int) {
    init {
        require(namespace.matches(Regex("/(?:[a-zA-Z_][a-zA-Z0-9_]*)(?:/[a-zA-Z_][a-zA-Z0-9_]*)*"))) {
            "Use a namespace such as /phone or /robot/phone; letters, numbers and underscores only."
        }
        require(domainId in 0..101) { "Use a ROS domain ID between 0 and 101 for this prototype." }
    }
}

