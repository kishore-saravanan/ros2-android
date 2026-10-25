#pragma once
#include <rclcpp/rclcpp.hpp>

// Runtime ABI 1: build runner and project with the same pinned ROS/NDK/libc++ SDK.
// Ownership of the returned Node transfers to the runner. Do not install signal handlers.
extern "C" rclcpp::Node * ros2_android_create_node(const rclcpp::NodeOptions * options);
