#!/usr/bin/env python3
"""Verify two phone-hosted ROS nodes, remapped topic flow and scalar parameters."""

import argparse
import json
import time
import rclpy
from rcl_interfaces.srv import GetParameters
from std_msgs.msg import String

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--namespace', default='/robot')
parser.add_argument('--message', default='Computed on Android')
parser.add_argument('--prefix', default='Phone processed: ')
parser.add_argument('--duration', type=float, default=20)
args = parser.parse_args()
rclpy.init()
node = rclpy.create_node('android_workspace_probe')
namespace = args.namespace.rstrip('/')
inputs, outputs = set(), set()


def incoming(message):
    inputs.add(message.data)


def result(message):
    if message.data.startswith(args.prefix):
        outputs.add(message.data[len(args.prefix) :])


subscriptions = [
    node.create_subscription(String, namespace + '/pipeline/input', incoming, 10),
    node.create_subscription(String, namespace + '/pipeline/result', result, 10),
]
clients = [
    node.create_client(GetParameters, namespace + '/' + name + '/get_parameters')
    for name in ('producer', 'processor')
]
futures = [None, None]
expected = [('message', args.message), ('prefix', args.prefix)]
deadline = time.monotonic() + args.duration
checks = {}
try:
    while time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=0.1)

        for index, client in enumerate(clients):
            if futures[index] is None and client.service_is_ready():
                futures[index] = client.call_async(
                    GetParameters.Request(names=[expected[index][0]])
                )

        names = {ns.rstrip('/') + '/' + name for name, ns in node.get_node_names_and_namespaces()}
        checks = {
            'two_phone_nodes': {namespace + '/producer', namespace + '/processor'} <= names,
            'remapped_pipeline': any(
                text.startswith(args.message + ' | tick=') for text in inputs & outputs
            ),
            'per_node_parameters': all(
                future is not None
                and future.done()
                and future.result() is not None
                and future.result().values[0].string_value == expected[index][1]
                for index, future in enumerate(futures)
            ),
        }
        if all(checks.values()):
            break
finally:
    print(
        json.dumps(
            {
                'verified': checks,
                'input_samples': len(inputs),
                'output_samples': len(outputs),
                'last_result': sorted(outputs)[-1] if outputs else None,
            },
            ensure_ascii=False,
        )
    )
    node.destroy_node()
    rclpy.shutdown()

raise SystemExit(0 if checks and all(checks.values()) else 1)
