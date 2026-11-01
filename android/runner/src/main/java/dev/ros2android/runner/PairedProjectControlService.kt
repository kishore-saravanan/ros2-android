package dev.ros2android.runner

import dev.ros2android.security.SigningIdentity

/** Opt-in public Binder entry; every request is checked before reading config or replying. */
class PairedProjectControlService : ProjectControlService() {
    override fun authorized(uid: Int): Boolean =
        SigningIdentity.ownsUid(packageManager, "dev.ros2android.android", uid) &&
            SigningIdentity.matches(BuildConfig.CONTROLLER_CERTIFICATE_SHA256,
                SigningIdentity.current(packageManager, "dev.ros2android.android"))
}
