plugins {
    id("com.chaquo.python") version "17.1.0" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}


// One version policy for all generated runner backends; identity/signing stay separate.
val runnerVersionCode = providers.gradleProperty("runnerVersionCode").getOrElse("1").toIntOrNull()
require(runnerVersionCode != null && runnerVersionCode in 1..2100000000) {
    "runnerVersionCode must be an integer from 1 to 2100000000"
}
val runnerVersionName = providers.gradleProperty("runnerVersionName").orNull
require(runnerVersionName == null || (runnerVersionName.isNotBlank() && runnerVersionName.length <= 100 &&
    runnerVersionName.none { it.isISOControl() })) { "runnerVersionName must be 1–100 visible characters" }
extra["runnerVersionCode"] = runnerVersionCode
extra["runnerVersionName"] = runnerVersionName ?: ""
