#include <jni.h>
#include <rcl/rcl.h>
#include <rcl/error_handling.h>
#include <rcl/graph.h>
#include <rcutils/error_handling.h>
#include <rmw/qos_profiles.h>
#include <rosidl_runtime_c/string_functions.h>
#include <rosidl_runtime_c/primitives_sequence_functions.h>
#include <geometry_msgs/msg/vector3_stamped.h>
#include <geometry_msgs/msg/detail/vector3_stamped__rosidl_typesupport_introspection_c.h>
#include <sensor_msgs/msg/imu.h>
#include <sensor_msgs/msg/magnetic_field.h>
#include <sensor_msgs/msg/illuminance.h>
#include <sensor_msgs/msg/fluid_pressure.h>
#include <sensor_msgs/msg/compressed_image.h>
#include <sensor_msgs/msg/detail/imu__rosidl_typesupport_introspection_c.h>
#include <sensor_msgs/msg/detail/magnetic_field__rosidl_typesupport_introspection_c.h>
#include <sensor_msgs/msg/detail/illuminance__rosidl_typesupport_introspection_c.h>
#include <sensor_msgs/msg/detail/fluid_pressure__rosidl_typesupport_introspection_c.h>
#include <sensor_msgs/msg/detail/compressed_image__rosidl_typesupport_introspection_c.h>
#include <std_msgs/msg/string.h>
#include <std_msgs/msg/float64.h>
#include <std_msgs/msg/bool.h>
#include <std_msgs/msg/detail/bool__rosidl_typesupport_introspection_c.h>
#include <geometry_msgs/msg/twist.h>
#include <geometry_msgs/msg/detail/twist__rosidl_typesupport_introspection_c.h>
#include <cmath>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <limits>
#include <std_msgs/msg/int32.h>
#include <std_msgs/msg/int64.h>
#include <std_msgs/msg/float32.h>
#include <std_msgs/msg/detail/int32__rosidl_typesupport_introspection_c.h>
#include <std_msgs/msg/detail/int64__rosidl_typesupport_introspection_c.h>
#include <std_msgs/msg/detail/float32__rosidl_typesupport_introspection_c.h>
#include <std_msgs/msg/detail/string__rosidl_typesupport_introspection_c.h>
#include <std_msgs/msg/detail/float64__rosidl_typesupport_introspection_c.h>
#include <chrono>
#include <cstdlib>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace
{
std::mutex gate;
rcl_context_t context = rcl_get_zero_initialized_context();
rcl_node_t node = rcl_get_zero_initialized_node();
rcl_subscription_t display = rcl_get_zero_initialized_subscription();
bool running = false;
bool subscribed = false;
std::string last_error;
std::string node_namespace;

struct Publisher
{
  rcl_publisher_t handle = rcl_get_zero_initialized_publisher();
  int kind = 0;
  int64_t sent = 0;
};

std::map<std::string, std::unique_ptr<Publisher>> publishers;

struct DashboardSubscription
{
  rcl_subscription_t handle = rcl_get_zero_initialized_subscription();
  int kind = 0;
};

std::map<std::string, std::unique_ptr<DashboardSubscription>> dashboard_subscriptions;

std::string str(JNIEnv * env, jstring input)
{
  if (!input) {
    return {};
  }

  const char * chars = env->GetStringUTFChars(input, nullptr);
  if (!chars) {
    throw std::runtime_error("Cannot read Java string");
  }

  std::string result(chars);
  env->ReleaseStringUTFChars(input, chars);
  return result;
}

void check(rcl_ret_t result)
{
  if (result == RCL_RET_OK) {
    return;
  }

  const auto error = rcl_get_error_string();
  std::string message = error.str;
  rcl_reset_error();
  throw std::runtime_error(message.empty() ? "ROS operation failed" : message);
}

void raise(JNIEnv * env, const std::exception & error)
{
  last_error = error.what();
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

void require_running()
{
  if (!running) {
    throw std::runtime_error("Start the ROS session first");
  }
}

void finish(rcl_ret_t result)
{
  if (result != RCL_RET_OK) {
    rcl_reset_error();
  }
}

void stop_locked()
{
  if (!running) {
    return;
  }

  if (subscribed) {
    finish(rcl_subscription_fini(&display, &node));
    subscribed = false;
  }

  for (auto & item : publishers) {
    finish(rcl_publisher_fini(&item.second->handle, &node));
  }

  publishers.clear();

  for (auto & item : dashboard_subscriptions) {
    finish(rcl_subscription_fini(&item.second->handle, &node));
  }

  dashboard_subscriptions.clear();
  finish(rcl_node_fini(&node));
  finish(rcl_shutdown(&context));
  finish(rcl_context_fini(&context));
  context = rcl_get_zero_initialized_context();
  node = rcl_get_zero_initialized_node();
  running = false;
}

const rosidl_message_type_support_t * support(int kind)
{
  switch (kind) {
    case 0:
    case 7:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, sensor_msgs, msg, Imu)();
    case 1:
    case 2:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, geometry_msgs, msg, Vector3Stamped)();
    case 3:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, sensor_msgs, msg, MagneticField)();
    case 4:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, sensor_msgs, msg, Illuminance)();
    case 5:
    case 9:
    case 13:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, std_msgs, msg, Float64)();
    case 6:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, sensor_msgs, msg, FluidPressure)();
    case 8:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, sensor_msgs, msg, CompressedImage)();
    case 10:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, std_msgs, msg, Bool)();
    case 11:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, std_msgs, msg, String)();
    case 12:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, geometry_msgs, msg, Twist)();
    case 14:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, std_msgs, msg, Int32)();
    case 15:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, std_msgs, msg, Float32)();
    case 16:
      return ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
        rosidl_typesupport_introspection_c, std_msgs, msg, Int64)();
    default:
      throw std::runtime_error("Unsupported publisher type");
  }
}

void header(std_msgs__msg__Header & out, int64_t timestamp, bool optical = false)
{
  out.stamp.sec = static_cast<int32_t>(timestamp / 1000000000LL);
  out.stamp.nanosec = static_cast<uint32_t>(timestamp % 1000000000LL);

  if (!rosidl_runtime_c__String__assign(
        &out.frame_id, optical ? "phone_camera_optical_frame" : "phone_sensor_frame")) {
    throw std::runtime_error("Cannot allocate frame ID");
  }
}

bool send(Publisher & pub, const void * data)
{
  const auto result = rcl_publish(&pub.handle, data, nullptr);
  if (result != RCL_RET_OK) {
    last_error = rcl_get_error_string().str;
    rcl_reset_error();
    return false;
  }

  pub.sent++;
  return true;
}

bool sample(Publisher & pub, int64_t time, const std::vector<float> & values)
{
  const auto need = pub.kind == 0 ? 6U : pub.kind == 7 ? 4U : pub.kind <= 3 ? 3U : 1U;
  if (values.size() < need) {
    throw std::runtime_error("Incomplete sensor sample");
  }

  bool result = false;

  switch (pub.kind) {
    case 0:
    case 7: {
      auto * m = sensor_msgs__msg__Imu__create();
      if (!m) {
        throw std::bad_alloc();
      }

      header(m->header, time);

      if (pub.kind == 0) {
        m->orientation_covariance[0] = -1;
        m->linear_acceleration.x = values[0];
        m->linear_acceleration.y = values[1];
        m->linear_acceleration.z = values[2];
        m->angular_velocity.x = values[3];
        m->angular_velocity.y = values[4];
        m->angular_velocity.z = values[5];
      } else {
        m->orientation.x = values[0];
        m->orientation.y = values[1];
        m->orientation.z = values[2];
        m->orientation.w = values[3];
        m->angular_velocity_covariance[0] = -1;
        m->linear_acceleration_covariance[0] = -1;
      }

      result = send(pub, m);
      sensor_msgs__msg__Imu__destroy(m);
      break;
    }
    case 1:
    case 2: {
      auto * m = geometry_msgs__msg__Vector3Stamped__create();
      if (!m) {
        throw std::bad_alloc();
      }

      header(m->header, time);
      m->vector.x = values[0];
      m->vector.y = values[1];
      m->vector.z = values[2];
      result = send(pub, m);
      geometry_msgs__msg__Vector3Stamped__destroy(m);
      break;
    }
    case 3: {
      auto * m = sensor_msgs__msg__MagneticField__create();
      if (!m) {
        throw std::bad_alloc();
      }

      header(m->header, time);
      m->magnetic_field.x = values[0] * 1e-6;
      m->magnetic_field.y = values[1] * 1e-6;
      m->magnetic_field.z = values[2] * 1e-6;
      result = send(pub, m);
      sensor_msgs__msg__MagneticField__destroy(m);
      break;
    }
    case 4: {
      auto * m = sensor_msgs__msg__Illuminance__create();
      if (!m) {
        throw std::bad_alloc();
      }

      header(m->header, time);
      m->illuminance = values[0];
      result = send(pub, m);
      sensor_msgs__msg__Illuminance__destroy(m);
      break;
    }
    case 5:
    case 9:
    case 13: {
      std_msgs__msg__Float64 m{};
      m.data = values[0];
      result = send(pub, &m);
      break;
    }
    case 6: {
      auto * m = sensor_msgs__msg__FluidPressure__create();
      if (!m) {
        throw std::bad_alloc();
      }

      header(m->header, time);
      m->fluid_pressure = values[0] * 100.0;
      result = send(pub, m);
      sensor_msgs__msg__FluidPressure__destroy(m);
      break;
    }
    default:
      throw std::runtime_error("Use the image publisher for camera data");
  }

  return result;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_ros2android_android_NativeRuntime_describe(JNIEnv * env, jobject)
{
  return env->NewStringUTF(
    "ROS 2 Jazzy / rcl / Cyclone DDS / ARM64\nNative publisher and subscriber runtime");
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_ros2android_android_NativeRuntime_monotonicTimeNanos(JNIEnv *, jobject)
{
  return static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                              std::chrono::steady_clock::now().time_since_epoch())
                              .count());
}

extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_android_NativeRuntime_startConfigured(
  JNIEnv * env, jobject, jint domain, jstring ns, jstring network_xml, jstring directory)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    stop_locked();
    const std::string xml = str(env, network_xml), lib = str(env, directory);
    node_namespace = str(env, ns);
    setenv("LD_LIBRARY_PATH", lib.c_str(), 1);
    setenv("RMW_IMPLEMENTATION", "rmw_cyclonedds_cpp", 1);
    setenv("CYCLONEDDS_URI", xml.c_str(), 1);
    auto options = rcl_get_zero_initialized_init_options();
    check(rcl_init_options_init(&options, rcl_get_default_allocator()));
    const auto domain_result =
      rcl_init_options_set_domain_id(&options, static_cast<size_t>(domain));
    if (domain_result != RCL_RET_OK) {
      finish(rcl_init_options_fini(&options));
      check(domain_result);
    }

    const auto init_result = rcl_init(0, nullptr, &options, &context);
    finish(rcl_init_options_fini(&options));
    check(init_result);
    auto node_options = rcl_node_get_default_options();
    node_options.enable_rosout = false;
    const auto node_result =
      rcl_node_init(&node, "robot_brain", node_namespace.c_str(), &context, &node_options);
    if (node_result != RCL_RET_OK) {
      finish(rcl_shutdown(&context));
      finish(rcl_context_fini(&context));
      context = rcl_get_zero_initialized_context();
      check(node_result);
    }

    running = true;
    last_error.clear();
  } catch (const std::exception & error) {
    raise(env, error);
  }
}

extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_android_NativeRuntime_stop(JNIEnv *, jobject)
{
  std::lock_guard<std::mutex> lock(gate);
  stop_locked();
}

extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_android_NativeRuntime_createPublisher(
  JNIEnv * env, jobject, jstring id, jstring topic, jint kind)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    require_running();
    const auto key = str(env, id);
    if (publishers.count(key)) {
      throw std::runtime_error("Publisher already active");
    }

    auto pub = std::make_unique<Publisher>();
    pub->kind = kind;
    auto options = rcl_publisher_get_default_options();
    options.qos = kind >= 10 ? rmw_qos_profile_default : rmw_qos_profile_sensor_data;

    if (kind >= 10) {
      options.qos.depth = 1;
    }

    check(
      rcl_publisher_init(&pub->handle, &node, support(kind), str(env, topic).c_str(), &options));
    publishers[key] = std::move(pub);
  } catch (const std::exception & error) {
    raise(env, error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ros2android_android_NativeRuntime_removePublisher(JNIEnv * env, jobject, jstring id)
{
  std::lock_guard<std::mutex> lock(gate);
  const auto it = publishers.find(str(env, id));

  if (it != publishers.end()) {
    finish(rcl_publisher_fini(&it->second->handle, &node));
    publishers.erase(it);
  }
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_ros2android_android_NativeRuntime_publishSample(
  JNIEnv * env, jobject, jstring id, jlong time, jfloatArray data)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    const auto it = publishers.find(str(env, id));

    if (it == publishers.end()) {
      return JNI_FALSE;
    }

    std::vector<float> values(static_cast<size_t>(env->GetArrayLength(data)));
    env->GetFloatArrayRegion(data, 0, static_cast<jsize>(values.size()), values.data());
    return sample(*it->second, time, values) ? JNI_TRUE : JNI_FALSE;
  } catch (const std::exception & error) {
    last_error = error.what();
    return JNI_FALSE;
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_ros2android_android_NativeRuntime_publishControlBytes(
  JNIEnv * env, jobject, jstring id, jdoubleArray data, jbyteArray text)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    require_running();
    auto found = publishers.find(str(env, id));
    if (found == publishers.end()) {
      return false;
    }

    auto & pub = *found->second;
    const auto length = env->GetArrayLength(data);
    if (length < 2) {
      throw std::runtime_error("Control needs two numeric fields");
    }

    jdouble values[2];
    env->GetDoubleArrayRegion(data, 0, 2, values);
    if (!std::isfinite(values[0]) || !std::isfinite(values[1])) {
      throw std::runtime_error("Invalid control value");
    }

    if (pub.kind == 10) {
      std_msgs__msg__Bool msg{};
      msg.data = values[0] != 0;
      return send(pub, &msg);
    }

    if (pub.kind == 13) {
      std_msgs__msg__Float64 msg{};
      msg.data = values[0];
      return send(pub, &msg);
    }

    if (pub.kind == 12) {
      geometry_msgs__msg__Twist msg{};
      msg.linear.x = values[0];
      msg.angular.z = values[1];
      return send(pub, &msg);
    }

    if (pub.kind == 11) {
      std_msgs__msg__String msg{};
      std_msgs__msg__String__init(&msg);
      const auto size = env->GetArrayLength(text);
      if (size > 4096) {
        std_msgs__msg__String__fini(&msg);
        throw std::runtime_error("Control text too long");
      }

      std::string value(static_cast<size_t>(size), '\0');
      env->GetByteArrayRegion(text, 0, size, reinterpret_cast<jbyte *>(value.data()));
      const bool assigned =
        rosidl_runtime_c__String__assignn(&msg.data, value.data(), value.size());
      const bool sent = assigned && send(pub, &msg);
      std_msgs__msg__String__fini(&msg);
      return sent;
    }

    throw std::runtime_error("Unsupported control publisher");
  } catch (const std::exception & error) {
    raise(env, error);
    return false;
  }
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_ros2android_android_NativeRuntime_publishImage(
  JNIEnv * env, jobject, jstring id, jlong time, jbyteArray data)
{
  std::lock_guard<std::mutex> lock(gate);
  const auto it = publishers.find(str(env, id));

  if (it == publishers.end() || it->second->kind != 8) {
    return JNI_FALSE;
  }

  auto * message = sensor_msgs__msg__CompressedImage__create();
  if (!message) {
    return JNI_FALSE;
  }

  bool result = false;

  try {
    header(message->header, time, true);

    if (!rosidl_runtime_c__String__assign(
          &message->header.frame_id, (str(env, id) + "_optical_frame").c_str())) {
      throw std::bad_alloc();
    }

    if (!rosidl_runtime_c__String__assign(&message->format, "jpeg")) {
      throw std::bad_alloc();
    }

    const auto size = env->GetArrayLength(data);
    if (!rosidl_runtime_c__uint8__Sequence__init(&message->data, static_cast<size_t>(size))) {
      throw std::bad_alloc();
    }

    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte *>(message->data.data));
    result = send(*it->second, message);
  } catch (const std::exception & error) {
    last_error = error.what();
  }

  sensor_msgs__msg__CompressedImage__destroy(message);
  return result ? JNI_TRUE : JNI_FALSE;
}

namespace
{
template <typename Message>
bool take_dashboard_scalar(rcl_subscription_t & handle, std::string & out)
{
  bool received = false;

  for (int i = 0; i < 16; ++i) {
    Message message{};
    const auto result = rcl_take(&handle, &message, nullptr, nullptr);
    if (result == RCL_RET_SUBSCRIPTION_TAKE_FAILED) {
      break;
    }

    check(result);
    std::ostringstream formatted;
    formatted << std::setprecision(std::numeric_limits<decltype(message.data)>::max_digits10)
              << message.data;
    out = formatted.str();
    received = true;
  }

  return received;
}

bool take_dashboard_string(rcl_subscription_t & handle, std::string & out)
{
  bool received = false;

  for (int i = 0; i < 16; ++i) {
    std_msgs__msg__String message{};
    std_msgs__msg__String__init(&message);
    const auto result = rcl_take(&handle, &message, nullptr, nullptr);
    if (result == RCL_RET_OK) {
      out.assign(message.data.data, std::min(message.data.size, size_t(4096)));
      received = true;
    }

    std_msgs__msg__String__fini(&message);

    if (result == RCL_RET_SUBSCRIPTION_TAKE_FAILED) {
      break;
    }

    check(result);
  }

  return received;
}

bool take_dashboard_image(rcl_subscription_t & handle, std::string & out)
{
  auto message = std::unique_ptr<
    sensor_msgs__msg__CompressedImage, decltype(&sensor_msgs__msg__CompressedImage__destroy)>(
    sensor_msgs__msg__CompressedImage__create(), sensor_msgs__msg__CompressedImage__destroy);
  if (!message) {
    throw std::runtime_error("Cannot allocate image subscriber");
  }

  const auto result = rcl_take(&handle, message.get(), nullptr, nullptr);
  if (result == RCL_RET_SUBSCRIPTION_TAKE_FAILED) {
    return false;
  }

  check(result);

  // Keep binary image data out of UTF-8 conversion. Decoder also bounds dimensions.
  if (message->data.size > 0 && message->data.size <= 2 * 1024 * 1024) {
    out.assign(reinterpret_cast<const char *>(message->data.data), message->data.size);
  }

  return true;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_ros2android_android_NativeRuntime_createDashboardSubscription(
  JNIEnv * env, jobject, jstring id, jstring topic, jint kind)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    require_running();
    const auto key = str(env, id);
    if (dashboard_subscriptions.count(key)) {
      throw std::runtime_error("Dashboard subscriber already exists");
    }

    if (
      kind != 8 && kind != 10 && kind != 11 && kind != 13 && kind != 14 && kind != 15 &&
      kind != 16) {
      throw std::runtime_error("Unsupported dashboard subscriber type");
    }

    auto sub = std::make_unique<DashboardSubscription>();
    sub->kind = kind;
    auto options = rcl_subscription_get_default_options();
    options.qos = rmw_qos_profile_sensor_data;
    options.qos.depth = 1;
    check(
      rcl_subscription_init(&sub->handle, &node, support(kind), str(env, topic).c_str(), &options));
    dashboard_subscriptions[key] = std::move(sub);
  } catch (const std::exception & error) {
    raise(env, error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ros2android_android_NativeRuntime_removeDashboardSubscription(
  JNIEnv * env, jobject, jstring id)
{
  std::lock_guard<std::mutex> lock(gate);
  const auto found = dashboard_subscriptions.find(str(env, id));
  if (found != dashboard_subscriptions.end()) {
    finish(rcl_subscription_fini(&found->second->handle, &node));
    dashboard_subscriptions.erase(found);
  }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_ros2android_android_NativeRuntime_pollDashboard(JNIEnv * env, jobject)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    std::vector<std::string> updates;

    for (auto & item : dashboard_subscriptions) {
      auto & sub = *item.second;
      std::string value;
      bool received = false;

      switch (sub.kind) {
        case 8:
          received = take_dashboard_image(sub.handle, value);
          break;
        case 10:
          received = take_dashboard_scalar<std_msgs__msg__Bool>(sub.handle, value);
          value = value == "1" ? "true" : "false";
          break;
        case 11:
          received = take_dashboard_string(sub.handle, value);
          break;
        case 13:
          received = take_dashboard_scalar<std_msgs__msg__Float64>(sub.handle, value);
          break;
        case 14:
          received = take_dashboard_scalar<std_msgs__msg__Int32>(sub.handle, value);
          break;
        case 15:
          received = take_dashboard_scalar<std_msgs__msg__Float32>(sub.handle, value);
          break;
        case 16:
          received = take_dashboard_scalar<std_msgs__msg__Int64>(sub.handle, value);
          break;
        default:
          break;
      }

      if (received) {
        updates.push_back(item.first + "\n" + value);
      }
    }

    auto result =
      env->NewObjectArray(static_cast<jsize>(updates.size()), env->FindClass("[B"), nullptr);
    for (size_t i = 0; i < updates.size(); ++i) {
      auto bytes = env->NewByteArray(static_cast<jsize>(updates[i].size()));
      env->SetByteArrayRegion(
        bytes, 0, static_cast<jsize>(updates[i].size()),
        reinterpret_cast<const jbyte *>(updates[i].data()));
      env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
      env->DeleteLocalRef(bytes);
    }

    return result;
  } catch (const std::exception & error) {
    raise(env, error);
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ros2android_android_NativeRuntime_subscribeDisplay(JNIEnv * env, jobject, jstring topic)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    require_running();

    if (subscribed) {
      finish(rcl_subscription_fini(&display, &node));
      subscribed = false;
    }

    display = rcl_get_zero_initialized_subscription();
    auto options = rcl_subscription_get_default_options();
    const auto * typesupport = ROSIDL_TYPESUPPORT_INTERFACE__MESSAGE_SYMBOL_NAME(
      rosidl_typesupport_introspection_c, std_msgs, msg, String)();
    check(rcl_subscription_init(&display, &node, typesupport, str(env, topic).c_str(), &options));
    subscribed = true;
  } catch (const std::exception & error) {
    raise(env, error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ros2android_android_NativeRuntime_unsubscribeDisplay(JNIEnv *, jobject)
{
  std::lock_guard<std::mutex> lock(gate);

  if (subscribed) {
    finish(rcl_subscription_fini(&display, &node));
    subscribed = false;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_ros2android_android_NativeRuntime_pollDisplay(JNIEnv * env, jobject)
{
  std::lock_guard<std::mutex> lock(gate);

  if (!subscribed) {
    return nullptr;
  }

  auto * message = std_msgs__msg__String__create();
  if (!message) {
    return nullptr;
  }

  std::string latest;
  bool received = false;

  for (int i = 0; i < 32; i++) {
    const auto result = rcl_take(&display, message, nullptr, nullptr);
    if (result == RCL_RET_SUBSCRIPTION_TAKE_FAILED) {
      break;
    }

    if (result != RCL_RET_OK) {
      last_error = rcl_get_error_string().str;
      rcl_reset_error();
      break;
    }

    latest.assign(message->data.data, message->data.size < 4096 ? message->data.size : 4096);
    received = true;
  }

  std_msgs__msg__String__destroy(message);

  if (!received) {
    return nullptr;
  }

  // ROS strings use UTF-8; JNI NewStringUTF uses modified UTF-8 and is not
  // suitable for arbitrary incoming Unicode, including emoji.
  jbyteArray bytes = env->NewByteArray(static_cast<jsize>(latest.size()));
  if (!bytes) {
    return nullptr;
  }

  env->SetByteArrayRegion(
    bytes, 0, static_cast<jsize>(latest.size()), reinterpret_cast<const jbyte *>(latest.data()));
  jclass string_class = env->FindClass("java/lang/String");
  jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
  jstring charset = env->NewStringUTF("UTF-8");
  return static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_ros2android_android_NativeRuntime_publishedCount(JNIEnv * env, jobject, jstring id)
{
  std::lock_guard<std::mutex> lock(gate);
  const auto it = publishers.find(str(env, id));
  return it == publishers.end() ? 0 : it->second->sent;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_ros2android_android_NativeRuntime_status(JNIEnv * env, jobject)
{
  std::lock_guard<std::mutex> lock(gate);
  const auto result = running ? "ROS node " + node_namespace + "/robot_brain · " +
                                  std::to_string(publishers.size()) + " publisher(s)" +
                                  (subscribed ? " · display subscriber active" : "")
                              : "ROS session stopped";
  return env->NewStringUTF(
    (result + (last_error.empty() ? "" : "\nLast error: " + last_error)).c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_ros2android_android_NativeRuntime_topics(JNIEnv * env, jobject)
{
  std::lock_guard<std::mutex> lock(gate);

  try {
    require_running();
    auto names = rcl_get_zero_initialized_names_and_types();
    auto allocator = rcl_get_default_allocator();
    check(rcl_get_topic_names_and_types(&node, &allocator, false, &names));
    std::vector<std::string> entries;

    for (size_t i = 0; i < names.names.size; i++) {
      for (size_t j = 0; j < names.types[i].size; j++) {
        entries.push_back(std::string(names.names.data[i]) + "|" + names.types[i].data[j]);
      }
    }

    finish(rcl_names_and_types_fini(&names));
    auto result = env->NewObjectArray(
      static_cast<jsize>(entries.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < entries.size(); i++) {
      auto entry = env->NewStringUTF(entries[i].c_str());
      env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
      env->DeleteLocalRef(entry);
    }

    return result;
  } catch (const std::exception & error) {
    raise(env, error);
    return nullptr;
  }
}
