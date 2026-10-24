#!/usr/bin/env python3
"""Measure received JPEG FPS and bandwidth without saving camera images."""
import argparse
import json
import time

import rclpy
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--topic', default='/phone/camera_back_0/image/compressed')
parser.add_argument('--duration', type=float, default=30)
parser.add_argument('--warmup', type=float, default=5)
parser.add_argument('--wait', type=float, default=90)
parser.add_argument('--min-fps', type=float, default=15)
parser.add_argument('--decode', action='store_true', help='Also verify every JPEG can be decoded; never saves pixels')
args = parser.parse_args()
if args.duration <= 0 or args.warmup < 0 or args.wait <= 0:
    parser.error('Duration and wait must be positive; warmup cannot be negative')
if args.decode:
    import cv2
    import numpy as np
rclpy.init()
node = rclpy.create_node('ros2_android_camera_benchmark')
first = None
start = None
count = 0
invalid = 0
byte_count = 0
last = None
gaps = []
shape = None


def receive(message):
    global first, start, count, invalid, byte_count, last, shape, report_at
    now = time.monotonic()
    if first is None:
        first = now
        start = first + args.warmup
        report_at = start + 10
        print(json.dumps({'state': 'warming_up', 'seconds': args.warmup}), flush=True)
    if now < start or now >= start + args.duration:
        return
    data = bytes(message.data)
    valid = message.format.lower().endswith('jpeg') and data[:2] == b'\xff\xd8' and data[-2:] == b'\xff\xd9'
    if valid and args.decode:
        frame = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
        valid = frame is not None
        if valid:
            shape = [int(frame.shape[1]), int(frame.shape[0])]
    if not valid:
        invalid += 1
        return
    if last is not None:
        gaps.append((now - last) * 1000)
    last = now
    count += 1
    byte_count += len(data)


subscription = node.create_subscription(CompressedImage, args.topic, receive, qos_profile_sensor_data)
waiting_deadline = time.monotonic() + args.wait
report_at = time.monotonic() + 10
print(json.dumps({'state': 'waiting', 'topic': args.topic}), flush=True)
try:
    while rclpy.ok():
        rclpy.spin_once(node, timeout_sec=0.05)
        now = time.monotonic()
        if first is None and now >= waiting_deadline:
            break
        if start is not None and now >= start + args.duration:
            break
        if now >= report_at and start is not None and now > start:
            print(json.dumps({'elapsed_s': round(now - start, 1), 'received': count, 'fps': round(count / (now - start), 2)}), flush=True)
            report_at = now + 10
finally:
    elapsed = max(0.0, min(args.duration, time.monotonic() - start)) if start else 0.0
    fps = count / elapsed if elapsed else 0.0
    ordered = sorted(gaps)
    result = {
        'topic': args.topic, 'duration_s': round(elapsed, 3), 'received': count,
        'fps': round(fps, 3), 'invalid': invalid, 'resolution': shape,
        'payload_mbit_s': round(byte_count * 8 / elapsed / 1e6, 3) if elapsed else 0,
        'gap_p95_ms': round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))], 2) if ordered else None,
        'gap_max_ms': round(max(gaps), 2) if gaps else None,
    }
    print(json.dumps({'summary': result}), flush=True)
    node.destroy_node()
    if rclpy.ok():
        rclpy.shutdown()
raise SystemExit(0 if fps >= args.min_fps and invalid == 0 and elapsed >= args.duration - .1 else 1)
