/* Standalone Android CPython entry point. Executed from the APK's native libs. */
#include <Python.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

static int ros_main(int argc, char ** argv)
{
  PyConfig config;
  PyConfig_InitPythonConfig(&config);
  config.parse_argv = 0;
  /* Console-script argv[0] is ros2, but Python subprocesses must re-enter
       the interpreter, not reinterpret -c/-m as ros2 subcommands. */
  PyStatus status = PyStatus_Ok();
  const char * home = getenv("PYTHONHOME");
  if (home) {
    size_t length = strlen(home) + sizeof("/bin/python3");
    char * executable = malloc(length);
    if (!executable) {
      PyConfig_Clear(&config);
      return 1;
    }

    snprintf(executable, length, "%s/bin/python3", home);
    status = PyConfig_SetBytesString(&config, &config.executable, executable);
    free(executable);
  }

  if (!PyStatus_Exception(status)) {
    status = PyConfig_SetBytesArgv(&config, argc, argv);
  }

  if (!PyStatus_Exception(status)) {
    status = Py_InitializeFromConfig(&config);
  }

  PyConfig_Clear(&config);

  if (PyStatus_Exception(status)) {
    Py_ExitStatusException(status);
  }

  PyObject * module = PyImport_ImportModule("ros2cli.cli");
  PyObject * function = module ? PyObject_GetAttrString(module, "main") : NULL;
  PyObject * result = function ? PyObject_CallNoArgs(function) : NULL;
  int code = 0;

  if (result) {
    if (PyLong_Check(result)) {
      code = (int)PyLong_AsLong(result);
    } else if (result != Py_None) {
      PyObject_Print(result, stderr, Py_PRINT_RAW);
      fputc('\n', stderr);
      code = 1;
    }
  } else if (PyErr_ExceptionMatches(PyExc_SystemExit)) {
    PyObject *type, *value, *trace;
    PyErr_Fetch(&type, &value, &trace);
    PyErr_NormalizeException(&type, &value, &trace);
    PyObject * exit_code = value ? PyObject_GetAttrString(value, "code") : NULL;
    code = exit_code == Py_None                   ? 0
           : exit_code && PyLong_Check(exit_code) ? (int)PyLong_AsLong(exit_code)
                                                  : 1;
  } else {
    PyErr_Print();
    code = 1;
  }

  PyRun_SimpleString("import sys; sys.stdout.flush(); sys.stderr.flush()");
  /* This process is disposable. Android ROS extensions may have C++ globals
       which race CPython finalization after SIGINT. The upstream run command
       has already waited for its child; let the OS release this interpreter's
       resources without unloading extension modules in an unsafe order. */
  fflush(NULL);
  _exit(code);
}

int main(int argc, char ** argv)
{
  const char * name = strrchr(argv[0], '/');
  name = name ? name + 1 : argv[0];
  if (strcmp(name, "ros2") == 0) {
    return ros_main(argc, argv);
  }

  return Py_BytesMain(argc, argv);
}
