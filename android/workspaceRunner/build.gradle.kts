import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val robotProject = rootProject.file(providers.gradleProperty("robotProjectDir").getOrElse("examples/cpp_talker")).canonicalFile
val manifest = JsonSlurper().parse(robotProject.resolve("robot-project.json")) as Map<*, *>
val active = manifest["runtime"] == "workspace"
val projectId = if (active) manifest["id"].toString() else "workspace_stub"
val runnerApplicationId = if (active) manifest["applicationId"].toString() else "dev.ros2android.android.runner.workspace_stub"
require(projectId.matches(Regex("[a-z][a-z0-9_]{0,63}")))
require(runnerApplicationId.matches(Regex("dev\\.ros2android\\.android\\.runner\\.[a-z][a-z0-9_]*")))
val workspaceBundle = if (active) rootProject.file(manifest["bundleRoot"].toString()) else layout.buildDirectory.dir("unconfigured").get().asFile
val launchJson = if (active) robotProject.resolve("launch.json").readText() else ""
val digest = MessageDigest.getInstance("SHA-256")
if (active) robotProject.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(robotProject).invariantSeparatorsPath }.forEach {
    digest.update(it.relativeTo(robotProject).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
    digest.update(byteArrayOf(0)); digest.update(it.readBytes()); digest.update(byteArrayOf(0))
}
val sourceId = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
android {
    namespace = "dev.ros2android.runner"
    compileSdk = 35; buildToolsVersion = "35.0.0"; ndkVersion = "27.2.12479018"
    defaultConfig {
        manifestPlaceholders += mapOf("runnerProjectId" to projectId, "runnerRuntime" to "workspace", "runnerSourceId" to sourceId)
        applicationId = runnerApplicationId
        minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0-workspace-dev1"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "PROJECT_ID", JsonOutput.toJson(projectId))
        buildConfigField("String", "PROJECT_SOURCE_ID", JsonOutput.toJson(sourceId))
        buildConfigField("String", "PROJECT_NAME", JsonOutput.toJson(if (active) manifest["name"].toString() else "Workspace"))
        buildConfigField("String", "PROJECT_NAMESPACE", "\"/android_test\"")
        buildConfigField("int", "PROJECT_RUNTIME_ABI", "2")
        buildConfigField("String", "PROJECT_LAUNCH_JSON", JsonOutput.toJson(launchJson))
    }
    // AAPT's default directory ignore rule drops Python packages such as _utils.
    androidResources { ignoreAssetsPattern = "!.git:!.svn:!CVS:!*.scc:.*:!*~" }
    sourceSets["main"].res.srcDir("../branding/res")
    buildFeatures.buildConfig = true
    sourceSets["main"].apply {
        manifest.srcFile("../runner/src/main/AndroidManifest.xml")
        java.srcDir("../runner/src/main/java"); res.srcDir("../runner/src/main/res")
        jniLibs.srcDir(workspaceBundle.resolve("jniLibs")); assets.srcDir(workspaceBundle.resolve("assets"))
    }
    sourceSets["debug"].apply {
        manifest.srcFile("../runner/src/debug/AndroidManifest.xml")
        java.srcDir("../runner/src/debug/java")
    }
    packaging.jniLibs.apply { useLegacyPackaging = true; keepDebugSymbols += "**/libworkspace_exec_*.so" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild").configure { doFirst { require(active) { "Use tools/ros2android-workspace build" } } }
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        if (manifest["withShell"] == true) {
            ZipFile(layout.buildDirectory.file("outputs/apk/debug/workspaceRunner-debug.apk").get().asFile).use { apk ->
                for (entry in listOf("assets/ros2-shell/site-packages/numpy/_utils/__init__.py",
                    "assets/ros2-shell/site-packages/numpy/core/_multiarray_umath.so",
                    "assets/ros2-shell/site-packages/ros2node/verb/list.py",
                    "lib/arm64-v8a/libros2_python_exec.so")) {
                    check(apk.getEntry(entry) != null) { "Native ROS shell asset missing from APK: $entry" }
                }
            }
        }
        val output = layout.buildDirectory.file("outputs/robot-project.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.toJson(mapOf("project" to projectId, "applicationId" to runnerApplicationId,
            "sourceId" to sourceId, "runtimeAbi" to 2, "runtime" to "workspace")) + "\n")
    }
}
