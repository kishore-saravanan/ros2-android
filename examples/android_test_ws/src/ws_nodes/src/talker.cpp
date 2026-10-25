#include <rclcpp/rclcpp.hpp>
#include <ws_interfaces/msg/sample.hpp>
#include <chrono>
#include <stdexcept>
class Talker : public rclcpp::Node {
 public:
  Talker() : Node("talker") {
    const auto period = declare_parameter("period_ms", int64_t{200});
    if (period < 20 || period > 60000) throw std::runtime_error("period_ms out of range");
    publisher_ = create_publisher<ws_interfaces::msg::Sample>("input", 10);
    timer_ = create_wall_timer(std::chrono::milliseconds(period), [this] {
      ws_interfaces::msg::Sample sample;
      sample.sequence = ++sequence_; sample.value = sequence_ * 0.25;
      publisher_->publish(sample);
    });
    RCLCPP_INFO(get_logger(), "talker ready");
  }
 private:
  int64_t sequence_ = 0;
  rclcpp::Publisher<ws_interfaces::msg::Sample>::SharedPtr publisher_;
  rclcpp::TimerBase::SharedPtr timer_;
};
int main(int argc, char ** argv) {
  rclcpp::init(argc, argv);
  rclcpp::spin(std::make_shared<Talker>());
  rclcpp::shutdown();
  return 0;
}
