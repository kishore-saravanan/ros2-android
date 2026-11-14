package dev.ros2android.android

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Pull only the newest service-owned bitmap while this view is attached. */
internal class DashboardImageView(
    context: Context,
    private val reading: () -> RobotSessionService.DashboardReading?,
    private val running: () -> Boolean,
) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val placeholder =
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.ros_muted))
            text = "Waiting for image"
        }
    private var displayed: android.graphics.Bitmap? = null
    private val refresh =
        object : Runnable {
            override fun run() {
                val active = running()
                val sample = if (active) reading() else null
                val bitmap = sample?.bitmap
                if (displayed !== bitmap) {
                    displayed = bitmap

                    if (bitmap == null) {
                        image.setImageDrawable(null)
                    } else {
                        image.setImageBitmap(bitmap)
                    }
                }

                placeholder.visibility = if (bitmap == null) VISIBLE else GONE
                val text =
                    if (!active) {
                        "Stopped"
                    } else {
                        sample?.value?.ifEmpty { "Waiting for image" } ?: "Waiting for image"
                    }
                if (placeholder.text.toString() != text) placeholder.text = text

                if (isAttachedToWindow) postOnAnimation(this)
            }
        }

    init {
        setBackgroundColor(context.getColor(R.color.ros_bg))
        addView(image, LayoutParams(-1, -1))
        addView(placeholder, LayoutParams(-1, -1))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        displayed = null
        image.setImageDrawable(null)
        super.onDetachedFromWindow()
    }
}
