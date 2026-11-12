package dev.ros2android.android

import dev.ros2android.health.HealthText
import dev.ros2android.health.PauseDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHealthTest {
    @Test fun regularWakeupsAndOrdinaryJitterAreNotPauses() {
        val detector = PauseDetector(periodMs = 250, thresholdMs = 1000)
        detector.reset(10_000)
        var now = 10_000L
        repeat(40) { now += 250 + (it % 3) * 200; detector.tick(now) } // up to 400 ms late
        assertEquals(PauseDetector.Snapshot(), detector.snapshot)
    }

    @Test fun reportsEachProcessPauseWithLongestAndTotal() {
        val detector = PauseDetector(periodMs = 250, thresholdMs = 1000)
        detector.reset(0)
        detector.tick(250)
        detector.tick(250 + 250 + 2200) // frozen 2.2 s
        detector.tick(2950 + 250)
        detector.tick(3200 + 250 + 1000) // exactly at threshold counts
        val snapshot = detector.snapshot
        assertEquals(2, snapshot.pauses)
        assertEquals(2200, snapshot.longestMs)
        assertEquals(3200, snapshot.totalMs)
        assertEquals(4450, snapshot.lastEndedAt)
    }

    @Test fun resetStartsANewRunWithoutAPhantomPauseFromIdleTime() {
        val detector = PauseDetector(periodMs = 250, thresholdMs = 1000)
        detector.reset(0); detector.tick(5000)
        assertEquals(1, detector.snapshot.pauses)
        detector.reset(60_000) // long idle gap between runs must not count
        detector.tick(60_250)
        assertEquals(PauseDetector.Snapshot(), detector.snapshot)
    }

    @Test fun operatorTextIsConciseAndOnlyShownForPauses() {
        assertNull(HealthText.pauseWarning("this project", 0, 0))
        assertEquals("Android paused this project once (longest 2.2 s). Allow background activity in Android app settings.",
            HealthText.pauseWarning("this project", 1, 2200))
        assertTrue(HealthText.pauseWarning("ROS2 Android", 3, 1049)!!.startsWith("Android paused ROS2 Android 3 times (longest 1.0 s)"))
        assertTrue(HealthText.policy(true, false).startsWith("Battery optimized"))
        assertTrue(HealthText.policy(false, false).startsWith("Background activity allowed"))
        assertTrue(HealthText.policy(false, true).startsWith("Background use is restricted"))
        assertTrue(HealthText.policy(null, null).contains("unavailable"))
    }
}
