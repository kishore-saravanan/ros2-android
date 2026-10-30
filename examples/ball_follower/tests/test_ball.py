# SPDX-License-Identifier: Apache-2.0
import io
import unittest
from PIL import Image, ImageDraw
from ball_follower.vision import detect
from ball_follower.control import command


def frame(kind='ball', x=320, radius=60):
    image = Image.new('RGB', (640, 480), (20, 40, 60))
    draw = ImageDraw.Draw(image)
    if kind == 'ball':
        draw.ellipse((x-radius, 240-radius, x+radius, 240+radius), fill=(210, 20, 20))
        draw.line((x-radius+5, 240, x+radius-5, 240), fill='white', width=5)
    elif kind == 'square':
        draw.rectangle((200, 140, 400, 340), fill='red')
    elif kind == 'green':
        draw.ellipse((200, 140, 400, 340), fill='lime')
    output = io.BytesIO()
    image.save(output, format='JPEG')
    return output.getvalue()


class BallTest(unittest.TestCase):
    def test_ball_with_seam_and_position(self):
        for x in (190, 320, 450):
            target, _, masked = detect(frame(x=x))
            self.assertIsNotNone(masked.getbbox())
            self.assertTrue(target['found'])
            self.assertAlmostEqual(target['center_x'], x, delta=7)
            self.assertAlmostEqual(target['radius_px'], 60, delta=7)

    def test_negative_shapes(self):
        for kind in ('empty', 'square', 'green'):
            self.assertFalse(detect(frame(kind))[0]['found'], kind)

    def test_invalid_image(self):
        for data in (b'', b'bad jpeg', b'\xff\xd8' + b'x' * (2*1024*1024)):
            with self.assertRaises((ValueError, OSError)):
                detect(data)

    def test_control_forward_turn_and_stop(self):
        target = dict(found=True, error_x=0., diameter_fraction=.12)
        self.assertGreater(command(target, .01)[0], 0)
        self.assertLess(command(dict(target, error_x=.6), .01)[1], 0)
        self.assertGreater(command(dict(target, error_x=-.6), .01)[1], 0)
        self.assertEqual(command(dict(target, error_x=.6), .01)[0], 0)
        self.assertEqual(command(dict(target, diameter_fraction=.4), .01), (0., 0.))

    def test_calibrated_aim_and_stop_size(self):
        target = dict(found=True, error_x=.2, diameter_fraction=.12)
        forward, turn = command(target, .01, target_center_x=.6)
        self.assertGreater(forward, 0)
        self.assertEqual(turn, 0)
        self.assertEqual(command(target, .01, stop_fraction=.1, target_center_x=.6), (0., 0.))

    def test_stop_on_stale_lost_and_invalid_targets(self):
        target = dict(found=True, error_x=0., diameter_fraction=.12)
        for age in (.31, -1., float('nan')):
            self.assertEqual(command(target, age), (0., 0.))
        for value in ({}, dict(target, found=False), dict(target, error_x=float('nan')),
                      dict(target, diameter_fraction=0), dict(target, error_x=2)):
            self.assertEqual(command(value, .01), (0., 0.))


if __name__ == '__main__':
    unittest.main()
