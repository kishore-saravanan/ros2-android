#!/usr/bin/env python3
"""Observe /phone/battery_percent with ordinary ROS discovery and no static peers."""
import argparse
import json
import math
import os
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--duration', type=float, default=60)
    args = parser.parse_args()
    if not 1 <= args.duration <= 120:
        parser.error('Duration must be 1–120 seconds')
    for name in ('ROS_STATIC_PEERS', 'CYCLONEDDS_URI', 'FASTRTPS_DEFAULT_PROFILES_FILE',
                 'FASTDDS_DEFAULT_PROFILES_FILE', 'ROS_DISCOVERY_SERVER'):
        if os.environ.get(name):
            parser.error(f'Unset {name} to test automatic discovery without a network profile')
    if os.environ.get('ROS_LOCALHOST_ONLY') == '1' or os.environ.get('ROS_AUTOMATIC_DISCOVERY_RANGE', 'SUBNET') not in ('SUBNET', 'SYSTEM_DEFAULT'):
        parser.error('Use subnet discovery, not localhost-only or disabled discovery')
    import rclpy
    from rclpy.qos import qos_profile_sensor_data
    from std_msgs.msg import Float64
    rclpy.init()
    node = rclpy.create_node('automatic_phone_discovery_observer')
    samples = []

    def received(message):
        if math.isfinite(message.data) and 0 <= message.data <= 100:
            samples.append(message.data)

    subscription = node.create_subscription(Float64, '/phone/battery_percent', received, qos_profile_sensor_data)
    end = time.monotonic() + args.duration
    try:
        while len(samples) < 3 and time.monotonic() < end:
            rclpy.spin_once(node, timeout_sec=.1)
        passed = len(samples) >= 3
        print(json.dumps({'passed': passed, 'samples': len(samples)}))
    finally:
        node.destroy_subscription(subscription)
        node.destroy_node()
        rclpy.shutdown()
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
