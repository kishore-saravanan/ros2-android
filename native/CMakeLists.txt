cmake_minimum_required(VERSION 3.22.1)
project(ros2android LANGUAGES CXX)
get_filename_component(ROS_ANDROID_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/../native-deps/install" REALPATH)
if(NOT EXISTS "${ROS_ANDROID_PREFIX}/lib/librcl.so")
  message(FATAL_ERROR "Build ARM64 ROS dependencies first with setup/build-native-ros.sh")
endif()
list(PREPEND CMAKE_PREFIX_PATH "${ROS_ANDROID_PREFIX}")
list(APPEND CMAKE_FIND_ROOT_PATH "${ROS_ANDROID_PREFIX}")
file(GLOB ROS_HOST_PYTHON_PATHS "${ROS_ANDROID_PREFIX}/lib/python*/site-packages")
list(JOIN ROS_HOST_PYTHON_PATHS ":" ROS_HOST_PYTHON_PATH)
set(ENV{PYTHONPATH} "${ROS_HOST_PYTHON_PATH}:$ENV{PYTHONPATH}")
set(ENV{AMENT_PREFIX_PATH} "${ROS_ANDROID_PREFIX}")
set(STATIC_ROSIDL_TYPESUPPORT_C rosidl_typesupport_introspection_c)
set(STATIC_ROSIDL_TYPESUPPORT_CPP rosidl_typesupport_introspection_cpp)
find_package(rcl REQUIRED)
find_package(sensor_msgs REQUIRED)
find_package(geometry_msgs REQUIRED)
find_package(std_msgs REQUIRED)
add_library(ros2android SHARED ros_runtime.cpp usb_pty.cpp)
target_link_libraries(ros2android PRIVATE rcl::rcl
  sensor_msgs::sensor_msgs__rosidl_generator_c
  sensor_msgs::sensor_msgs__rosidl_typesupport_introspection_c
  geometry_msgs::geometry_msgs__rosidl_generator_c
  geometry_msgs::geometry_msgs__rosidl_typesupport_introspection_c
  std_msgs::std_msgs__rosidl_generator_c
  std_msgs::std_msgs__rosidl_typesupport_introspection_c)
target_compile_features(ros2android PRIVATE cxx_std_17)
target_link_options(ros2android PRIVATE "-Wl,-z,max-page-size=16384,-z,common-page-size=16384")

