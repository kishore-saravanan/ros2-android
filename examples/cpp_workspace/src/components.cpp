#include "ros2_android_project.hpp"
#include <std_msgs/msg/string.hpp>
#include <chrono>
#include <stdexcept>
#include <string>

class Producer : public rclcpp::Node
{
public:
  explicit Producer(const rclcpp::NodeOptions & options) : Node("producer", options)
  {
    declare_parameter("message", "Computed on Android");
    const auto period = declare_parameter("period_ms", int64_t{200});
    if (period < 20 || period > 60000) {
      throw std::runtime_error("period_ms must be 20–60000");
    }

    declare_parameter("fail_after_ticks", int64_t{0});
    publisher_ = create_publisher<std_msgs::msg::String>("output", 10);
    timer_ = create_wall_timer(std::chrono::milliseconds(period), [this] {
      std_msgs::msg::String message;
      message.data = get_parameter("message").as_string() + " | tick=" + std::to_string(++tick_);
      publisher_->publish(message);
      const auto fail_after = get_parameter("fail_after_ticks").as_int();
      if (fail_after > 0 && tick_ >= static_cast<uint64_t>(fail_after)) {
        throw std::runtime_error("Requested workspace callback failure");
      }
    });
  }

private:
  uint64_t tick_ = 0;
  rclcpp::Publisher<std_msgs::msg::String>::SharedPtr publisher_;
  rclcpp::TimerBase::SharedPtr timer_;
};

class Processor : public rclcpp::Node
{
public:
  explicit Processor(const rclcpp::NodeOptions & options) : Node("processor", options)
  {
    declare_parameter("prefix", "Phone processed: ");
    publisher_ = create_publisher<std_msgs::msg::String>("output", 10);
    subscription_ = create_subscription<std_msgs::msg::String>(
      "input", 10, [this](std_msgs::msg::String::ConstSharedPtr input) {
        std_msgs::msg::String output;
        output.data = get_parameter("prefix").as_string() + input->data;
        publisher_->publish(output);
      });
  }

private:
  rclcpp::Publisher<std_msgs::msg::String>::SharedPtr publisher_;
  rclcpp::Subscription<std_msgs::msg::String>::SharedPtr subscription_;
};

extern "C" rclcpp::Node * ros2_android_create_component(
  const char * component, const rclcpp::NodeOptions * options)
{
  const std::string name(component);
  if (name == "producer") {
    return new Producer(*options);
  }

  if (name == "processor") {
    return new Processor(*options);
  }

  throw std::runtime_error("Unknown project component: " + name);
}
