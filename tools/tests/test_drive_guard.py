"""Host checks for the MCU command guard; no GPIO or physical timing claim."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('g++'), 'Requires a C++ compiler')
class DriveGuardTest(unittest.TestCase):
    def test_deadline_disconnect_invalid_input_and_wheel_mixing(self):
        source = r"""
#include "drive_guard.hpp"
#include <cassert>
#include <limits>
int main() {
  robot::DriveGuard g;
  auto stopped = [](robot::Wheels w) { return w.left == 0 && w.right == 0; };
  assert(g.pwm_limit() == 255);
  assert(g.angular_limit() == 3);
  robot::DriveGuard full(true);
  full.connection(true);
  for (float v : {.01f, .2f, .8f, 1.f, 3.f}) {
    const int duty = int(std::round(std::min(v, 1.f) * 255));
    full.command(v, 0, 100, .16f);
    assert(robot::DriveGuard::duty(full.output(100).left, 255) == duty);
    assert(robot::DriveGuard::duty(full.output(100).right, 255) == duty);
    full.command(0, v, 100, .16f);
    assert(robot::DriveGuard::duty(full.output(100).left, 255) == -duty);
    assert(robot::DriveGuard::duty(full.output(100).right, 255) == duty);
  }
  assert(stopped(full.output(400)));
  full.command(0, 0, 500, .16f); assert(stopped(full.output(500)));
  full.command(std::numeric_limits<float>::infinity(), 0, 600, .16f);
  assert(stopped(full.output(600)));
  full.command(.1f, 0, 700, .16f); full.connection(false);
  assert(stopped(full.output(700)));
  assert(!g.set_angular_limit(-1, 0) && !g.set_angular_limit(7, 0));
  assert(!g.set_angular_limit(std::numeric_limits<float>::quiet_NaN(), 0));
  assert(!g.set_angular_limit(std::numeric_limits<float>::infinity(), 0));
  assert(g.set_angular_limit(3, 0));
  assert(!g.set_pwm_limit(-1, 0) && !g.set_pwm_limit(256, 0));
  assert(g.set_pwm_limit(120, 0));
  assert(robot::DriveGuard::duty(.5f, 120) == 60);
  assert(robot::DriveGuard::duty(-.5f, 120) == -60);
  assert(robot::DriveGuard::duty(9, 255) == 255);
  assert(robot::DriveGuard::duty(0, 255) == 0);
  assert(g.set_pwm_limit(0, 0) && robot::DriveGuard::duty(1, g.pwm_limit()) == 0);
  assert(g.set_pwm_limit(255, 0));
  g.command(.1f, 0, 10, .16f); assert(stopped(g.output(10)));
  g.connection(true); assert(stopped(g.output(10))); // Reconnect cannot replay.
  g.command(.1f, 0, 100, .16f);
  assert(g.output(399).left > 0 && g.output(399).right > 0);
  assert(!g.set_pwm_limit(100, 399) && g.pwm_limit() == 255);
  assert(stopped(g.output(400))); // Exact expiry boundary.
  assert(g.set_pwm_limit(100, 400)); // Expired command permits configuration.
  assert(g.set_angular_limit(1, 400));
  g.command(0, 2.5f, 450, .16f);
  assert(robot::DriveGuard::duty(g.output(450).right, 255) == 82); // Old ceiling.
  assert(!g.set_angular_limit(3, 450)); // No jump during live movement.
  assert(g.set_angular_limit(3, 750));
  g.command(0, 2.5f, 800, .16f);
  auto turn = g.output(800);
  assert(robot::DriveGuard::duty(turn.left, 255) == -204);
  assert(robot::DriveGuard::duty(turn.right, 255) == 204);
  assert(stopped(g.output(1100))); // Higher turn retains expiry.
  assert(g.set_angular_limit(0, 1100));
  g.command(0, 3, 1200, .16f); assert(stopped(g.output(1200)));
  assert(g.set_angular_limit(3, 1200));
  g.command(0, 1, 500, .16f); assert(g.output(500).left < 0 && g.output(500).right > 0);
  g.command(999, 999, 600, .16f);
  auto w = g.output(600); assert(std::abs(w.left) <= 1 && std::abs(w.right) <= 1);
  g.command(0, 0, 601, .16f); assert(stopped(g.output(601)));
  assert(g.set_pwm_limit(255, 601)); // Explicit zero permits configuration.
  g.command(.1f, 0, UINT32_MAX - 100, .16f);
  assert(g.output(50).left > 0); assert(stopped(g.output(199))); // millis rollover.
  g.command(std::numeric_limits<float>::quiet_NaN(), 0, 700, .16f);
  assert(stopped(g.output(700)));
  g.command(.1f, 0, 800, .16f); g.connection(false);
  assert(stopped(g.output(801)));
  g.connection(true); assert(stopped(g.output(802)));
  g.command(.1f, 0, 900, -1); assert(stopped(g.output(900)));

}
"""

        with tempfile.TemporaryDirectory() as tmp:
            cpp = Path(tmp) / 'guard.cpp'
            binary = Path(tmp) / 'guard'
            cpp.write_text(source)
            subprocess.run(
                [
                    'g++',
                    '-std=c++11',
                    '-Wall',
                    '-Wextra',
                    '-Werror',
                    '-I',
                    str(ROOT / 'examples/esp32_wifi/include'),
                    str(cpp),
                    '-o',
                    str(binary),
                ],
                check=True,
            )
            subprocess.run([str(binary)], check=True)
