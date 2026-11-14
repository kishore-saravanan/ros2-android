"""User-editable ROS 2 Python node. Runs entirely on the phone."""

import math
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from demo_interfaces.msg import RuntimeStatus
from sensor_msgs.msg import Imu
from std_msgs.msg import Float64, String


class PhoneNode(Node):
    def __init__(self, *, context, namespace, cli_args):
        super().__init__(
            'python_demo',
            context=context,
            namespace=namespace,
            cli_args=cli_args,
            enable_rosout=False,
        )
        self.declare_parameter('message', 'Hello from Android Python')
        self.declare_parameter('fail_after_ticks', 0)
        self.declare_parameter('cpp_bridge_enabled', False)
        cpp_namespace = self.declare_parameter('cpp_namespace', '/demo').value.rstrip('/')
        self.cpp_command = None
        if self.get_parameter('cpp_bridge_enabled').value:
            if cpp_namespace == self.get_namespace().rstrip('/'):
                raise ValueError(
                    'C++ bridge namespace must differ from this Python node namespace'
                )

            self.cpp_command = self.create_publisher(String, cpp_namespace + '/command', 10)
            self.cpp_reply = self.create_publisher(String, 'cpp_reply', 10)
            self.cpp_subscription = self.create_subscription(
                String,
                cpp_namespace + '/echo',
                lambda message: self.cpp_reply.publish(message),
                10,
            )

        self.tick = self.imu_samples = 0
        self.status = self.create_publisher(String, 'status', 10)
        self.telemetry = self.create_publisher(RuntimeStatus, 'runtime_status', 10)
        self.echo = self.create_publisher(String, 'echo', 10)
        self.magnitude = self.create_publisher(
            Float64, 'acceleration_magnitude', qos_profile_sensor_data
        )
        self.command = self.create_subscription(String, 'command', self.on_command, 10)
        self.imu = self.create_subscription(
            Imu, '/phone/imu', self.on_imu, qos_profile_sensor_data
        )
        self.timer = self.create_timer(1.0, self.on_timer)

    def on_command(self, message):
        self.echo.publish(String(data='phone Python processed: ' + message.data))

        if self.cpp_command is not None:
            self.cpp_command.publish(message)

    def on_imu(self, message):
        a = message.linear_acceleration
        self.magnitude.publish(Float64(data=math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z)))
        self.imu_samples += 1

    def on_timer(self):
        self.tick += 1
        self.status.publish(
            String(
                data=(
                    f'{self.get_parameter("message").value} | tick={self.tick} | '
                    f'imu={self.imu_samples}'
                )
            )
        )
        self.telemetry.publish(
            RuntimeStatus(
                message=self.get_parameter('message').value,
                tick=self.tick,
                imu_samples=self.imu_samples,
            )
        )
        fail_after = self.get_parameter('fail_after_ticks').value
        if fail_after > 0 and self.tick >= fail_after:
            raise RuntimeError('Requested Python callback failure')
