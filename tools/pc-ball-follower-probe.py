#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Send generated JPEGs; verify phone-computed targets and TEST-topic velocity only."""
import argparse
import io
import json
import time
from PIL import Image, ImageDraw
import rclpy
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage, Image as RosImage
from std_msgs.msg import String, Bool
from geometry_msgs.msg import Twist


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--namespace', default='/ball')
    parser.add_argument('--command-topic', default='/ball_demo/cmd_vel')
    parser.add_argument('--compressed-only', action='store_true', help='Verify compressed previews without requesting raw image streams')
    parser.add_argument('--annotated-only', action='store_true', help='Verify the default annotated preview only, without masks or raw images')
    parser.add_argument('--turn-pulses', action='store_true', help='Expect turn/brake cycles in full-duty mode, rather than continuous turns')
    args = parser.parse_args()
    ns = args.namespace.rstrip('/')
    rclpy.init()
    node = rclpy.create_node('ball_follower_test_observer')
    publisher = node.create_publisher(CompressedImage, '/ball_test/image/compressed', qos_profile_sensor_data)
    toggle = node.create_publisher(Bool, ns+'/track_ball', 1)
    targets, velocities, timings, images, masks = [], [], [], [], []
    node.create_subscription(String, ns+'/target', lambda m: targets.append((time.monotonic(), json.loads(m.data))), qos_profile_sensor_data)
    node.create_subscription(Twist, args.command_topic, lambda m: velocities.append((time.monotonic(), m.linear.x, m.angular.z)), qos_profile_sensor_data)
    node.create_subscription(String, ns+'/metrics', lambda m: timings.append(json.loads(m.data)), qos_profile_sensor_data)
    node.create_subscription(CompressedImage, ns+'/image/compressed', lambda m: images.append(len(m.data)), qos_profile_sensor_data)
    if not args.annotated_only:
        node.create_subscription(CompressedImage, ns+'/mask/compressed', lambda m: masks.append(len(m.data)), qos_profile_sensor_data)
    raw_images = []
    def raw_frame(message):
        assert message.encoding == 'rgb8' and message.step == message.width*3
        assert len(message.data) == message.step*message.height
        raw_images.append((message.width, message.height))
    if not args.compressed_only and not args.annotated_only:
        node.create_subscription(RosImage, ns+'/image', raw_frame, qos_profile_sensor_data)
        node.create_subscription(RosImage, ns+'/mask', raw_frame, qos_profile_sensor_data)
    results = {}
    try:
        deadline = time.monotonic()+30
        while (publisher.get_subscription_count() == 0 or toggle.get_subscription_count() == 0) and time.monotonic()<deadline:
            rclpy.spin_once(node, timeout_sec=.1)
        assert publisher.get_subscription_count(), 'No phone vision subscription'
        for phase, x, radius in [('disabled', 320, 40), ('forward', 320, 40), ('right', 450, 40),
                                  ('left', 190, 40), ('near', 320, 110),
                                  ('wood', None, 0), ('lost', None, 0), ('malformed', None, 0), ('stale', None, 0), ('off', 320, 40)]:
            toggle.publish(Bool(data=phase not in ('disabled', 'off')))
            image = Image.new('RGB', (640, 480), (20, 40, 60))
            if x is not None:
                draw = ImageDraw.Draw(image)
                draw.ellipse((x-radius, 240-radius, x+radius, 240+radius), fill=(210, 20, 20))
                draw.line((x-radius+4, 240, x+radius-4, 240), fill='white', width=4)
            if phase == 'wood':
                draw = ImageDraw.Draw(image)
                draw.rectangle((0, 0, 640, 220), fill=(90, 60, 45))
                for grain in range(0, 640, 9):
                    draw.line((grain, 0, grain+15, 220), fill=(110, 72, 50), width=3)
            output = io.BytesIO();image.save(output, format='JPEG')
            data = output.getvalue() if phase != 'malformed' else b'not a jpeg'
            begin = time.monotonic();next_send=0
            while time.monotonic()-begin<4:
                now=time.monotonic()
                if phase != 'stale' and now>=next_send:
                    msg=CompressedImage();msg.header.stamp=node.get_clock().now().to_msg()
                    msg.header.frame_id='generated_ball_test';msg.format='jpeg';msg.data=data
                    publisher.publish(msg);next_send=now+1/15
                rclpy.spin_once(node, timeout_sec=.01)
            samples=[v for v in velocities if v[0]>begin+1.5]
            assert len(samples)>=5, (phase, 'Missing velocity samples')
            if phase=='forward': good=lambda v:v[1]>0 and abs(v[2])<.01
            elif phase in ('right', 'left') and args.turn_pulses:
                sign = -1 if phase=='right' else 1
                good=lambda v:v[1]==0 and (abs(v[2])<.0001 or .1 < sign*v[2] <= .800001)
                moving = sum(sign*v[2]>.1 for v in samples)
                braking = sum(abs(v[2])<.0001 for v in samples)
                assert moving>=2 and braking>=2, (phase, 'Missing turn/brake cycles', moving, braking)
            elif phase=='right': good=lambda v:v[1]==0 and v[2]<-.1
            elif phase=='left': good=lambda v:v[1]==0 and v[2]>.1
            else: good=lambda v:abs(v[1])+abs(v[2])<.0001
            ratio=sum(good(v) for v in samples)/len(samples)
            results[phase]={'samples':len(samples),'correct_fraction':ratio}
            assert ratio>=.9, (phase,results[phase], timings[-1] if timings else {}, targets[-1][1] if targets else {})
        names={name for name,ns in node.get_node_names_and_namespaces() if ns==args.namespace.rstrip('/')}
        assert {'ball_vision','ball_follower'}<=names,names
        assert targets and timings and images, 'Missing target, metrics or annotated image output'
        assert args.annotated_only or masks, 'Missing mask output'
        assert args.annotated_only or args.compressed_only or raw_images, 'Missing raw image output'
        print(json.dumps(dict(passed=True, phases=results, nodes=sorted(names),
                              compressed_only=args.compressed_only,
                              annotated_only=args.annotated_only,
                              annotated_frames=len(images), masked_frames=len(masks), raw_frames=len(raw_images), metrics=timings[-1]),indent=2))
    finally:
        toggle.publish(Bool(data=False))
        rclpy.spin_once(node, timeout_sec=.1)
        node.destroy_node();rclpy.shutdown()


if __name__=='__main__':
    main()
