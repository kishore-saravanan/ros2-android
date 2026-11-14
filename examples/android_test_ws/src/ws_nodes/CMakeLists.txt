cmake_minimum_required(VERSION 3.22)
project(ws_nodes)

find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(ws_interfaces REQUIRED)
find_package(std_msgs REQUIRED)

add_executable(talker src/talker.cpp)
ament_target_dependencies(talker rclcpp ws_interfaces)
target_compile_features(talker PRIVATE cxx_std_17)

add_executable(processor src/processor.cpp)
ament_target_dependencies(processor rclcpp ws_interfaces std_msgs)
target_compile_features(processor PRIVATE cxx_std_17)

install(TARGETS talker processor DESTINATION lib/${PROJECT_NAME})
ament_package()
