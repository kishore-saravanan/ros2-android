pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RobotBrain"
include(":app")
project(":app").projectDir = file("android/app")


include(":runner")
project(":runner").projectDir = file("android/runner")

include(":pythonRunner")
project(":pythonRunner").projectDir = file("android/pythonRunner")

include(":workspaceRunner")
project(":workspaceRunner").projectDir = file("android/workspaceRunner")
