#!/usr/bin/env python3
"""Verify the same custom-message numerical pipeline on Linux and Android."""
import argparse
import json
import math
import time
import rclpy
from std_msgs.msg import String
from ws_interfaces.msg import Sample
from rcl_interfaces.srv import GetParameters

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--namespace', default='/android_test')
parser.add_argument('--duration', type=float, default=20)
args = parser.parse_args()
rclpy.init(); node = rclpy.create_node('workspace_parity_probe')
namespace = args.namespace.rstrip('/')
samples, results = {}, {}

def sample(message):
    if math.isfinite(message.value) and abs(message.value - message.sequence * .25) < 1e-9:
        samples[message.sequence] = message.value

def result(message):
    value = json.loads(message.data)
    if isinstance(value['sequence'], int) and math.isfinite(value['squared']):
        results[value['sequence']] = value['squared']

subscriptions = [node.create_subscription(Sample, namespace + '/input', sample, 10),
                 node.create_subscription(String, namespace + '/result', result, 10)]
client = node.create_client(GetParameters, namespace + '/talker/get_parameters')
future = None
checks = {}
deadline = time.monotonic() + args.duration
try:
    while time.monotonic() < deadline:
        rclpy.spin_once(node, timeout_sec=.1)
        if future is None and client.service_is_ready():
            future = client.call_async(GetParameters.Request(names=['period_ms']))
        matches = [seq for seq in samples.keys() & results.keys() if abs(results[seq] - samples[seq] ** 2) < 1e-6]
        names = {ns.rstrip('/') + '/' + name for name, ns in node.get_node_names_and_namespaces()}
        checks = {'custom_input': len(samples) >= 3, 'correct_computation': len(matches) >= 3,
                  'both_nodes': {namespace + '/talker', namespace + '/processor'} <= names,
                  'parameter_service': future is not None and future.done() and future.result() is not None
                        and future.result().values[0].integer_value == 200}
        if all(checks.values()): break
finally:
    print(json.dumps({'verified': checks, 'matching_samples': len(matches),
                      'last_sequence': max(matches) if matches else None}))
    node.destroy_node(); rclpy.shutdown()
raise SystemExit(0 if checks and all(checks.values()) else 1)
