#!/usr/bin/env python3
"""Show live ROS JPEG frames and received FPS without saving images."""
import argparse
import time

import cv2
import numpy as np
import rclpy
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--topic', default='/phone/camera_back_0/image/compressed')
parser.add_argument('--duration', type=float, help='Close automatically after this many seconds')
args = parser.parse_args()
rclpy.init()
node = rclpy.create_node('ros2_android_camera_viewer')
latest = None
count = 0


def receive(message):
    global latest, count
    latest = bytes(message.data)
    count += 1


subscription = node.create_subscription(CompressedImage, args.topic, receive, qos_profile_sensor_data)
title = 'ROS2 Android - live camera (Q to close)'
cv2.namedWindow(title, cv2.WINDOW_NORMAL)
cv2.resizeWindow(title, 960, 720)
waiting = np.zeros((480, 640, 3), dtype=np.uint8)
cv2.putText(waiting, 'Waiting for camera topic...', (30, 240), cv2.FONT_HERSHEY_SIMPLEX, .8, (100, 226, 188), 2)
cv2.imshow(title, waiting)
last_frame = None
started = last_report = time.monotonic()
last_count = 0
fps = 0.0
received = False
try:
    while rclpy.ok():
        # Drain ready samples, retain one newest frame, and keep GUI events responsive.
        rclpy.spin_once(node, timeout_sec=0.005)
        now = time.monotonic()
        if now - last_report >= 1:
            fps = (count - last_count) / (now - last_report)
            last_report = now
            last_count = count
        if latest is not None and latest is not last_frame:
            last_frame = latest
            frame = cv2.imdecode(np.frombuffer(last_frame, dtype=np.uint8), cv2.IMREAD_COLOR)
            if frame is not None:
                cv2.rectangle(frame, (0, 0), (frame.shape[1], 34), (16, 23, 29), -1)
                cv2.putText(frame, f'{frame.shape[1]}x{frame.shape[0]} | received {fps:.1f} FPS | Q: close',
                            (10, 23), cv2.FONT_HERSHEY_SIMPLEX, .55, (188, 226, 100), 1)
                cv2.imshow(title, frame)
                if not received:
                    print(f'Live preview: {frame.shape[1]}x{frame.shape[0]}', flush=True)
                    received = True
        if cv2.waitKey(1) & 0xFF in (ord('q'), 27):
            break
        if cv2.getWindowProperty(title, cv2.WND_PROP_VISIBLE) < 1:
            break
        if args.duration is not None and now - started >= args.duration:
            break
except KeyboardInterrupt:
    pass
finally:
    cv2.destroyAllWindows()
    node.destroy_node()
    if rclpy.ok():
        rclpy.shutdown()
