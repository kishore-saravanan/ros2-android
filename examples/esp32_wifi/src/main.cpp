#include <Arduino.h>
#ifndef ROS2_ANDROID_USB
#include <WiFi.h>
#endif
#include <micro_ros_platformio.h>
#include <rcl/rcl.h>
#include <rclc/rclc.h>
#include <rclc/executor.h>
#include <rmw_microros/rmw_microros.h>
#include <std_msgs/msg/int32.h>
#include <std_msgs/msg/bool.h>
#include <std_msgs/msg/float64.h>
#include <std_msgs/msg/string.h>
#ifndef ROS2_ANDROID_USB
#include "wifi_config.h"  // Generated locally; never commit credentials.
#define LOG(...) Serial.printf(__VA_ARGS__)
#else
#ifndef ROS_DOMAIN
#define ROS_DOMAIN 0
#endif
// UART carries framed XRCE bytes only. No debug text on this transport.
#define LOG(...) ((void)0)
#endif

static rcl_allocator_t allocator;
static rclc_support_t support;
static rcl_node_t node;
static rcl_publisher_t heartbeat_pub, display_pub, led_state_pub;
static rcl_subscription_t battery_sub, led_sub;
static rcl_timer_t timer;
static rclc_executor_t executor;
static std_msgs__msg__Int32 heartbeat;
static std_msgs__msg__Float64 battery;
static std_msgs__msg__Bool led_command, led_state;
#ifndef ROS2_ANDROID_LED_PIN
#define ROS2_ANDROID_LED_PIN 2
#endif
#ifndef ROS2_ANDROID_LED_ACTIVE_HIGH
#define ROS2_ANDROID_LED_ACTIVE_HIGH 1
#endif

static void set_led(bool on) {
  digitalWrite(ROS2_ANDROID_LED_PIN, (on == bool(ROS2_ANDROID_LED_ACTIVE_HIGH)) ? HIGH : LOW);
  led_state.data = on;
  const rcl_ret_t result = rcl_publish(&led_state_pub, &led_state, nullptr); (void)result;
}
static void led_received(const void *message) {
  const bool on = static_cast<const std_msgs__msg__Bool *>(message)->data;
  set_led(on);
}
static std_msgs__msg__String display;
static char display_buffer[160];
static bool ready = false;
static unsigned long last_ping = 0;

void battery_received(const void *message) {
  const auto *value = static_cast<const std_msgs__msg__Float64 *>(message);
  int count = snprintf(display_buffer, sizeof(display_buffer),
      "ESP32 received phone battery: %.1f%% (heartbeat %ld)", value->data, (long)heartbeat.data);
  display.data.size = count < 0 ? 0 : min((size_t)count, sizeof(display_buffer) - 1);
  const rcl_ret_t result = rcl_publish(&display_pub, &display, nullptr); (void)result;
  LOG("phone battery %.1f; reply %s\n", value->data, result == RCL_RET_OK ? "sent" : "failed");
}
void heartbeat_tick(rcl_timer_t *, int64_t) {
  ++heartbeat.data;
  const rcl_ret_t result = rcl_publish(&heartbeat_pub, &heartbeat, nullptr);
  (void)result;
}
bool create_entities() {
  allocator = rcl_get_default_allocator();
  rcl_init_options_t options = rcl_get_zero_initialized_init_options();
  if (rcl_init_options_init(&options, allocator) != RCL_RET_OK) return false;
  if (rcl_init_options_set_domain_id(&options, ROS_DOMAIN) != RCL_RET_OK) return false;
  const rcl_ret_t initialized = rclc_support_init_with_options(&support, 0, nullptr, &options, &allocator);
  const rcl_ret_t options_done = rcl_init_options_fini(&options); (void)options_done;
  if (initialized != RCL_RET_OK) return false;
#ifdef ROS2_ANDROID_USB
  const char *node_name = "usb_bridge";
#else
  const char *node_name = "wifi_bridge";
#endif
  if (rclc_node_init_default(&node, node_name, "/esp32", &support) != RCL_RET_OK) return false;
  if (rclc_publisher_init_best_effort(&heartbeat_pub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Int32), "/esp32/heartbeat") != RCL_RET_OK) return false;
  if (rclc_publisher_init_default(&display_pub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, String), "/phone/display/text") != RCL_RET_OK) return false;
  if (rclc_subscription_init_best_effort(&battery_sub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Float64), "/phone/battery_percent") != RCL_RET_OK) return false;
  if (rclc_publisher_init_default(&led_state_pub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Bool), "/esp32/led_state") != RCL_RET_OK) return false;
  if (rclc_subscription_init_default(&led_sub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Bool), "/esp32/led") != RCL_RET_OK) return false;
  if (rclc_timer_init_default(&timer, &support, RCL_MS_TO_NS(1000), heartbeat_tick) != RCL_RET_OK) return false;
  if (rclc_executor_init(&executor, &support.context, 3, &allocator) != RCL_RET_OK) return false;
  if (rclc_executor_add_timer(&executor, &timer) != RCL_RET_OK) return false;
  if (rclc_executor_add_subscription(&executor, &battery_sub, &battery, battery_received, ON_NEW_DATA) != RCL_RET_OK) return false;
  return rclc_executor_add_subscription(&executor, &led_sub, &led_command, led_received, ON_NEW_DATA) == RCL_RET_OK;
}
void setup() {
  pinMode(ROS2_ANDROID_LED_PIN, OUTPUT);
  digitalWrite(ROS2_ANDROID_LED_PIN, ROS2_ANDROID_LED_ACTIVE_HIGH ? LOW : HIGH);
  Serial.begin(115200);
  display.data.data = display_buffer; display.data.capacity = sizeof(display_buffer); display.data.size = 0;
#ifdef ROS2_ANDROID_USB
  set_microros_serial_transports(Serial);
#else
  WiFi.mode(WIFI_STA);
  IPAddress agent(AGENT_IP_0, AGENT_IP_1, AGENT_IP_2, AGENT_IP_3);
  set_microros_wifi_transports(WIFI_SSID, WIFI_PASSWORD, agent, AGENT_PORT);
  LOG("Wi-Fi connected: %s; agent %s:%u\n", WiFi.localIP().toString().c_str(), agent.toString().c_str(), AGENT_PORT);
#endif
}
void loop() {
  if (!ready) {
    if (rmw_uros_ping_agent(100, 1) == RMW_RET_OK) {
      ready = create_entities();
      LOG("%s\n", ready ? "micro-ROS ready" : "micro-ROS creation failed; restarting");
      if (!ready) { delay(1000); ESP.restart(); }
    } else delay(200);
    return;
  }
  const rcl_ret_t result = rclc_executor_spin_some(&executor, RCL_MS_TO_NS(20)); (void)result;
  if (millis() - last_ping > 3000) {
    last_ping = millis();
    bool disconnected = rmw_uros_ping_agent(200, 2) != RMW_RET_OK;
#ifndef ROS2_ANDROID_USB
    disconnected = disconnected || WiFi.status() != WL_CONNECTED;
#endif
    if (disconnected) {
      LOG("Connection lost; restarting to rejoin\n"); delay(100); ESP.restart();
    }
  }
}
