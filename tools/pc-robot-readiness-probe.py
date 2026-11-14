#!/usr/bin/env python3
"""Read-only check of robot firmware topics; never publishes velocity."""

import argparse
import json
import time
import rclpy
from rclpy.qos import qos_profile_sensor_data
from std_msgs.msg import Float64, Int32, String

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--duration', type=float, default=30)
args = parser.parse_args()
if not 1 <= args.duration <= 120:
    parser.error('Use a duration of 1–120 seconds')

rclpy.init()
node = rclpy.create_node('isolated_robot_readiness_observer')
heartbeats, limits, angular_limits, replies = [], [], [], []
subscriptions = [
    node.create_subscription(
        Float64,
        '/robot/angular_limit/current',
        lambda m: angular_limits.append(m.data),
        qos_profile_sensor_data,
    ),
    node.create_subscription(
        Int32, '/esp32/heartbeat', lambda m: heartbeats.append(m.data), qos_profile_sensor_data
    ),
    node.create_subscription(
        Int32, '/robot/pwm_limit/current', lambda m: limits.append(m.data), qos_profile_sensor_data
    ),
    node.create_subscription(
        String,
        '/phone/display/text',
        lambda m: (
            replies.append(m.data) if m.data.startswith('ESP32 received phone battery:') else None
        ),
        qos_profile_sensor_data,
    ),
]
checks = {}
try:
    deadline = time.monotonic() + args.duration
    while time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=0.1)
        expected = {
            '/cmd_vel': 'geometry_msgs/msg/Twist',
            '/robot/pwm_limit': 'std_msgs/msg/Int32',
            '/robot/angular_limit': 'std_msgs/msg/Float64',
        }
        checks = {
            topic: any(
                info.topic_type == kind for info in node.get_subscriptions_info_by_topic(topic)
            )
            for topic, kind in expected.items()
        }
        checks.update(
            {
                'fresh_heartbeat': len(set(heartbeats)) >= 3,
                'angular_configuration': len(angular_limits) >= 3
                and all(0 <= value <= 6 for value in angular_limits),
                'pwm_configuration': len(limits) >= 3
                and all(0 <= value <= 255 for value in limits),
                'battery_round_trip': len(replies) >= 3,
            }
        )

        if all(checks.values()):
            break

    passed = all(checks.values())
    print(
        json.dumps(
            {
                'passed': passed,
                'checks': checks,
                'heartbeat_samples': len(heartbeats),
                'pwm_samples': len(limits),
                'battery_replies': len(replies),
            }
        )
    )
finally:
    node.destroy_node()
    rclpy.shutdown()

raise SystemExit(0 if passed else 1)
