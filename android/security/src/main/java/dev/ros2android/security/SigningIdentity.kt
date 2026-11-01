package dev.ros2android.security

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Current APK signer only: rotation/multiple signers require explicit re-authorization. */
object SigningIdentity {
    fun valid(fingerprint: String?): Boolean = fingerprint?.matches(Regex("[a-f0-9]{64}")) == true
    fun matches(expected: String?, actual: String?): Boolean = valid(expected) && expected == actual
    fun current(pm: PackageManager, packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        else pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        signatures?.singleOrNull()?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()
    fun uidMatches(packageName: String, uid: Int, packageUid: Int?, uidPackages: Set<String>?): Boolean =
        uid >= 0 && uid == packageUid && uidPackages == setOf(packageName)
    fun ownsUid(pm: PackageManager, packageName: String, uid: Int): Boolean = runCatching {
        uidMatches(packageName, uid, pm.getApplicationInfo(packageName, 0).uid,
            pm.getPackagesForUid(uid)?.toSet())
    }.getOrDefault(false)
}
