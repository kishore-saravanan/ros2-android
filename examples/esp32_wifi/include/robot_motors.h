#pragma once
#include "drive_guard.hpp"
#include <geometry_msgs/msg/twist.h>

#ifndef ROS2_ANDROID_TRACK_M
#define ROS2_ANDROID_TRACK_M 0.16f
#endif
#ifndef ROS2_ANDROID_LEFT_SIGN
#define ROS2_ANDROID_LEFT_SIGN -1  // Reference robot: owner observed reversed left wheel.
#endif
#ifndef ROS2_ANDROID_RIGHT_SIGN
#define ROS2_ANDROID_RIGHT_SIGN 1
#endif
// Inputs are PWM-controlled with ENA/ENB jumpers fitted. No enable-pin wiring assumed.
static constexpr int motor_pins[] = {25, 26, 27, 14};
static portMUX_TYPE drive_lock = portMUX_INITIALIZER_UNLOCKED;
#ifndef ROS2_ANDROID_FULL_PWM
#define ROS2_ANDROID_FULL_PWM 0
#endif
static robot::DriveGuard drive{bool(ROS2_ANDROID_FULL_PWM)};
static rcl_subscription_t drive_sub, pwm_sub, angular_sub;
static rcl_publisher_t pwm_pub, angular_pub;
static std_msgs__msg__Int32 pwm_command, pwm_state;
static std_msgs__msg__Float64 angular_command, angular_state;
static geometry_msgs__msg__Twist velocity;

static void robot_connection(bool up)
{
  portENTER_CRITICAL(&drive_lock);
  drive.connection(up);
  portEXIT_CRITICAL(&drive_lock);
}

static void velocity_received(const void * message)
{
  const auto * v = static_cast<const geometry_msgs__msg__Twist *>(message);
  portENTER_CRITICAL(&drive_lock);
  drive.command(float(v->linear.x), float(v->angular.z), millis(), ROS2_ANDROID_TRACK_M);
  portEXIT_CRITICAL(&drive_lock);
}

static void pwm_received(const void * message)
{
  const auto value = static_cast<const std_msgs__msg__Int32 *>(message)->data;
  portENTER_CRITICAL(&drive_lock);
  drive.set_pwm_limit(value, millis());
  portEXIT_CRITICAL(&drive_lock);
}

static void angular_received(const void * message)
{
  const auto value = static_cast<const std_msgs__msg__Float64 *>(message)->data;
  portENTER_CRITICAL(&drive_lock);
  drive.set_angular_limit(float(value), millis());
  portEXIT_CRITICAL(&drive_lock);
}

static void write_motor(int channel, float fraction, int sign, int limit)
{
  const int duty = robot::DriveGuard::duty(fraction * sign, limit);

  // Zero the inactive direction BEFORE applying the active direction.
  if (duty > 0) {
    ledcWrite(channel + 1, 0);
    ledcWrite(channel, duty);
  } else {
    ledcWrite(channel, 0);
    ledcWrite(channel + 1, -duty);
  }
}

static void motor_watchdog(void *)
{
  TickType_t tick = xTaskGetTickCount();

  for (;;) {
    portENTER_CRITICAL(&drive_lock);
    const auto value = drive.output(millis());
    const int limit = drive.pwm_limit();
    portEXIT_CRITICAL(&drive_lock);
    write_motor(0, value.left, ROS2_ANDROID_LEFT_SIGN, limit);
    write_motor(2, value.right, ROS2_ANDROID_RIGHT_SIGN, limit);
    vTaskDelayUntil(&tick, pdMS_TO_TICKS(20));
  }
}

static void setup_robot_motors()
{
  for (int i = 0; i < 4; ++i) {
    digitalWrite(motor_pins[i], LOW);
    pinMode(motor_pins[i], OUTPUT);
    ledcSetup(i, 2000, 8);
    ledcAttachPin(motor_pins[i], i);
    ledcWrite(i, 0);
  }

  // Separate from rclc executor/Agent ping: a stalled ROS loop must not retain PWM.
  if (xTaskCreate(motor_watchdog, "motor-deadline", 3072, nullptr, 4, nullptr) != pdPASS) {
    abort();
  }
}

static bool create_robot_entities()
{
  auto velocity_qos = rmw_qos_profile_sensor_data;
  velocity_qos.depth = 1;  // Keep the latest command, not a queue of old motion requests.
  return rclc_subscription_init_default(
           &angular_sub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Float64),
           "/robot/angular_limit") == RCL_RET_OK &&
         rclc_publisher_init_best_effort(
           &angular_pub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Float64),
           "/robot/angular_limit/current") == RCL_RET_OK &&
         rclc_subscription_init_default(
           &pwm_sub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Int32),
           "/robot/pwm_limit") == RCL_RET_OK &&
         rclc_publisher_init_best_effort(
           &pwm_pub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(std_msgs, msg, Int32),
           "/robot/pwm_limit/current") == RCL_RET_OK &&
         rclc_subscription_init(
           &drive_sub, &node, ROSIDL_GET_MSG_TYPE_SUPPORT(geometry_msgs, msg, Twist), "/cmd_vel",
           &velocity_qos) == RCL_RET_OK;
}

static bool add_robot_handles()
{
  return rclc_executor_add_subscription(
           &executor, &angular_sub, &angular_command, angular_received, ON_NEW_DATA) ==
           RCL_RET_OK &&
         rclc_executor_add_subscription(
           &executor, &pwm_sub, &pwm_command, pwm_received, ON_NEW_DATA) == RCL_RET_OK &&
         rclc_executor_add_subscription(
           &executor, &drive_sub, &velocity, velocity_received, ON_NEW_DATA) == RCL_RET_OK;
}

static void publish_robot_state()
{
  portENTER_CRITICAL(&drive_lock);
  pwm_state.data = drive.pwm_limit();
  angular_state.data = drive.angular_limit();
  portEXIT_CRITICAL(&drive_lock);
  const auto angular_result = rcl_publish(&angular_pub, &angular_state, nullptr);
  (void)angular_result;
  const auto pwm_result = rcl_publish(&pwm_pub, &pwm_state, nullptr);
  (void)pwm_result;
}
