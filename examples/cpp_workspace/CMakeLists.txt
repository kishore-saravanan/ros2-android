cmake_minimum_required(VERSION 3.22.1)
project(cpp_workspace LANGUAGES CXX)

find_package(rclcpp REQUIRED)
find_package(std_msgs REQUIRED)

add_library(robot_project STATIC src/components.cpp)
set_target_properties(robot_project PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(robot_project PRIVATE "${ROS2_ANDROID_SDK_INCLUDE}")
target_link_libraries(robot_project PUBLIC
  rclcpp::rclcpp
  std_msgs::std_msgs__rosidl_typesupport_cpp)
target_compile_features(robot_project PUBLIC cxx_std_17)
