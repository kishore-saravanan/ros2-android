#include <rclcpp/rclcpp.hpp>
#include <std_msgs/msg/bool.hpp>
#include <std_msgs/msg/string.hpp>
#include <chrono>
#include <ctime>
#include <string>
#include <stdexcept>

// Same clock as Android SystemClock.elapsedRealtime(), so peers on one phone can
// attribute a telemetry gap to publishing or to delivery.
static int64_t boot_milliseconds() {
  timespec now{};
  clock_gettime(CLOCK_BOOTTIME, &now);
  return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

// Harmless lifecycle peer: no hardware, files, camera or movement topics.
class Fixture : public rclcpp::Node {
 public:
  explicit Fixture(const rclcpp::NodeOptions & options) : Node("runtime_fixture", options) {
    trial_ = declare_parameter<std::string>("trial", "default");
    if (trial_.empty() || trial_.size() > 64 ||
        trial_.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") != std::string::npos)
      throw std::invalid_argument("trial must contain 1..64 letters, numbers, underscores or hyphens");
    output_ = create_publisher<std_msgs::msg::String>("observed", rclcpp::QoS(10).durability_volatile());
    input_ = create_subscription<std_msgs::msg::Bool>("request", rclcpp::QoS(10).durability_volatile(),
      [this](std_msgs::msg::Bool::ConstSharedPtr value) { ++count_; last_ = value->data ? "true" : "false"; });
    timer_ = create_wall_timer(std::chrono::milliseconds(200), [this] {
      std_msgs::msg::String value;
      value.data = "{\"trial\":\"" + trial_ + "\",\"tick\":" + std::to_string(++tick_) + ",\"count\":" +
        std::to_string(count_) + ",\"last\":" + last_ + ",\"stamp_ms\":" + std::to_string(boot_milliseconds()) + "}";
      output_->publish(value);
    });
  }
 private:
  uint64_t count_ = 0, tick_ = 0;
  std::string last_ = "null", trial_;
  rclcpp::Publisher<std_msgs::msg::String>::SharedPtr output_;
  rclcpp::Subscription<std_msgs::msg::Bool>::SharedPtr input_;
  rclcpp::TimerBase::SharedPtr timer_;
};

extern "C" rclcpp::Node * ros2_android_create_node(const rclcpp::NodeOptions * options) {
  return new Fixture(*options);
}
