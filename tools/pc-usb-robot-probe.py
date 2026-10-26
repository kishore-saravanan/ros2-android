#!/usr/bin/env python3
"""Observe external ROS delivery of phone battery and phone-hosted MCU messages."""
import argparse
import json
import time
import rclpy
from rclpy.qos import qos_profile_sensor_data
from std_msgs.msg import Float64, Int32, String

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--duration', type=float, default=20)
parser.add_argument('--continuous', action='store_true', help='Observe the full interval and require each stream to remain fresh')
args = parser.parse_args()
if not 1 <= args.duration <= 600:
    parser.error('Use duration 1–600 seconds')
rclpy.init()
node = rclpy.create_node('phone_usb_robot_observer')
counts = {'battery': 0, 'heartbeat': 0, 'reply': 0}
last = {}
max_gap = {key: 0.0 for key in counts}

def received(key, message):
    if key == 'reply' and 'ESP32 received phone battery:' not in message.data:
        return
    now = time.monotonic()
    if key in last:
        max_gap[key] = max(max_gap[key], now - last[key])
    last[key] = now
    counts[key] += 1

subscriptions = [node.create_subscription(kind, topic, lambda message, key=key: received(key, message), qos_profile_sensor_data)
    for key, kind, topic in [('battery', Float64, '/phone/battery_percent'),
                             ('heartbeat', Int32, '/esp32/heartbeat'),
                             ('reply', String, '/phone/display/text')]]
try:
    end = time.monotonic() + args.duration
    while time.monotonic() < end:
        rclpy.spin_once(node, timeout_sec=.1)
        if not args.continuous and all(count >= 3 for count in counts.values()):
            break
    passed = all(count >= 3 for count in counts.values())
    age = {key: time.monotonic() - last[key] if key in last else None for key in counts}
    if args.continuous:
        passed = passed and all(max_gap[key] < 10 and age[key] is not None and age[key] < 10 for key in counts)
    print(json.dumps({'passed': passed, 'counts': counts, 'maxGapSeconds': max_gap, 'ageSeconds': age}))
finally:
    node.destroy_node()
    rclpy.shutdown()
raise SystemExit(0 if passed else 1)
