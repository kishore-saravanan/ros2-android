#!/usr/bin/env python3
"""Read-only PC observer for ControlsTest on reserved /phone/control_test topics."""

import argparse
import time, json, rclpy
from rclpy.node import Node
from std_msgs.msg import Bool, String, Float64
from geometry_msgs.msg import Twist

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--timeout', type=float, default=100.0)
args = parser.parse_args()
if not 1 <= args.timeout <= 600:
    parser.error('--timeout must be 1 to 600 seconds')

rclpy.init()
n = Node('android_controls_probe')
seen = {}
motion = []


def record_bool(m):
    if m.data:
        seen['bool'] = True


def record_string(m):
    if m.data == 'Hello 🤖':
        seen['utf8_string'] = True


def record_slider(m):
    if abs(m.data - 0.625) < 1e-9:
        seen['slider'] = True


def record_twist(m):
    if abs(m.linear.x - 0.2) < 1e-6 and abs(m.angular.z + 0.4) < 1e-6:
        seen['twist'] = True
        motion.append(('move', time.monotonic()))
    elif m.linear.x == 0 and m.angular.z == 0 and motion:
        motion.append(('zero', time.monotonic()))
        seen['zero_after_motion'] = True


subs = [
    n.create_subscription(Bool, '/phone/control_test/bool', record_bool, 10),
    n.create_subscription(String, '/phone/control_test/string', record_string, 10),
    n.create_subscription(Float64, '/phone/control_test/slider', record_slider, 10),
    n.create_subscription(Twist, '/phone/control_test/twist', record_twist, 10),
]
end = time.monotonic() + args.timeout
while time.monotonic() < end:
    rclpy.spin_once(n, timeout_sec=0.1)

    if len(seen) == 5 and motion and motion[-1][0] == 'zero':
        zeros = [i for i, (kind, _) in enumerate(motion) if kind == 'zero']
        # One zero from command expiry, then fresh motion and a second zero from pause.
        if any(
            any(kind == 'move' for kind, _ in motion[i + 1 : j])
            for i in zeros
            for j in zeros
            if j > i
        ):
            break
else:
    print(json.dumps({'passed': False, 'seen': seen, 'motion': motion}))
    raise SystemExit(1)

print(
    json.dumps(
        {
            'passed': True,
            'seen': seen,
            'stop_transitions': len(
                [
                    1
                    for i in range(1, len(motion))
                    if motion[i][0] == 'zero' and motion[i - 1][0] == 'move'
                ]
            ),
        }
    )
)
n.destroy_node()
rclpy.shutdown()
