#!/usr/bin/env python3
"""Observe actual phone IMU traffic and reply only after receipt; no simulated sensor data."""
import argparse
import json
import time

import rclpy
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import Imu
from std_msgs.msg import String

parser = argparse.ArgumentParser()
parser.add_argument('--duration', type=float, default=90)
parser.add_argument('--text', default='Robot Brain PC round trip verified')
options = parser.parse_args()
rclpy.init()
node = Node('robot_brain_pc_probe')
state = {'received': 0, 'reply_sent': 0, 'first_sample': None}


def received(message):
    state['received'] += 1
    if state['first_sample'] is None:
        state['first_sample'] = {
            'frame_id': message.header.frame_id,
            'acceleration': [message.linear_acceleration.x, message.linear_acceleration.y, message.linear_acceleration.z],
            'gyro': [message.angular_velocity.x, message.angular_velocity.y, message.angular_velocity.z],
            'orientation_unavailable': bool(message.orientation_covariance[0] == -1),
        }
        print(json.dumps({'first_phone_imu': state['first_sample']}), flush=True)


subscription = node.create_subscription(Imu, '/phone/imu', received, qos_profile_sensor_data)
publisher = node.create_publisher(String, '/phone/display/text', 10)


def reply():
    if state['received']:
        publisher.publish(String(data=options.text))
        state['reply_sent'] += 1


timer = node.create_timer(0.25, reply)
print('PC probe ready: awaiting actual /phone/imu; replies on /phone/display/text', flush=True)
deadline = time.monotonic() + options.duration
try:
    while time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=0.1)
finally:
    print(json.dumps(state), flush=True)
    node.destroy_node()
    rclpy.shutdown()
raise SystemExit(0 if state['received'] and state['reply_sent'] else 1)
