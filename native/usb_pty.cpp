#include <jni.h>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <fcntl.h>
#include <unistd.h>
#include <termios.h>
#include <poll.h>

// Only app-owned PTYs are exposed: no device nodes, shell commands or root.
static void fail(JNIEnv * env, const char * message)
{
  env->ThrowNew(env->FindClass("java/io/IOException"), message);
}

extern "C" JNIEXPORT jint JNICALL Java_dev_ros2android_android_UsbPty_open(JNIEnv * env, jobject)
{
  int fd = posix_openpt(O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
  if (fd < 0) {
    fail(env, "Cannot allocate USB Agent PTY");
    return -1;
  }

  if (grantpt(fd) || unlockpt(fd)) {
    close(fd);
    fail(env, "Cannot unlock USB Agent PTY");
    return -1;
  }

  return fd;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_ros2android_android_UsbPty_slave(JNIEnv * env, jobject, jint fd)
{
  char path[128];
  if (ptsname_r(fd, path, sizeof(path))) {
    fail(env, "Cannot resolve USB Agent PTY");
    return nullptr;
  }

  // Keep raw mode even before the Agent opens the slave.
  int slave = ::open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (slave < 0) {
    fail(env, "Cannot open USB Agent PTY slave");
    return nullptr;
  }

  termios settings{};
  int result = tcgetattr(slave, &settings);
  if (!result) {
    cfmakeraw(&settings);
    result = tcsetattr(slave, TCSANOW, &settings);
  }

  close(slave);

  if (result) {
    fail(env, "Cannot configure USB Agent PTY");
    return nullptr;
  }

  return env->NewStringUTF(path);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_ros2android_android_UsbPty_read(JNIEnv * env, jobject, jint fd, jbyteArray buffer)
{
  if (env->GetArrayLength(buffer) < 4096) {
    fail(env, "PTY read buffer too small");
    return 0;
  }

  pollfd ready{fd, POLLIN, 0};
  if (poll(&ready, 1, 20) <= 0) {
    return 0;
  }

  jbyte bytes[4096];
  ssize_t size = ::read(fd, bytes, sizeof(bytes));
  if (size < 0 && (errno == EAGAIN || errno == EINTR || errno == EIO)) {
    usleep(20000);
    return 0;
  }

  if (size < 0) {
    fail(env, "USB Agent PTY read failed");
    return 0;
  }

  env->SetByteArrayRegion(buffer, 0, static_cast<jsize>(size), bytes);
  return static_cast<jint>(size);
}

extern "C" JNIEXPORT void JNICALL Java_dev_ros2android_android_UsbPty_write(
  JNIEnv * env, jobject, jint fd, jbyteArray buffer, jint size)
{
  if (size < 0 || size > env->GetArrayLength(buffer) || size > 4096) {
    fail(env, "Invalid USB frame size");
    return;
  }

  jbyte bytes[4096];
  env->GetByteArrayRegion(buffer, 0, size, bytes);
  int offset = 0;
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);

  while (offset < size) {
    if (std::chrono::steady_clock::now() > deadline) {
      fail(env, "USB Agent PTY write timed out");
      return;
    }

    ssize_t count = ::write(fd, bytes + offset, size - offset);
    if (count < 0 && errno == EINTR) {
      continue;
    }

    if (count < 0 && errno == EAGAIN) {
      pollfd ready{fd, POLLOUT, 0};
      if (poll(&ready, 1, 1000) > 0) {
        continue;
      }
    }

    if (count <= 0) {
      fail(env, "USB Agent PTY write failed");
      return;
    }

    offset += static_cast<int>(count);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ros2android_android_UsbPty_close(JNIEnv *, jobject, jint fd)
{
  ::close(fd);
}
