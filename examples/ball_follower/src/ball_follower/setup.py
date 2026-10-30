from glob import glob
from setuptools import setup

setup(name='ball_follower', version='0.1.0', packages=['ball_follower'],
      data_files=[('share/ament_index/resource_index/packages', ['resource/ball_follower']),
                  ('share/ball_follower', ['package.xml']),
                  ('share/ball_follower/launch', glob('launch/*.launch.py'))],
      install_requires=['setuptools', 'numpy', 'Pillow'], zip_safe=True,
      maintainer='ROS2 Android contributors', maintainer_email='maintainers@example.invalid',
      description='Python red-ball detection and visual-servoing example.', license='Apache-2.0',
      entry_points={'console_scripts': ['vision = ball_follower.nodes:vision_main',
                                      'follower = ball_follower.nodes:follower_main']})
