cmake_minimum_required(VERSION 3.22.1)
project(ros2_android_python_environment LANGUAGES CXX)

add_library(python_environment SHARED environment.cpp)
target_compile_features(python_environment PRIVATE cxx_std_17)
target_compile_options(python_environment PRIVATE -Wall -Wextra -Werror)
target_link_options(python_environment PRIVATE
  "-Wl,-z,max-page-size=16384,-z,common-page-size=16384")
