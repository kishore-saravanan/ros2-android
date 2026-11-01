package dev.ros2android.android

import android.app.Service
import android.content.Intent
import android.os.*

/** Debug-only negative test: sends from an isolated UID with no package permissions. */
class UnauthorizedRunnerProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val endpoint = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(request: Message) {
            val destination = request.replyTo
            var replies = 0
            val observer = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { replies++ }
            })
            val target = Messenger(requireNotNull(request.data.getBinder("target")))
            destination?.send(Message.obtain(null, 0).apply {
                data = Bundle().apply { putInt("uid", Process.myUid()) }
            })
            val failure = runCatching { target.send(Message.obtain(null, 1).apply { replyTo = observer }) }.exceptionOrNull()
            handler.postDelayed({
                destination?.send(Message.obtain(null, 1).apply {
                    data = Bundle().apply { putInt("replies", replies); putInt("uid", Process.myUid()); putString("failure", failure?.javaClass?.simpleName) }
                })
            }, 2000)
        }
    })
    override fun onBind(intent: Intent): IBinder = endpoint.binder
}
