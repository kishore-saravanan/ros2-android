"""Clock calibration for camera measurements; no ROS or pixel access."""
import math
import subprocess
import time


def estimate_adb_clock_offset(adb='adb', serial=None, samples=20):
    """Return phone-minus-PC offset and sampling uncertainty in milliseconds.

    The phone clock read occurs inside the ADB round trip. Half that interval
    bounds sampling uncertainty; it does not bound future drift or sensor-clock
    conversion error. Reject local wall-clock jumps during calibration.
    """
    if samples < 1:
        raise ValueError('Clock calibration needs at least one sample')
    best = None
    for _ in range(samples):
        before = time.time()
        mono_before = time.monotonic()
        try:
            result = subprocess.run([str(adb), *(['-s', serial] if serial else []),
                                     'shell', 'echo $EPOCHREALTIME'],
                                    capture_output=True, text=True, timeout=15, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise ValueError('ADB clock calibration failed; check the selected development connection') from None
        mono_elapsed = time.monotonic() - mono_before
        after = time.time()
        elapsed = after - before
        if result.returncode:
            raise ValueError('ADB could not read the selected phone clock')
        try:
            phone = float(result.stdout.strip().replace(',', '.'))
        except ValueError:
            raise ValueError('Phone shell did not return a numeric epoch clock') from None
        if not math.isfinite(phone) or phone <= 0:
            raise ValueError('Phone shell returned an invalid epoch clock')
        if elapsed < 0 or abs(elapsed - mono_elapsed) > .005:
            raise ValueError('PC clock changed during calibration; repeat after clock synchronization')
        if best is None or elapsed < best[0]:
            best = elapsed, phone - (before + after) / 2
    return best[1] * 1000, best[0] * 500
