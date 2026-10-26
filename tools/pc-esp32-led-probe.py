#!/usr/bin/env python3
"""Observe Dashboard LED Bool command and MCU on/off telemetry; publishes nothing."""
import json
import time
import rclpy
from rclpy.node import Node
from std_msgs.msg import Bool

rclpy.init()
node = Node('esp32_led_observer')
command = []
on = []
off = []

def state(message):
    now = time.monotonic()
    if command and message.data:
        on.append(now)
    elif on and not message.data:
        off.append(now)

subscriptions = [
    node.create_subscription(Bool, '/esp32/led', lambda m: command.append(time.monotonic()) if m.data else None, 10),
    node.create_subscription(Bool, '/esp32/led_state', state, 10),
]
try:
    deadline = time.monotonic() + 100
    while time.monotonic() < deadline and not off:
        rclpy.spin_once(node, timeout_sec=.1)
    if not command or not on or not off:
        raise RuntimeError('Missing phone command or MCU on/off telemetry')
    duration = off[0] - on[0]
    print(json.dumps({'passed': 2.5 <= duration <= 20.0, 'mcu_on_off_interval_s': round(duration, 3), 'command_seen': True, 'mode': 'held-until-off'}), flush=True)
    if not 2.5 <= duration <= 20.0:
        raise RuntimeError('Unexpected MCU on/off interval')
finally:
    node.destroy_node(); rclpy.shutdown()
