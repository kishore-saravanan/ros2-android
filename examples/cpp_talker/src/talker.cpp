#include "ros2_android_project.hpp"
#include <demo_interfaces/msg/runtime_status.hpp>
#include <sensor_msgs/msg/imu.hpp>
#include <std_msgs/msg/float64.hpp>
#include <std_msgs/msg/string.hpp>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <string>

class DemoNode : public rclcpp::Node {
 public:
  explicit DemoNode(const rclcpp::NodeOptions & options) : Node("cpp_demo", options) {
    declare_parameter("message", "Hello from ROS2 Android C++");
    const auto imu_topic = declare_parameter("imu_topic", "/phone/imu");
    status_ = create_publisher<std_msgs::msg::String>("status", 10);
    telemetry_ = create_publisher<demo_interfaces::msg::RuntimeStatus>("runtime_status", 10);
    echo_ = create_publisher<std_msgs::msg::String>("echo", 10);
    magnitude_ = create_publisher<std_msgs::msg::Float64>("acceleration_magnitude", rclcpp::SensorDataQoS());
    command_ = create_subscription<std_msgs::msg::String>("command", 10, [this](std_msgs::msg::String::ConstSharedPtr input) {
      std_msgs::msg::String out;
      out.data = "phone C++ processed: " + input->data;
      echo_->publish(out);
    });
    imu_ = create_subscription<sensor_msgs::msg::Imu>(imu_topic, rclcpp::SensorDataQoS(), [this](sensor_msgs::msg::Imu::ConstSharedPtr input) {
      const auto & a = input->linear_acceleration;
      std_msgs::msg::Float64 out;
      out.data = std::sqrt(a.x * a.x + a.y * a.y + a.z * a.z);
      magnitude_->publish(out);
      ++imu_count_;
    });
    timer_ = create_wall_timer(std::chrono::seconds(1), [this] {
      std_msgs::msg::String out;
      out.data = get_parameter("message").as_string() + " | tick=" + std::to_string(++tick_) +
        " | imu=" + std::to_string(imu_count_);
      status_->publish(out);
      demo_interfaces::msg::RuntimeStatus telemetry;
      telemetry.message = get_parameter("message").as_string();
      telemetry.tick = tick_; telemetry.imu_samples = imu_count_;
      telemetry_->publish(telemetry);
    });
  }
 private:
  uint64_t tick_ = 0;
  uint64_t imu_count_ = 0;
  rclcpp::Publisher<std_msgs::msg::String>::SharedPtr status_, echo_;
  rclcpp::Publisher<std_msgs::msg::Float64>::SharedPtr magnitude_;
  rclcpp::Publisher<demo_interfaces::msg::RuntimeStatus>::SharedPtr telemetry_;
  rclcpp::Subscription<std_msgs::msg::String>::SharedPtr command_;
  rclcpp::Subscription<sensor_msgs::msg::Imu>::SharedPtr imu_;
  rclcpp::TimerBase::SharedPtr timer_;
};

extern "C" rclcpp::Node * ros2_android_create_node(const rclcpp::NodeOptions * options) {
  return new DemoNode(*options);
}
