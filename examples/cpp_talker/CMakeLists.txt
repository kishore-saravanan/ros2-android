cmake_minimum_required(VERSION 3.22.1)
project(cpp_talker LANGUAGES CXX)

find_package(rclcpp REQUIRED)
find_package(std_msgs REQUIRED)
find_package(sensor_msgs REQUIRED)

add_subdirectory(interfaces/demo_interfaces)
rosidl_get_typesupport_target(project_message_types demo_interfaces "rosidl_typesupport_cpp")

add_library(robot_project STATIC src/talker.cpp)
set_target_properties(robot_project PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(robot_project PRIVATE "${ROS2_ANDROID_SDK_INCLUDE}")
target_link_libraries(robot_project PUBLIC
  rclcpp::rclcpp
  std_msgs::std_msgs__rosidl_typesupport_cpp
  sensor_msgs::sensor_msgs__rosidl_typesupport_cpp
  ${project_message_types})
target_compile_features(robot_project PUBLIC cxx_std_17)
