import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}
val requestedProject = rootProject.file(providers.gradleProperty("robotProjectDir").getOrElse("examples/python_talker")).canonicalFile
val requestedManifest = JsonSlurper().parse(requestedProject.resolve("robot-project.json")) as Map<*, *>
val robotProject = if (requestedManifest["runtime"] == "python") requestedProject else rootProject.file("examples/python_talker").canonicalFile
val manifest = JsonSlurper().parse(robotProject.resolve("robot-project.json")) as Map<*, *>
val pythonSource = manifest["pythonSourceDir"]?.toString()?.let { robotProject.resolve(it).canonicalFile }
require(pythonSource == null || (pythonSource.isDirectory && pythonSource.toPath().startsWith(robotProject.toPath()))) {
    "pythonSourceDir must be an existing directory inside the project"
}
val pythonLock = JsonSlurper().parse(rootProject.file("native/python-runtime.lock.json")) as Map<*, *>
val projectId = manifest["id"].toString()
val runnerApplicationId = manifest["applicationId"].toString()
require(manifest["runtime"] == "python" && manifest["runtimeAbi"] == 1 && manifest["schemaVersion"] == 1)
require(projectId.matches(Regex("[a-z][a-z0-9_]{0,63}")))
require(runnerApplicationId.matches(Regex("dev\\.ros2android\\.android\\.runner\\.[a-z][a-z0-9_]*")))
val digest = MessageDigest.getInstance("SHA-256")
robotProject.walkTopDown().onEnter { it.name !in setOf("build", ".git", ".cxx", ".gradle", "__pycache__") }
    .filter { it.isFile }.sortedBy { it.relativeTo(robotProject).invariantSeparatorsPath }.forEach {
        digest.update(it.relativeTo(robotProject).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        digest.update(byteArrayOf(0)); digest.update(it.readBytes()); digest.update(byteArrayOf(0))
    }
val sourceId = digest.digest().joinToString("") { "%02x".format(it) }.take(12)
android {
    namespace = "dev.ros2android.runner"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.2.12479018"
    defaultConfig {
        manifestPlaceholders += mapOf("runnerProjectId" to projectId, "runnerRuntime" to "python", "runnerSourceId" to sourceId)
        applicationId = runnerApplicationId
        minSdk = 26; targetSdk = 35
        versionCode = 1; versionName = "0.1.0-python-dev1"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "PROJECT_ID", JsonOutput.toJson(projectId))
        buildConfigField("String", "PROJECT_SOURCE_ID", JsonOutput.toJson(sourceId))
        buildConfigField("String", "PROJECT_NAME", JsonOutput.toJson(manifest["name"].toString()))
        buildConfigField("String", "PROJECT_NAMESPACE", "\"/python\"")
        buildConfigField("int", "PROJECT_RUNTIME_ABI", "1")
        buildConfigField("String", "PROJECT_LAUNCH_JSON", "\"\"")
        externalNativeBuild.cmake.arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
    }
    sourceSets["main"].res.srcDir("../branding/res")
    buildFeatures.buildConfig = true
    sourceSets["main"].apply {
        manifest.srcFile("../runner/src/main/AndroidManifest.xml")
        java.srcDir("../runner/src/main/java")
        res.srcDir("../runner/src/main/res")
        jniLibs.srcDir("../../native-deps/python-apk-libs")
        assets.srcDir("../../native-deps/python-assets")
    }
    sourceSets["debug"].apply {
        manifest.srcFile("../runner/src/debug/AndroidManifest.xml")
        java.srcDir("../runner/src/debug/java")
    }
    externalNativeBuild.cmake { path = file("../../native/python/CMakeLists.txt"); version = "3.22.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging.jniLibs.useLegacyPackaging = true
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
chaquopy {
    defaultConfig {
        version = pythonLock["python"].toString().split(".").take(2).joinToString(".")
        buildPython("/usr/bin/python3")
        pip {
            install("numpy==${pythonLock["numpy"]}"); install("PyYAML==${pythonLock["pyyaml"]}")
            val requirements = robotProject.resolve("requirements.txt")
            if (requirements.isFile) install("-r", requirements.path)
        }
    }
    sourceSets.getByName("main") {
        srcDir(robotProject)
        pythonSource?.let { srcDir(it) }
        srcDir("../../native-deps/python-packages")
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val output = layout.buildDirectory.file("outputs/robot-project.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.toJson(mapOf("project" to projectId, "applicationId" to runnerApplicationId,
            "sourceId" to sourceId, "runtimeAbi" to 1, "runtime" to "python")) + "\n")
    }
}
