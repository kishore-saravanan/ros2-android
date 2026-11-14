#!/usr/bin/env python3
"""Verify camera JPEG dimensions over ROS; never save or display camera frames."""

import io
import json
import time
from PIL import Image
import rclpy
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage

rclpy.init()
node = rclpy.create_node('camera_options_metadata_observer')
samples = []
errors = []


def receive(msg):
    try:
        with Image.open(io.BytesIO(bytes(msg.data))) as image:
            samples.append((image.width, image.height, image.format))
    except Exception as error:
        errors.append(type(error).__name__)


subscription = node.create_subscription(
    CompressedImage,
    '/phone/camera_options_test/image/compressed',
    receive,
    qos_profile_sensor_data,
)

try:
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline and len(samples) < 20:
        rclpy.spin_once(node, timeout_sec=0.1)

    passed = len(samples) >= 5 and not errors and all(fmt == 'JPEG' for _, _, fmt in samples)
    print(
        json.dumps(
            {
                'passed': passed,
                'samples': len(samples),
                'dimensions': sorted(set(samples)),
                'decode_errors': errors,
            }
        )
    )

    if not passed:
        raise SystemExit(1)
finally:
    node.destroy_node()
    rclpy.shutdown()
