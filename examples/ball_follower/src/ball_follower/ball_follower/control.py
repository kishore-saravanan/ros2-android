# SPDX-License-Identifier: Apache-2.0
"""Stateless visual-servo law; distance is apparent size, not metric depth."""

import math


def command(
    target,
    age,
    *,
    stop_fraction=0.30,
    max_linear=0.12,
    max_angular=2.5,
    target_center_x=0.5,
    linear_gain=1.2,
    full_pwm=False,
    angular_pwm_limit=0.8,
):
    if not math.isfinite(age) or age < -0.05 or age > 0.30 or not target.get('found', False):
        return 0.0, 0.0

    error = target.get('error_x', float('nan'))
    size = target.get('diameter_fraction', float('nan'))
    if not all(isinstance(v, (float, int)) and math.isfinite(v) for v in (error, size)):
        return 0.0, 0.0

    if abs(error) > 1 or not 0 < size <= 1 or size >= stop_fraction:
        return 0.0, 0.0

    error -= 2 * target_center_x - 1

    if full_pwm:
        return (
            1.0 if abs(error) <= 0.08 else 0.0,
            math.copysign(angular_pwm_limit, -error) if abs(error) > 0.08 else 0.0,
        )

    # Image right -> ROS negative angular Z. Turn first when well off-centre.
    turn = max(-max_angular, min(max_angular, -3.0 * error)) if abs(error) > 0.08 else 0.0
    forward = (
        min(max_linear, max(0.0, (stop_fraction - size) * linear_gain))
        if abs(error) < 0.30
        else 0.0
    )
    return forward, turn


class TurnPulse:
    """Turn, brake, then require an image captured after settling before moving."""

    def __init__(self, duration=0.04, settle=0.15, max_duration=None):
        max_duration = duration if max_duration is None else max_duration
        if (
            not all(math.isfinite(v) and v > 0 for v in (duration, settle, max_duration))
            or max_duration < duration
        ):
            raise ValueError('Turn pulse and settling time must be positive')

        self.duration, self.settle, self.max_duration = duration, settle, max_duration
        self.reset()

    def duration_for_error(self, error):
        # Preserve the proven short pulse near alignment; use a longer pulse
        # only for large image-space errors. Never change an active deadline.
        if not math.isfinite(error):
            return self.duration

        weight = max(0.0, min(1.0, (abs(error) - 0.30) / 0.50))
        return self.duration + weight * (self.max_duration - self.duration)

    def reset(self):
        self.phase = 'idle'
        self.until = 0.0
        self.fresh_after = self.last_stamp = 0
        self.turn = 0.0

    def step(self, linear, angular, now, stamp_ns, ros_now_ns, error=0.0):
        if self.phase == 'turn':
            if now < self.until and angular * self.turn > 0:
                return 0.0, math.copysign(min(abs(angular), abs(self.turn)), angular)

            self.phase = 'settle'
            self.until = now + self.settle
            self.fresh_after = ros_now_ns + int(self.settle * 1e9)
            return 0.0, 0.0

        if self.phase == 'settle':
            if now < self.until or stamp_ns < self.fresh_after:
                return 0.0, 0.0

            self.phase = 'idle'

        if angular:
            if stamp_ns <= self.last_stamp:
                return 0.0, 0.0

            self.last_stamp, self.turn = stamp_ns, angular
            self.phase, self.until = 'turn', now + self.duration_for_error(error)
            return 0.0, angular

        return linear, 0.0


class DetectionGate:
    """Require consecutive, spatially consistent detections; misses stop immediately."""

    def __init__(self, frames=3):
        if not isinstance(frames, int) or not 1 <= frames <= 10:
            raise ValueError('confirmation_frames must be 1..10')

        self.frames, self.streak, self.previous = frames, 0, None

    def accept(self, target):
        if not target.get('found'):
            self.streak, self.previous = 0, None
            return False

        current = (
            target['center_x'] / target['width'],
            target['center_y'] / target['height'],
            target['diameter_fraction'],
        )
        matches = (
            self.previous is not None
            and math.hypot(current[0] - self.previous[0], current[1] - self.previous[1]) <= 0.15
            and 0.6 <= current[2] / self.previous[2] <= 1.7
        )
        self.streak = self.streak + 1 if matches else 1
        self.previous = current
        return self.streak >= self.frames
