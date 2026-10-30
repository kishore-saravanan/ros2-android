# SPDX-License-Identifier: Apache-2.0
"""Two independent rclpy nodes; the Android runner hosts both in one process."""
from collections import deque
import json
import math
import threading
import time
import numpy as np
import rclpy
from rclpy.node import Node
from rclpy.callback_groups import MutuallyExclusiveCallbackGroup
from rclpy.qos import QoSProfile, ReliabilityPolicy, DurabilityPolicy
from sensor_msgs.msg import CompressedImage, Image
from std_msgs.msg import String, Bool
from geometry_msgs.msg import Twist
from rcl_interfaces.msg import SetParametersResult
from .vision import detect, jpeg
from .control import command

QOS = QoSProfile(depth=1, reliability=ReliabilityPolicy.BEST_EFFORT,
                 durability=DurabilityPolicy.VOLATILE)


class VisionNode(Node):
    def __init__(self, **kwargs):
        super().__init__('ball_vision', enable_rosout=False, **kwargs)
        self.width = self.declare_parameter('processing_width', 320).value
        hz = self.declare_parameter('processing_hz', 15.).value
        self.annotated_hz = self.declare_parameter('annotated_hz', 5.).value
        self.raw_hz = self.declare_parameter('raw_image_hz', 2.).value
        self.raw_last = 0.
        self.fov = self.declare_parameter('horizontal_fov_degrees', 0.).value
        self.diameter = self.declare_parameter('ball_diameter_m', .066).value
        topic = self.declare_parameter('image_topic', '/phone/camera_back_0/image/compressed').value
        if not 64 <= self.width <= 1280 or not math.isfinite(hz) or hz <= 0 or not math.isfinite(self.annotated_hz) or self.annotated_hz < 0 or not math.isfinite(self.raw_hz) or self.raw_hz < 0:
            raise ValueError('Invalid processing width/rates')
        if not math.isfinite(self.fov) or not 0 <= self.fov < 179 or not math.isfinite(self.diameter) or self.diameter <= 0:
            raise ValueError('Invalid camera FOV or physical ball diameter')
        self.pending = None
        self.output_slot = None
        self.output_group = MutuallyExclusiveCallbackGroup()
        self.received = self.processed = self.dropped = self.errors = 0
        self.started = time.monotonic()
        self.timings = deque(maxlen=120)
        self.latest = {}
        self.targets = self.create_publisher(String, 'target', QOS)
        self.found = self.create_publisher(Bool, 'detected', QOS)
        self.images = self.create_publisher(CompressedImage, 'image/compressed', QOS)
        self.masks = self.create_publisher(CompressedImage, 'mask/compressed', QOS)
        self.raw_images = self.create_publisher(Image, 'image', QOS)
        self.raw_masks = self.create_publisher(Image, 'mask', QOS)
        self.metrics = self.create_publisher(String, 'metrics', QOS)
        self.create_subscription(CompressedImage, topic, self.receive, QOS)
        self.create_timer(1 / hz, self.process)
        self.create_timer(1., self.report)
        if self.annotated_hz:
            self.create_timer(1/self.annotated_hz, self.publish_images, callback_group=self.output_group)

    def receive(self, message):
        self.received += 1
        if self.pending is not None:
            self.dropped += 1
        self.pending = (message, time.monotonic())

    def process(self):
        if self.pending is None:
            return
        message, arrived = self.pending
        self.pending = None
        queue_ms = (time.monotonic()-arrived)*1000
        begin = time.perf_counter()
        stamp_ns = message.header.stamp.sec * 1_000_000_000 + message.header.stamp.nanosec
        age = (self.get_clock().now().nanoseconds - stamp_ns) / 1e9
        if not math.isfinite(age) or age < -.05 or age > .30:
            self.dropped += 1
            return
        try:
            target, image, masked = detect(bytes(message.data), self.width)
        except (ValueError, OSError, SyntaxError) as error:
            self.errors += 1
            target = dict(found=False, reason=str(error))
            image = None
        compute_ms = (time.perf_counter() - begin) * 1000
        self.timings.append(compute_ms)
        self.processed += 1
        target.update(stamp_ns=stamp_ns, processing_ms=compute_ms,
                      frame_age_ms=(self.get_clock().now().nanoseconds-stamp_ns)/1e6,
                      queue_ms=queue_ms)
        if target.get('found') and self.fov:
            focal = target['width'] / (2 * math.tan(math.radians(self.fov) / 2))
            target['estimated_range_m'] = self.diameter * focal / (2 * target['radius_px'])
        self.latest = target
        self.targets.publish(String(data=json.dumps(target, allow_nan=False)))
        self.found.publish(Bool(data=bool(target['found'])))
        if image is not None and self.annotated_hz:
            self.output_slot = (message.header, image, masked)

    def publish_images(self):
        pending = self.output_slot
        if pending is None:
            return
        self.output_slot = None
        header, image, masked = pending
        now = time.monotonic()
        publish_raw = bool(self.raw_hz and now-self.raw_last >= 1/self.raw_hz)
        if publish_raw:
            self.raw_last = now
        for publisher, raw, frame in ((self.images, self.raw_images, image),
                                      (self.masks, self.raw_masks, masked)):
            if publish_raw and raw.get_subscription_count():
                output_raw = Image()
                output_raw.header = header
                output_raw.height, output_raw.width = frame.height, frame.width
                output_raw.encoding = 'rgb8'
                output_raw.is_bigendian = 0
                output_raw.step = frame.width * 3
                output_raw.data = frame.tobytes()
                raw.publish(output_raw)
            if publisher.get_subscription_count():
                output = CompressedImage()
                output.header = header
                output.format = 'jpeg'
                output.data = jpeg(frame)
                publisher.publish(output)

    def report(self):
        elapsed = max(.001, time.monotonic()-self.started)
        stamp = self.latest.get('stamp_ns', 0)
        age = (self.get_clock().now().nanoseconds-stamp)/1e6 if stamp else None
        stale = age is None or age > 300 or age < -50
        self.found.publish(Bool(data=bool(self.latest.get('found', False) and not stale)))
        stats = dict(received=self.received, processed=self.processed, dropped=self.dropped,
                     errors=self.errors, received_hz=self.received/elapsed,
                     processed_hz=self.processed/elapsed, stale=stale, frame_age_ms=age,
                     processing_mean_ms=float(np.mean(self.timings)) if self.timings else None,
                     processing_p95_ms=float(np.percentile(self.timings, 95)) if self.timings else None)
        self.metrics.publish(String(data=json.dumps(stats, allow_nan=False)))


class FollowerNode(Node):
    def __init__(self, **kwargs):
        super().__init__('ball_follower', enable_rosout=False, **kwargs)
        self.target_center = self.declare_parameter('target_center_x', .5).value
        self.stop_fraction = self.declare_parameter('stop_diameter_fraction', .30).value
        self.max_linear = self.declare_parameter('max_linear', .12).value
        self.max_angular = self.declare_parameter('max_angular', 2.5).value
        topic = self.declare_parameter('command_topic', '/ball_demo/cmd_vel').value
        if not all(math.isfinite(v) for v in (self.stop_fraction, self.max_linear, self.max_angular, self.target_center)) or not (
                0 <= self.target_center <= 1 and 0 < self.stop_fraction <= 1 and 0 <= self.max_linear <= .2 and 0 <= self.max_angular <= 3):
            raise ValueError('Invalid follower limits')
        self.add_on_set_parameters_callback(self.validate_parameters)
        self.target = {}
        self.latest_stamp = 0
        self.tracking = False
        self.commands = self.create_publisher(Twist, topic, QOS)
        self.create_subscription(String, 'target', self.receive, QOS)
        self.create_subscription(Bool, 'track_ball', self.toggle, 1)
        self.tracking_state = self.create_publisher(Bool, 'tracking', 1)
        self.create_timer(1., lambda: self.tracking_state.publish(Bool(data=self.tracking)))
        self.create_timer(.05, self.tick)

    def validate_parameters(self, parameters):
        ranges = {'target_center_x': (0., 1.), 'stop_diameter_fraction': (.01, 1.),
                  'max_linear': (0., .2), 'max_angular': (0., 3.)}
        for parameter in parameters:
            if parameter.name == 'command_topic':
                return SetParametersResult(successful=False, reason='Restart to change command_topic')
            if parameter.name in ranges:
                low, high = ranges[parameter.name]
                if not isinstance(parameter.value, (float, int)) or not math.isfinite(parameter.value) or not low <= parameter.value <= high:
                    return SetParametersResult(successful=False, reason=f'{parameter.name} must be finite in {low}..{high}')
        return SetParametersResult(successful=True)

    def receive(self, message):
        try:
            target = json.loads(message.data)
            if not isinstance(target, dict) or not isinstance(target.get('stamp_ns'), int):
                raise ValueError('Invalid target record')
            if target['stamp_ns'] <= self.latest_stamp:
                self.target = {}  # Replays/out-of-order frames must not continue a movement.
                return
            self.latest_stamp = target['stamp_ns']
            self.target = target
        except (ValueError, TypeError):
            self.target = {}

    def toggle(self, message):
        self.tracking = bool(message.data)
        self.tracking_state.publish(Bool(data=self.tracking))
        if not self.tracking:
            self.commands.publish(Twist())

    def tick(self):
        age = (self.get_clock().now().nanoseconds-self.target.get('stamp_ns', 0))/1e9
        linear, angular = command(self.target, age,
                                  stop_fraction=self.get_parameter('stop_diameter_fraction').value,
                                  max_linear=self.get_parameter('max_linear').value,
                                  max_angular=self.get_parameter('max_angular').value,
                                  target_center_x=self.get_parameter('target_center_x').value)
        output = Twist()
        output.linear.x, output.angular.z = (linear, angular) if self.tracking else (0., 0.)
        self.commands.publish(output)

    def destroy_node(self):
        self.commands.publish(Twist())
        return super().destroy_node()


def _main(cls, args=None):
    rclpy.init(args=args)
    node = cls()
    try:
        rclpy.spin(node)
    finally:
        node.destroy_node()
        rclpy.shutdown()


def vision_main(args=None):
    _main(VisionNode, args)


def follower_main(args=None):
    _main(FollowerNode, args)
