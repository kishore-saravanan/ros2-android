#!/usr/bin/env python3
"""Expose simulated TurtleBot odometry as one standard Float64 dashboard value."""
import time
from math import hypot
import rclpy
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from nav_msgs.msg import Odometry
from std_msgs.msg import Float64


class Telemetry(Node):
    def __init__(self):
        super().__init__('dashboard_telemetry', namespace='/tb4_demo')
        self.value = 0.0
        self.received = None
        self.publisher = self.create_publisher(Float64, 'dashboard/speed', 1)
        self.subscription = self.create_subscription(Odometry, 'odom', self.observe, qos_profile_sensor_data)
        self.timer = self.create_timer(0.2, self.publish)

    def observe(self, message):
        velocity = message.twist.twist.linear
        self.value = round(hypot(velocity.x, velocity.y), 3)
        self.received = time.monotonic()

    def publish(self):
        # Do not turn old odometry into apparently fresh telemetry.
        if self.received is not None and time.monotonic() - self.received < 1.0:
            self.publisher.publish(Float64(data=self.value))


def main():
    rclpy.init()
    node = Telemetry()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == '__main__':
    main()
