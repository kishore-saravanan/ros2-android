#include <rclcpp/rclcpp.hpp>
#include <ws_interfaces/msg/sample.hpp>
#include <std_msgs/msg/string.hpp>
#include <stdexcept>
#include <string>
class Processor : public rclcpp::Node {
 public:
  Processor() : Node("processor") {
    declare_parameter("fail_after_samples", int64_t{0});
    publisher_ = create_publisher<std_msgs::msg::String>("result", 10);
    subscription_ = create_subscription<ws_interfaces::msg::Sample>("input", 10, [this](ws_interfaces::msg::Sample::ConstSharedPtr sample) {
      std_msgs::msg::String output;
      output.data = "{\"sequence\":" + std::to_string(sample->sequence) + ",\"squared\":" +
        std::to_string(sample->value * sample->value) + "}";
      publisher_->publish(output);
      const auto fail_after = get_parameter("fail_after_samples").as_int();
      if (fail_after > 0 && ++received_ >= fail_after) throw std::runtime_error("Requested workspace process failure");
    });
  }
 private:
  int64_t received_ = 0;
  rclcpp::Publisher<std_msgs::msg::String>::SharedPtr publisher_;
  rclcpp::Subscription<ws_interfaces::msg::Sample>::SharedPtr subscription_;
};
int main(int argc, char ** argv) {
  rclcpp::init(argc, argv);
  rclcpp::spin(std::make_shared<Processor>());
  rclcpp::shutdown();
  return 0;
}
