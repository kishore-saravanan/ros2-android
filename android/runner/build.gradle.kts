import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val requestedProject = rootProject.file(providers.gradleProperty("robotProjectDir").getOrElse("examples/cpp_talker")).canonicalFile
val requestedManifest = JsonSlurper().parse(requestedProject.resolve("robot-project.json")) as Map<*, *>
val robotProject = if (requestedManifest["runtime"] in listOf("python", "workspace")) rootProject.file("examples/cpp_talker").canonicalFile else requestedProject
val projectManifest = JsonSlurper().parse(robotProject.resolve("robot-project.json")) as Map<*, *>
val projectId = projectManifest["id"].toString()
val runnerApplicationId = projectManifest["applicationId"].toString()
require(projectManifest["schemaVersion"] == 1 && projectManifest["runtimeAbi"] in listOf(1, 2)) { "Unsupported project schema or runtime ABI" }
require(projectId.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "Invalid project ID" }
require(runnerApplicationId.matches(Regex("dev\\.robotbrain\\.android\\.runner\\.[a-z][a-z0-9_]*"))) { "Use a unique dev.robotbrain.android.runner.<id> applicationId" }
require(robotProject.resolve("CMakeLists.txt").isFile) { "Project CMakeLists.txt missing" }

val runtimeAbi = projectManifest["runtimeAbi"] as Int
val launchPath = projectManifest["defaultLaunch"]?.toString()
val launchFile = launchPath?.let { robotProject.resolve(it).canonicalFile }
require(launchFile == null || launchFile.toPath().startsWith(robotProject.toPath())) { "Launch file must be inside project" }
val resolvedLaunch = providers.gradleProperty("robotLaunchPlan").orNull?.let { rootProject.file(it) }
require(launchFile == null || launchFile.extension == "json" || resolvedLaunch != null) {
    "Resolve ROS launch files with tools/ros2android build first"
}
val launchJson = resolvedLaunch?.readText() ?: launchFile?.readText() ?: ""
require(runtimeAbi != 2 || launchJson.isNotEmpty()) { "ABI 2 requires defaultLaunch" }

val sourceDigest = MessageDigest.getInstance("SHA-256")
robotProject.walkTopDown().onEnter { it.name !in setOf("build", ".git", ".cxx", ".gradle", "__pycache__") }
    .filter { it.isFile }.sortedBy { it.relativeTo(robotProject).invariantSeparatorsPath }.forEach {
        sourceDigest.update(it.relativeTo(robotProject).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        sourceDigest.update(byteArrayOf(0)); sourceDigest.update(it.readBytes()); sourceDigest.update(byteArrayOf(0))
    }
val sourceId = sourceDigest.digest().joinToString("") { "%02x".format(it) }.take(12)

android {
    namespace = "dev.robotbrain.runner"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = runnerApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-runner-dev1"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "PROJECT_NAMESPACE", "\"/demo\"")
        buildConfigField("int", "PROJECT_RUNTIME_ABI", runtimeAbi.toString())
        buildConfigField("String", "PROJECT_LAUNCH_JSON", JsonOutput.toJson(launchJson))
        buildConfigField("String", "PROJECT_ID", JsonOutput.toJson(projectId))
        buildConfigField("String", "PROJECT_SOURCE_ID", JsonOutput.toJson(sourceId))
        buildConfigField("String", "PROJECT_NAME", JsonOutput.toJson(projectManifest["name"].toString()))
        externalNativeBuild.cmake {
            arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DROBOT_PROJECT_DIR=${robotProject.path}")
            cppFlags += "-std=c++17"
        }
    }
    buildFeatures.buildConfig = true
    externalNativeBuild.cmake {
        path = file("../../native/runner/CMakeLists.txt")
        version = "3.22.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir("src/cpp/java")
    sourceSets["main"].jniLibs.srcDir("../../native-deps/apk-libs")
    packaging.jniLibs.useLegacyPackaging = true
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val output = layout.buildDirectory.file("outputs/robot-project.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
            "project" to projectId, "applicationId" to runnerApplicationId, "sourceId" to sourceId, "runtimeAbi" to runtimeAbi,
            "launchId" to MessageDigest.getInstance("SHA-256").digest(launchJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
        ))) + "\n")
    }
}
