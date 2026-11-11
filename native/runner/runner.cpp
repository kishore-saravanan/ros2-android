#include "ros2_android_project.hpp"
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
std::mutex lifecycle, errors;
std::shared_ptr<rclcpp::Context> context;
std::shared_ptr<rclcpp::executors::SingleThreadedExecutor> executor;
rclcpp::Node::SharedPtr host_node;
std::vector<rclcpp::Node::SharedPtr> project_nodes;
rclcpp::TimerBase::SharedPtr heartbeat;
std::thread spin_thread;
std::atomic<bool> running{false};
std::atomic<uint64_t> pulses{0};
std::string last_error;

std::string utf8(JNIEnv * env, jstring input) {
  if (!input) return {};
  const auto length = env->GetStringLength(input);
  const auto * units = env->GetStringChars(input, nullptr);
  if (!units) throw std::runtime_error("Cannot read Java string");
  std::string out;
  for (jsize i = 0; i < length; ++i) {
    uint32_t code = units[i];
    if (code >= 0xd800 && code <= 0xdbff && i + 1 < length && units[i + 1] >= 0xdc00 && units[i + 1] <= 0xdfff) {
      code = 0x10000 + ((code - 0xd800) << 10) + (units[++i] - 0xdc00);
    } else if (code >= 0xd800 && code <= 0xdfff) code = 0xfffd;
    if (code < 0x80) out += static_cast<char>(code);
    else if (code < 0x800) {
      out += static_cast<char>(0xc0 | (code >> 6)); out += static_cast<char>(0x80 | (code & 0x3f));
    } else if (code < 0x10000) {
      out += static_cast<char>(0xe0 | (code >> 12)); out += static_cast<char>(0x80 | ((code >> 6) & 0x3f)); out += static_cast<char>(0x80 | (code & 0x3f));
    } else {
      out += static_cast<char>(0xf0 | (code >> 18)); out += static_cast<char>(0x80 | ((code >> 12) & 0x3f)); out += static_cast<char>(0x80 | ((code >> 6) & 0x3f)); out += static_cast<char>(0x80 | (code & 0x3f));
    }
  }
  env->ReleaseStringChars(input, units);
  return out;
}
std::string json_string(const std::string & input) {
  std::ostringstream out; out << '"';
  // ASCII-escape non-ASCII bytes in diagnostics; ROS user payloads stay UTF-8.
  for (const unsigned char c : input) {
    if (c == '"' || c == '\\') out << '\\' << c;
    else if (c >= 32 && c < 127) out << c;
    else out << '?';
  }
  out << '"'; return out.str();
}
void stop_locked() {
  running.store(false);
  if (executor) executor->cancel();
  if (spin_thread.joinable()) spin_thread.join();
  heartbeat.reset(); executor.reset(); project_nodes.clear(); host_node.reset();
  if (context && context->is_valid()) context->shutdown("Project stopped");
  context.reset();
}
void report(const std::string & error) {
  std::lock_guard<std::mutex> lock(errors); last_error = error;
  __android_log_print(ANDROID_LOG_ERROR, "ROS2AndroidProject", "%s", error.c_str());
}
}

extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_runner_ProjectNative_startConfigured(
  JNIEnv * env, jobject, jint domain, jstring name_space, jstring network_xml,
  jstring library_dir, jobjectArray arguments, jobjectArray components, jobjectArray node_arguments) {
  std::lock_guard<std::mutex> lock(lifecycle);
  try {
    if (context) throw std::runtime_error("Stop the current project before starting another");
    if (domain < 0 || domain > 101) throw std::runtime_error("Invalid ROS domain");
    const auto lib = utf8(env, library_dir), xml = utf8(env, network_xml);
    setenv("LD_LIBRARY_PATH", lib.c_str(), 1);
    setenv("RMW_IMPLEMENTATION", "rmw_cyclonedds_cpp", 1);
    setenv("CYCLONEDDS_URI", xml.c_str(), 1);
    rclcpp::InitOptions init;
    init.set_domain_id(static_cast<size_t>(domain)); init.shutdown_on_signal = false;
    context = std::make_shared<rclcpp::Context>();
    context->init(0, nullptr, init); // Do not take over Android process signal handlers.
    std::vector<std::string> args{"--ros-args", "-r", "__ns:=" + utf8(env, name_space)};
    const auto length = env->GetArrayLength(arguments);
    for (jsize i = 0; i < length; ++i) {
      auto item = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
      args.push_back(utf8(env, item)); env->DeleteLocalRef(item);
    }
    const auto count = env->GetArrayLength(components);
    if (count < 1 || count > 16 || count != env->GetArrayLength(node_arguments))
      throw std::runtime_error("Launch must contain 1 to 16 nodes");
    for (jsize index = 0; index < count; ++index) {
      // rcl remapping uses the first matching rule. Per-node launch settings
      // precede common CLI defaults, including the fallback namespace.
      std::vector<std::string> node_args{"--ros-args"};
      auto extra = static_cast<jobjectArray>(env->GetObjectArrayElement(node_arguments, index));
      for (jsize j = 0; j < env->GetArrayLength(extra); ++j) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(extra, j));
        node_args.push_back(utf8(env, item)); env->DeleteLocalRef(item);
      }
      env->DeleteLocalRef(extra);
      node_args.insert(node_args.end(), args.begin() + 1, args.end());
      rclcpp::NodeOptions options;
      options.context(context).arguments(node_args).enable_rosout(false);
#if ROS2_ANDROID_PROJECT_ABI == 1
      if (count != 1) throw std::runtime_error("ABI 1 supports one node only");
      rclcpp::Node::SharedPtr node(ros2_android_create_node(&options));
#else
      auto component = static_cast<jstring>(env->GetObjectArrayElement(components, index));
      const auto component_name = utf8(env, component); env->DeleteLocalRef(component);
      rclcpp::Node::SharedPtr node(ros2_android_create_component(component_name.c_str(), &options));
#endif
      if (!node) throw std::runtime_error("Project factory returned no node");
      for (const auto & existing : project_nodes)
        if (std::string(existing->get_fully_qualified_name()) == node->get_fully_qualified_name())
          throw std::runtime_error("Duplicate launched node name");
      project_nodes.push_back(node);
    }
    rclcpp::NodeOptions host_options;
    host_options.context(context).use_global_arguments(false).enable_rosout(false).start_parameter_services(false);
    host_node = std::make_shared<rclcpp::Node>("android_project_host", "/ros2_android", host_options);
    pulses.store(0);
    heartbeat = host_node->create_wall_timer(std::chrono::seconds(1), [] { ++pulses; });
    rclcpp::ExecutorOptions executor_options; executor_options.context = context;
    executor = std::make_shared<rclcpp::executors::SingleThreadedExecutor>(executor_options);
    for (const auto & node : project_nodes) executor->add_node(node);
    executor->add_node(host_node);
    { std::lock_guard<std::mutex> error_lock(errors); last_error.clear(); }
    running.store(true);
    spin_thread = std::thread([] {
      try { executor->spin(); }
      catch (const std::exception & error) { report(error.what()); }
      catch (...) { report("Unhandled project exception"); }
      running.store(false);
    });
    __android_log_print(ANDROID_LOG_INFO, "ROS2AndroidProject", "Project started with %zu node(s)", project_nodes.size());
  } catch (const std::exception & error) {
    stop_locked(); report(error.what());
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
  }
}
extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_runner_ProjectNative_stop(JNIEnv *, jobject) {
  std::lock_guard<std::mutex> lock(lifecycle); stop_locked();
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_ros2android_runner_ProjectNative_status(JNIEnv * env, jobject) {
  std::unique_lock<std::mutex> lock(lifecycle, std::try_to_lock);
  if (!lock.owns_lock()) return env->NewStringUTF("{\"transition\":true}");
  std::lock_guard<std::mutex> error_lock(errors);
  std::ostringstream out;
  out << "{\"running\":" << (running.load() ? "true" : "false")
      << ",\"heartbeat\":" << pulses.load()
      << ",\"node\":" << json_string(project_nodes.empty() ? "" : project_nodes.front()->get_fully_qualified_name())
      << ",\"nodes\":[";
  for (size_t i = 0; i < project_nodes.size(); ++i) {
    if (i) out << ',';
    out << json_string(project_nodes[i]->get_fully_qualified_name());
  }
  out << "],\"error\":" << json_string(last_error) << '}';
  return env->NewStringUTF(out.str().c_str());
}
