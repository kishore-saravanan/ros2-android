# SPDX-License-Identifier: Apache-2.0
"""Stateless visual-servo law; distance is apparent size, not metric depth."""
import math


def command(target, age, *, stop_fraction=.30, max_linear=.12, max_angular=2.5, target_center_x=.5):
    if not math.isfinite(age) or age < -.05 or age > .30 or not target.get('found', False):
        return 0., 0.
    error = target.get('error_x', float('nan'))
    size = target.get('diameter_fraction', float('nan'))
    if not all(isinstance(v, (float, int)) and math.isfinite(v) for v in (error, size)):
        return 0., 0.
    if abs(error) > 1 or not 0 < size <= 1 or size >= stop_fraction:
        return 0., 0.
    error -= 2*target_center_x-1
    # Image right -> ROS negative angular Z. Turn first when well off-centre.
    turn = max(-max_angular, min(max_angular, -3.0 * error)) if abs(error) > .08 else 0.
    forward = min(max_linear, max(0., (stop_fraction - size) * .6)) if abs(error) < .30 else 0.
    return forward, turn
