#!/usr/bin/env python3
"""Publish synthetic image fixtures for DashboardImageTest. No camera/files read."""
import json
import io
import time
import rclpy
from PIL import Image
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage
from geometry_msgs.msg import Twist

rclpy.init()
node = rclpy.create_node('dashboard_image_test_source')
pub = node.create_publisher(CompressedImage, '/phone/dashboard_image_test', qos_profile_sensor_data)
motion = []
subscription = node.create_subscription(Twist, '/phone/dashboard_image_test/cmd_vel',
    lambda msg: motion.append((msg.linear.x, msg.angular.z)), qos_profile_sensor_data)
try:
    deadline = time.monotonic() + 90
    while pub.get_subscription_count() == 0 and time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=.1)
    if not pub.get_subscription_count():
        raise RuntimeError('Image test subscriber did not connect')
    time.sleep(2)  # Let the test assert its initial Waiting state.
    for encoding, color, size, seconds in [
        ('jpeg', 'red', (32, 24), 6),
        ('pause', '', (1, 1), 7),
        ('png', 'lime', (48, 32), 6),
        ('invalid', '', (1, 1), 3),
        ('jpeg', 'blue', (64, 48), 6),
    ]:
        data = b'not an image'
        if encoding in ('jpeg', 'png'):
            output = io.BytesIO()
            Image.new('RGB', size, color).save(output, format=encoding.upper())
            data = output.getvalue()
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if encoding != 'pause':
                msg = CompressedImage()
                msg.header.stamp = node.get_clock().now().to_msg()
                msg.format = encoding
                msg.data = data
                pub.publish(msg)
            rclpy.spin_once(node, timeout_sec=.01)
            time.sleep(.04)
        print(encoding, 'phase completed', flush=True)
    nonzero = [i for i, value in enumerate(motion) if value != (0., 0.)]
    passed = len(nonzero) >= 4 and any(value == (0., 0.) for value in motion[nonzero[-1] + 1:]) if nonzero else False
    print(json.dumps({'image_motion_coexistence': passed, 'motion_samples': len(motion),
                      'nonzero_samples': len(nonzero), 'topic': '/phone/dashboard_image_test/cmd_vel'}), flush=True)
    if not passed:
        raise RuntimeError('Concurrent joystick samples/release-to-zero not observed')
finally:
    node.destroy_node()
    rclpy.shutdown()
