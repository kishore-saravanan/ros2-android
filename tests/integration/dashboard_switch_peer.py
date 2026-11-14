#!/usr/bin/env python3
"""Independent ROS peer for DashboardSwitchTest; use isolated ROS_DOMAIN_ID=87.

No actuator topics. Counts every received request so an unintended replay fails
on the phone. The observed value is test telemetry, not a robot acknowledgement.
"""

import time

import rclpy
from rclpy.node import Node
from std_msgs.msg import Bool, String


def main():
    rclpy.init()
    node = Node('dashboard_switch_test_peer')
    events = []
    publisher = node.create_publisher(String, '/ros2android/switch_test/observed', 1)

    def receive(message):
        events.append(message.data)
        print(f'Request {len(events)}: {message.data}', flush=True)

    subscription = node.create_subscription(Bool, '/ros2android/switch_test/request', receive, 10)
    timer = node.create_timer(
        0.1,
        lambda: publisher.publish(
            String(data=f'{len(events)}:{str(events[-1]).lower() if events else "none"}')
        ),
    )
    deadline = time.monotonic() + 180
    completed = None
    try:
        while time.monotonic() < deadline:
            rclpy.spin_once(node, timeout_sec=0.1)

            if len(events) >= 4:
                if events != [True, False, True, False]:
                    raise RuntimeError(f'Unexpected request/replay sequence: {events}')

                completed = completed or time.monotonic()
                if time.monotonic() - completed > 5:
                    print('PASS: ON/OFF, navigation and restart without replay', flush=True)
                    return

        raise TimeoutError(f'Switch test incomplete: {events}')
    finally:
        node.destroy_timer(timer)
        node.destroy_subscription(subscription)
        node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    main()
