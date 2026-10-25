#pragma once
#include <rclcpp/rclcpp.hpp>

// Build runner and project with the same pinned ROS/NDK/libc++ SDK.
// Node ownership transfers to the runner. Do not install signal handlers.
#ifndef ROS2_ANDROID_PROJECT_ABI
#define ROS2_ANDROID_PROJECT_ABI 1
#endif
#if ROS2_ANDROID_PROJECT_ABI == 1
extern "C" rclcpp::Node * ros2_android_create_node(const rclcpp::NodeOptions * options);
#else
// ABI 2: component selects one compiled-in factory; called once per launch entry.
extern "C" rclcpp::Node * ros2_android_create_component(
    const char * component, const rclcpp::NodeOptions * options);
#endif
