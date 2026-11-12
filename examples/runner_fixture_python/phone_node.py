"""Harmless lifecycle peer with the same wire contract as the C++ fixture."""
import json
import re
from rclpy.node import Node
from rclpy.qos import QoSProfile, DurabilityPolicy
from std_msgs.msg import Bool, String


class PhoneNode(Node):
    def __init__(self, **kwargs):
        super().__init__('runtime_fixture', **kwargs)
        self.trial = self.declare_parameter('trial', 'default').value
        if not isinstance(self.trial, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', self.trial):
            raise ValueError('trial must contain 1..64 letters, numbers, underscores or hyphens')
        self.count = self.tick = 0
        self.last = None
        qos = QoSProfile(depth=10, durability=DurabilityPolicy.VOLATILE)
        self.output = self.create_publisher(String, 'observed', qos)
        self.input = self.create_subscription(Bool, 'request', self.command, qos)
        self.timer = self.create_timer(0.2, self.publish)

    def command(self, value):
        self.count += 1
        self.last = value.data

    def publish(self):
        self.tick += 1
        self.output.publish(String(data=json.dumps(dict(trial=self.trial, tick=self.tick, count=self.count, last=self.last))))
