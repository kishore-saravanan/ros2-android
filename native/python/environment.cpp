#include <jni.h>
#include <cstdlib>
#include <string>

namespace {
std::string string(JNIEnv * env, jstring value) {
  const char * chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) return {};
  std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
}
extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_runner_PythonEnvironment_configure(
    JNIEnv * env, jobject, jstring network_xml, jstring libraries, jstring prefix) {
  const auto xml = string(env, network_xml);
  setenv("CYCLONEDDS_URI", xml.c_str(), 1);
  setenv("RMW_IMPLEMENTATION", "rmw_cyclonedds_cpp", 1);
  setenv("ROS_DISTRO", "jazzy", 1);
  setenv("LD_LIBRARY_PATH", string(env, libraries).c_str(), 1);
  setenv("AMENT_PREFIX_PATH", string(env, prefix).c_str(), 1);
}
