"""Embedded rclpy lifecycle. User code lives in the project's phone_node module."""
import json
import sys
import threading
import traceback

_context = _executor = _node = _host = _thread = None
_running = False
_heartbeat = 0
_error = ''
_name = ''
_stop = threading.Event()


def start(domain, namespace, arguments):
    global _context, _executor, _node, _host, _thread, _running, _heartbeat, _error, _name
    if _context is not None:
        raise RuntimeError('Stop the current Python project before starting again')
    import rclpy
    from rclpy.context import Context
    from rclpy.executors import SingleThreadedExecutor
    from rclpy.node import Node
    from rclpy.signals import SignalHandlerOptions
    from phone_node import PhoneNode
    _error = ''; _heartbeat = 0; _stop.clear()
    try:
        _context = Context()
        rclpy.init(args=[], context=_context, domain_id=domain,
                   signal_handler_options=SignalHandlerOptions.NO)
        cli_args = list(arguments)
        _node = PhoneNode(context=_context, namespace=namespace,
                          cli_args=['--ros-args', *cli_args] if cli_args else [])
        _name = _node.get_fully_qualified_name()
        _host = Node('android_python_host', namespace='/ros2_android', context=_context,
                     enable_rosout=False, start_parameter_services=False)
        _host.create_timer(1.0, heartbeat)
        _executor = SingleThreadedExecutor(context=_context)
        _executor.add_node(_node); _executor.add_node(_host)
        _running = True
        _thread = threading.Thread(target=spin, name='ros2-android-python', daemon=True)
        _thread.start()
    except BaseException:
        _error = traceback.format_exc()
        stop()
        raise


def heartbeat():
    global _heartbeat
    _heartbeat += 1


def spin():
    global _running, _error
    try:
        while not _stop.is_set() and _context.ok():
            _executor.spin_once(timeout_sec=.1)
    except BaseException:
        _error = traceback.format_exc()
        print(_error, flush=True)
    finally:
        _running = False


def stop():
    global _context, _executor, _node, _host, _thread, _running, _name
    _stop.set()
    if _executor is not None:
        _executor.wake()
    if _thread is not None:
        _thread.join(timeout=8)
        if _thread.is_alive():
            raise RuntimeError('Python callback did not stop; force-stop this runner')
    _running = False
    if _executor is not None:
        _executor.shutdown(timeout_sec=1)
    if _node is not None:
        _node.destroy_node()
    if _host is not None:
        _host.destroy_node()
    if _context is not None:
        _context.try_shutdown()
    _context = _executor = _node = _host = _thread = None
    _name = ''


def status():
    return json.dumps({'running': _running, 'heartbeat': _heartbeat, 'node': _name,
                       'nodes': [_name] if _name else [], 'error': _error, 'runtime': 'python', 'python_version': sys.version.split()[0]})
