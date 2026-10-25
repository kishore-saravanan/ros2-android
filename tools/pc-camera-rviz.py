#!/usr/bin/env python3
"""Open RViz for a phone JPEG stream; closing RViz stops its private decoder."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--topic', default='/phone/camera_back_0/image/compressed',
                        help='Full CompressedImage topic (default: back camera)')
    parser.add_argument('--domain', type=int, default=None, help='Match phone domain (default: ROS_DOMAIN_ID or 0)')
    args = parser.parse_args()
    if not re.fullmatch(r'/[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*', args.topic):
        parser.error('--topic must be an absolute ROS topic without a trailing slash')
    if args.domain is not None and not 0 <= args.domain <= 232:
        parser.error('--domain must be between 0 and 232')
    env = os.environ.copy()
    if args.domain is not None:
        env['ROS_DOMAIN_ID'] = str(args.domain)
    children = []
    # Each invocation gets a private raw topic, avoiding other RViz/decoder sessions.
    raw = f'/ros2_android_viewer_{os.getpid()}/image'
    config = {
        'Panels': [{'Class': 'rviz_common/Displays', 'Name': 'Displays'}],
        'Visualization Manager': {
            'Class': '', 'Enabled': True, 'Name': 'root',
            'Global Options': {'Fixed Frame': 'camera_link', 'Frame Rate': 30},
            'Displays': [{'Class': 'rviz_default_plugins/Image', 'Name': 'Phone camera',
                          'Enabled': True, 'Value': True,
                          'Topic': {'Value': raw, 'Reliability Policy': 'Best Effort',
                                    'Durability Policy': 'Volatile', 'History Policy': 'Keep Last', 'Depth': 1}}]},
        'Window Geometry': {'Width': 1100, 'Height': 800},
    }
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        with tempfile.TemporaryDirectory(prefix='ros2-android-camera-') as folder:
            path = Path(folder) / 'camera.rviz'
            path.write_text(json.dumps(config))  # JSON is valid YAML, read by RViz.
            decoder = subprocess.Popen([
                'ros2', 'run', 'image_transport', 'republish', '--ros-args',
                '-r', f'__node:=ros2_android_decoder_{os.getpid()}',
                '-p', 'in_transport:=compressed', '-p', 'out_transport:=raw',
                '-r', f'in/compressed:={args.topic}', '-r', f'out:={raw}',
                '-p', f'qos_overrides.{args.topic}.subscription.reliability:=best_effort',
            ], env=env, start_new_session=True)
            children.append(decoder)
            viewer = subprocess.Popen(['rviz2', '-d', str(path)], env=env, start_new_session=True)
            children.append(viewer)
            print(f'Viewing {args.topic} on domain {env.get("ROS_DOMAIN_ID", "0")}.\n'
                  'Publish that camera in the phone app. Close RViz or press Ctrl+C to stop.', flush=True)
            while viewer.poll() is None:
                if decoder.poll() is not None:
                    raise RuntimeError('Camera decoder exited; check the ROS/image_transport error above.')
                time.sleep(0.2)
            return viewer.returncode
    except KeyboardInterrupt:
        return 0
    except (OSError, RuntimeError) as error:
        print(f'Camera viewer: {error}', flush=True)
        return 1
    finally:
        for child in reversed(children):
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
        for child in children:
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()


if __name__ == '__main__':
    raise SystemExit(main())
