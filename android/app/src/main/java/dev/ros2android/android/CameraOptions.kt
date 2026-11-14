package dev.ros2android.android

/** Zero dimensions select the balanced default; explicit dimensions must be camera-supported. */
data class CameraOptions(val width: Int = 0, val height: Int = 0, val jpegQuality: Int = 65) {
    init {
        require(
            (width == 0 && height == 0) ||
                (width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        ) {
            "Choose a supported camera resolution"
        }

        require(jpegQuality in 0..100) { "JPEG quality must be between 0 and 100" }
    }
}

internal object CameraRatePolicy {
    fun select(ranges: List<Pair<Int, Int>>, requested: Double): Pair<Int, Int> {
        require(requested.isFinite() && (requested == 0.0 || requested >= 0.2)) {
            "Use 0 for automatic camera FPS, or at least 0.2 fps"
        }

        require(ranges.isNotEmpty()) { "Camera exposes no normal capture FPS ranges" }

        if (requested == 0.0) {
            return ranges.maxWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
        }

        return ranges
            .filter { it.second >= requested }
            .minWithOrNull(
                compareBy<Pair<Int, Int>> { it.second }
                    .thenBy { kotlin.math.abs(it.first - requested) }
            ) ?: ranges.maxWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }
}
