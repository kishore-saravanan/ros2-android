#!/usr/bin/env python3
"""Validate camera ROS messages without saving or displaying camera images."""
import argparse
import json
import time
import rclpy
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage

parser = argparse.ArgumentParser()
parser.add_argument('--duration', type=float, default=90)
parser.add_argument('--topic', action='append', required=True)
args = parser.parse_args()
rclpy.init()
node = Node('robot_brain_camera_probe')
received = {topic: 0 for topic in args.topic}


def callback(topic, message):
    valid = len(message.data) > 4 and bytes(message.data[:2]) == bytes.fromhex('ffd8') and bytes(message.data[-2:]) == bytes.fromhex('ffd9')
    if not valid:
        print(json.dumps({'topic': topic, 'invalid_jpeg': True}), flush=True)
        return
    received[topic] += 1
    if received[topic] == 1:
        print(json.dumps({'topic': topic, 'format': message.format, 'bytes': len(message.data), 'frame_id': message.header.frame_id, 'jpeg_valid': True}), flush=True)


subscriptions = [node.create_subscription(CompressedImage, topic, lambda msg, t=topic: callback(t, msg), qos_profile_sensor_data) for topic in args.topic]
print('Camera probe ready; validates messages without retaining images', flush=True)
deadline = time.monotonic() + args.duration
try:
    while time.monotonic() < deadline and not all(count >= 3 for count in received.values()):
        rclpy.spin_once(node, timeout_sec=0.1)
finally:
    print(json.dumps({'camera_counts': received}), flush=True)
    node.destroy_node()
    rclpy.shutdown()
raise SystemExit(0 if all(count >= 3 for count in received.values()) else 1)
