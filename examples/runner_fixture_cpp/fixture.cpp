#include <rclcpp/rclcpp.hpp>
#include <std_msgs/msg/bool.hpp>
#include <std_msgs/msg/string.hpp>
#include <chrono>
#include <string>

// Harmless lifecycle peer: no hardware, files, camera or movement topics.
class Fixture : public rclcpp::Node {
 public:
  explicit Fixture(const rclcpp::NodeOptions & options) : Node("runtime_fixture", options) {
    output_ = create_publisher<std_msgs::msg::String>("observed", rclcpp::QoS(10).durability_volatile());
    input_ = create_subscription<std_msgs::msg::Bool>("request", rclcpp::QoS(10).durability_volatile(),
      [this](std_msgs::msg::Bool::ConstSharedPtr value) { ++count_; last_ = value->data ? "true" : "false"; });
    timer_ = create_wall_timer(std::chrono::milliseconds(200), [this] {
      std_msgs::msg::String value;
      value.data = "{\"tick\":" + std::to_string(++tick_) + ",\"count\":" +
        std::to_string(count_) + ",\"last\":" + last_ + "}";
      output_->publish(value);
    });
  }
 private:
  uint64_t count_ = 0, tick_ = 0;
  std::string last_ = "null";
  rclcpp::Publisher<std_msgs::msg::String>::SharedPtr output_;
  rclcpp::Subscription<std_msgs::msg::Bool>::SharedPtr input_;
  rclcpp::TimerBase::SharedPtr timer_;
};

extern "C" rclcpp::Node * ros2_android_create_node(const rclcpp::NodeOptions * options) {
  return new Fixture(*options);
}
