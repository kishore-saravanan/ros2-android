#!/usr/bin/env python3
"""Verify real Android project ROS traffic, command echo and optional on-phone IMU processing."""
import argparse
import collections
import json
import math
import secrets
import time

import rclpy
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import Imu
from std_msgs.msg import Float64, String

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--echo-prefix', default='phone C++ processed: ')
parser.add_argument('--namespace', default='/demo')
parser.add_argument('--expected-text', default='Hello from ROS2 Android C++')
parser.add_argument('--status-topic', help='Override remapped status topic')
parser.add_argument('--require-imu', action='store_true')
parser.add_argument('--require-cpp', action='store_true', help='Require Python→phone C++→Python reply')
parser.add_argument('--custom-message', action='store_true', help='Requires the generated demo_interfaces PC overlay')
parser.add_argument('--duration', type=float, default=25)
args = parser.parse_args()
rclpy.init()
node = rclpy.create_node('ros2_android_project_probe')
ns = args.namespace.rstrip('/')
token = 'project-probe-' + secrets.token_hex(4)
raw_magnitudes = collections.deque(maxlen=100)
results = {'status': False, 'echo': False, 'imu': False, 'processed_magnitude': False}
last_status = None


def status(message):
    global last_status
    last_status = message.data
    if args.expected_text in message.data:
        results['status'] = True


def echo(message):
    if message.data == args.echo_prefix + token:
        results['echo'] = True


def imu(message):
    a = message.linear_acceleration
    raw_magnitudes.append(math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z))
    results['imu'] = True


def magnitude(message):
    if math.isfinite(message.data) and any(abs(value - message.data) < .05 for value in raw_magnitudes):
        results['processed_magnitude'] = True


subscriptions = [
    node.create_subscription(String, args.status_topic or ns + '/status', status, 10),
    node.create_subscription(String, ns + '/echo', echo, 10),
    node.create_subscription(Imu, '/phone/imu', imu, qos_profile_sensor_data),
    node.create_subscription(Float64, ns + '/acceleration_magnitude', magnitude, qos_profile_sensor_data),
]
if args.custom_message:
    from demo_interfaces.msg import RuntimeStatus
    results['custom_message'] = False
    def telemetry(message):
        if message.message == args.expected_text and message.tick > 0 and (not args.require_imu or message.imu_samples > 0):
            results['custom_message'] = True
    subscriptions.append(node.create_subscription(RuntimeStatus, ns + '/runtime_status', telemetry, 10))
if args.require_cpp:
    results['cpp_reply'] = False
    def cpp_reply(message):
        if message.data == 'phone C++ processed: ' + token:
            results['cpp_reply'] = True
    subscriptions.append(node.create_subscription(String, ns + '/cpp_reply', cpp_reply, 10))
publisher = node.create_publisher(String, ns + '/command', 10)
timer = node.create_timer(.2, lambda: publisher.publish(String(data=token)))
deadline = time.monotonic() + args.duration
required = ['status', 'echo'] + (['imu', 'processed_magnitude'] if args.require_imu else []) + (['custom_message'] if args.custom_message else []) + (['cpp_reply'] if args.require_cpp else [])
try:
    while rclpy.ok() and time.monotonic() < deadline and not all(results[key] for key in required):
        rclpy.spin_once(node, timeout_sec=.1)
finally:
    print(json.dumps({'verified': results, 'last_status': last_status, 'imu_samples': len(raw_magnitudes)}, ensure_ascii=False), flush=True)
    node.destroy_node()
    if rclpy.ok():
        rclpy.shutdown()
raise SystemExit(0 if all(results[key] for key in required) else 1)
