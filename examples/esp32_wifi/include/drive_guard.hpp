#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace robot {
struct Wheels {
  float left, right;
  Wheels(float l = 0, float r = 0) : left(l), right(r) {}
};
// Open-loop command scaling, not measured vehicle speed or odometry.
class DriveGuard {
 public:
  static constexpr uint32_t deadline_ms = 300;
  bool connected = false;
  bool set_pwm_limit(int32_t value, uint32_t now) {
    const auto current = output(now);
    if (current.left != 0 || current.right != 0 || value < 0 || value > 255) return false;
    pwm_limit_ = value; return true;
  }
  int pwm_limit() const { return pwm_limit_; }
  bool set_angular_limit(float value, uint32_t now) {
    const auto current = output(now);
    if (current.left != 0 || current.right != 0 || !std::isfinite(value) || value < 0 || value > 6) return false;
    angular_limit_ = value; return true;
  }
  float angular_limit() const { return angular_limit_; }
  static int duty(float fraction, int limit) {
    if (!std::isfinite(fraction)) return 0;
    return int(std::round(std::max(-1.f, std::min(1.f, fraction)) * limit));
  }
  void connection(bool up) { connected = up; valid = false; }
  void command(float linear, float angular, uint32_t now, float track_m) {
    if (!std::isfinite(linear) || !std::isfinite(angular) || !std::isfinite(track_m) || track_m <= 0) {
      valid = false; return;
    }
    if (!connected) { valid = false; return; }
    linear = std::max(-.2f, std::min(.2f, linear));
    angular = std::max(-angular_limit_, std::min(angular_limit_, angular));
    desired = {linear - angular * track_m * .5f, linear + angular * track_m * .5f};
    const float scale = std::max(.25f, std::max(std::abs(desired.left), std::abs(desired.right)));
    desired.left /= scale; desired.right /= scale;
    received = now; valid = true;
  }
  Wheels output(uint32_t now) const {
    if (!connected || !valid || uint32_t(now - received) >= deadline_ms) return {};
    return desired;
  }
 private:
  float angular_limit_ = 3.f; // Command ceiling, not measured yaw speed.
  int pwm_limit_ = 255; // Full 8-bit range; command scaling still applies.
  bool valid = false;
  uint32_t received = 0;
  Wheels desired;
};
}
