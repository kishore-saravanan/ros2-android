#!/usr/bin/env python3
"""Show a live ROS compressed camera topic without saving frames."""
import argparse
import cv2
import numpy as np
import rclpy
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--topic', default='/phone/camera_back_0/image/compressed')
args = parser.parse_args()
rclpy.init()
node = rclpy.create_node('robot_brain_camera_viewer')
latest = None

def receive(message):
    global latest
    latest = cv2.imdecode(np.frombuffer(message.data, dtype=np.uint8), cv2.IMREAD_COLOR)

subscription = node.create_subscription(CompressedImage, args.topic, receive, qos_profile_sensor_data)
cv2.namedWindow('Robot Brain - live camera (Q to close)', cv2.WINDOW_NORMAL)
received = False
try:
    while rclpy.ok():
        rclpy.spin_once(node, timeout_sec=0.02)
        if latest is not None:
            cv2.imshow('Robot Brain - live camera (Q to close)', latest)
            if not received:
                print(f'Live preview: {latest.shape[1]}x{latest.shape[0]}', flush=True)
                received = True
        if cv2.waitKey(1) & 0xFF in (ord('q'), 27):
            break
except KeyboardInterrupt:
    pass
finally:
    cv2.destroyAllWindows()
    node.destroy_node()
    if rclpy.ok():
        rclpy.shutdown()
