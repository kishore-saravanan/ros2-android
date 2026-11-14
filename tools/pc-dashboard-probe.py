#!/usr/bin/env python3
"""Publish reserved test telemetry for DashboardSubscriberTest; no actuator topics."""

import time
import rclpy
from rclpy.node import Node
from std_msgs.msg import String, Bool, Int32, Int64, Float32, Float64


def main():
    rclpy.init()
    node = Node('android_dashboard_probe')
    specs = [
        (String, 'string', 'Telemetry 🤖'),
        (Bool, 'bool', True),
        (Int32, 'int32', 42),
        (Int64, 'int64', 9223372036854775806),
        (Float32, 'float32', -0.25),
        (Float64, 'float64', 0.625),
    ]
    publishers = [
        (node.create_publisher(cls, '/phone/dashboard_test/' + key, 1), cls, value)
        for cls, key, value in specs
    ]
    display = node.create_publisher(String, '/phone/dashboard_test/display', 1)
    try:
        deadline = time.monotonic() + 100

        while not all(pub.get_subscription_count() for pub, _, _ in publishers):
            if time.monotonic() > deadline:
                raise RuntimeError('Phone dashboard subscribers did not appear')

            rclpy.spin_once(node, timeout_sec=0.1)

        print('All six phone subscribers matched', flush=True)
        time.sleep(2)  # Observe Waiting before publishing the first sample.

        for label, duration in [('live', 5), ('pause', 7), ('resumed', 8)]:
            print(label, flush=True)
            deadline = time.monotonic() + duration
            while time.monotonic() < deadline:
                if label != 'pause':
                    for pub, cls, value in publishers:
                        pub.publish(
                            cls(data=False if cls is Bool and label == 'resumed' else value)
                        )

                    display.publish(String(data='Legacy display okay'))

                rclpy.spin_once(node, timeout_sec=0.2)

        print('Telemetry sequence completed', flush=True)
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    main()
