package dev.ros2android.android

import android.content.Context
import android.content.pm.PackageManager
import dev.ros2android.security.SigningIdentity

/** Local approval is scoped to package AND its current certificate, never to display name. */
class ProjectTrust(private val context: Context, private val project: InstalledProjects.Project) {
    private val pm get() = context.packageManager
    private val prefs get() = context.getSharedPreferences("project_trust", Context.MODE_PRIVATE)
    fun fingerprint(): String? = SigningIdentity.current(pm, project.packageName)
    val paired get() = project.controllerCertificate.isNotEmpty()
    fun compatible(): Boolean = if (paired)
        SigningIdentity.matches(project.controllerCertificate, SigningIdentity.current(pm, context.packageName))
    else pm.checkSignatures(context.packageName, project.packageName) == PackageManager.SIGNATURE_MATCH
    fun authorized(): Boolean = compatible() && (!paired ||
        SigningIdentity.matches(prefs.getString(project.packageName, null), fingerprint()))
    fun approve(shownFingerprint: String) {
        check(compatible() && SigningIdentity.matches(shownFingerprint, fingerprint())) { "Runner identity changed; refresh and review again" }
        check(paired) { "This runner uses same-signature controls" }
        prefs.edit().putString(project.packageName, shownFingerprint).commit()
    }
    fun revoke() { prefs.edit().remove(project.packageName).commit() }
    fun accepts(uid: Int): Boolean = authorized() && SigningIdentity.ownsUid(pm, project.packageName, uid)
}
