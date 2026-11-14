# SPDX-License-Identifier: Apache-2.0
import io
import unittest
from PIL import Image, ImageDraw
from ball_follower.vision import detect
from ball_follower.control import command, DetectionGate, TurnPulse


def frame(kind='ball', x=320, radius=60):
    image = Image.new('RGB', (640, 480), (20, 40, 60))
    draw = ImageDraw.Draw(image)

    if kind == 'ball':
        draw.ellipse((x - radius, 240 - radius, x + radius, 240 + radius), fill=(210, 20, 20))
        draw.line((x - radius + 5, 240, x + radius - 5, 240), fill='white', width=5)
    elif kind == 'square':
        draw.rectangle((200, 140, 400, 340), fill='red')
    elif kind == 'wood':
        draw.rectangle((0, 0, 640, 210), fill=(90, 60, 45))

        for x in range(0, 640, 9):
            draw.line((x, 0, x + 15, 210), fill=(110, 72, 50), width=3)
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
        for kind in ('empty', 'square', 'green', 'wood'):
            self.assertFalse(detect(frame(kind))[0]['found'], kind)

    def test_invalid_image(self):
        for data in (b'', b'bad jpeg', b'\xff\xd8' + b'x' * (2 * 1024 * 1024)):
            with self.assertRaises((ValueError, OSError)):
                detect(data)

    def test_control_forward_turn_and_stop(self):
        target = dict(found=True, error_x=0.0, diameter_fraction=0.12)
        self.assertGreater(command(target, 0.01)[0], 0)
        self.assertLess(command(dict(target, error_x=0.6), 0.01)[1], 0)
        self.assertGreater(command(dict(target, error_x=-0.6), 0.01)[1], 0)
        self.assertEqual(command(dict(target, error_x=0.6), 0.01)[0], 0)
        self.assertEqual(command(dict(target, diameter_fraction=0.4), 0.01), (0.0, 0.0))

    def test_full_forward_command_and_confirmation(self):
        target = dict(
            found=True,
            error_x=0.0,
            diameter_fraction=0.05,
            center_x=320.0,
            center_y=240.0,
            width=640,
            height=480,
        )
        self.assertEqual(command(target, 0.01, max_linear=0.2, linear_gain=1.2)[0], 0.2)
        gate = DetectionGate(3)
        self.assertFalse(gate.accept(target))
        self.assertFalse(gate.accept(target))
        self.assertTrue(gate.accept(target))
        self.assertFalse(gate.accept(dict(target, found=False)))
        self.assertFalse(gate.accept(target))
        self.assertFalse(gate.accept(dict(target, center_x=600.0)))

    def test_full_pwm_and_stop_conditions(self):
        target = dict(found=True, error_x=0.0, diameter_fraction=0.29)
        self.assertEqual(command(target, 0.01, full_pwm=True), (1.0, 0.0))
        self.assertEqual(command(dict(target, error_x=0.6), 0.01, full_pwm=True), (0.0, -0.8))
        self.assertEqual(command(dict(target, error_x=-0.6), 0.01, full_pwm=True), (0.0, 0.8))

        for t, age in (
            (dict(target, diameter_fraction=0.31), 0.01),
            (dict(target, found=False), 0.01),
            (target, 0.31),
        ):
            self.assertEqual(command(t, age, full_pwm=True), (0.0, 0.0))

    def test_turn_pulse_brakes_and_waits_for_post_settle_frame(self):
        pulse = TurnPulse(0.04, 0.15)
        self.assertEqual(pulse.step(1.0, 0.8, 1.0, 1_000_000_000, 1_000_000_000), (0.0, 0.8))
        self.assertEqual(pulse.step(0.0, 0.8, 1.05, 1_040_000_000, 1_050_000_000), (0.0, 0.0))
        self.assertEqual(pulse.step(0.0, 0.8, 1.3, 1_100_000_000, 1_300_000_000), (0.0, 0.0))
        self.assertEqual(pulse.step(0.0, 0.8, 1.31, 1_250_000_000, 1_310_000_000), (0.0, 0.8))
        # Losing the ball or reaching the aim point stops a turn immediately.
        self.assertEqual(pulse.step(0.0, 0.0, 1.32, 1_300_000_000, 1_320_000_000), (0.0, 0.0))
        self.assertEqual(pulse.step(1.0, 0.0, 1.6, 1_500_000_000, 1_600_000_000), (1.0, 0.0))
        pulse.reset()
        self.assertEqual(pulse.phase, 'idle')

    def test_full_duty_never_mixes_forward_and_turn(self):
        for error in (-0.9, -0.2, -0.081, 0.0, 0.081, 0.2, 0.9):
            linear, angular = command(
                dict(found=True, error_x=error, diameter_fraction=0.12), 0.01, full_pwm=True
            )
            self.assertFalse(linear and angular)
            self.assertEqual(linear, 1.0 if abs(error) <= 0.08 else 0.0)

    def test_adaptive_turn_duration_is_bounded_and_symmetric(self):
        pulse = TurnPulse(0.04, 0.15, 0.09)
        self.assertEqual(pulse.duration_for_error(0.2), 0.04)
        self.assertAlmostEqual(pulse.duration_for_error(0.55), 0.065)

        for error in (0.8, 1.4, -0.8, -1.4):
            self.assertEqual(pulse.duration_for_error(error), 0.09)

        self.assertEqual(pulse.duration_for_error(float('nan')), 0.04)

        with self.assertRaises(ValueError):
            TurnPulse(0.04, 0.15, 0.03)

    def test_adaptive_turn_does_not_extend_or_ignore_stop(self):
        pulse = TurnPulse(0.04, 0.15, 0.09)
        self.assertEqual(pulse.step(0.0, 0.8, 1.0, 1_000_000_000, 1_000_000_000, 0.9), (0.0, 0.8))
        self.assertEqual(pulse.step(0.0, 0.8, 1.06, 1_050_000_000, 1_060_000_000, 0.9), (0.0, 0.8))
        self.assertEqual(pulse.step(0.0, 0.8, 1.10, 1_090_000_000, 1_100_000_000, 0.9), (0.0, 0.0))
        pulse.reset()
        pulse.step(0.0, 0.8, 2.0, 2_000_000_000, 2_000_000_000, 0.9)
        self.assertEqual(pulse.step(0.0, 0.0, 2.02, 2_010_000_000, 2_020_000_000, 0.1), (0.0, 0.0))

    def test_calibrated_aim_and_stop_size(self):
        target = dict(found=True, error_x=0.2, diameter_fraction=0.12)
        forward, turn = command(target, 0.01, target_center_x=0.6)
        self.assertGreater(forward, 0)
        self.assertEqual(turn, 0)
        self.assertEqual(command(target, 0.01, stop_fraction=0.1, target_center_x=0.6), (0.0, 0.0))

    def test_stop_on_stale_lost_and_invalid_targets(self):
        target = dict(found=True, error_x=0.0, diameter_fraction=0.12)

        for age in (0.31, -1.0, float('nan')):
            self.assertEqual(command(target, age), (0.0, 0.0))

        for value in (
            {},
            dict(target, found=False),
            dict(target, error_x=float('nan')),
            dict(target, diameter_fraction=0),
            dict(target, error_x=2),
        ):
            self.assertEqual(command(value, 0.01), (0.0, 0.0))


if __name__ == '__main__':
    unittest.main()
