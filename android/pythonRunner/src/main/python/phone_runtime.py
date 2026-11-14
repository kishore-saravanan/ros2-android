"""Embedded rclpy lifecycle. User code lives in the project's phone_node module."""

import json
import sys
import threading
import traceback

_context = _executor = _node = _host = _thread = None
_nodes = []
_names = []
_running = False
_heartbeat = 0
_error = ''
_name = ''
_stop = threading.Event()


def start(domain, namespace, arguments):
    global _context, _executor, _node, _nodes, _names, _host
    global _thread, _running, _heartbeat, _error, _name

    if _context is not None:
        raise RuntimeError('Stop the current Python project before starting again')

    import rclpy
    from rclpy.context import Context
    from rclpy.executors import MultiThreadedExecutor
    from rclpy.node import Node
    from rclpy.signals import SignalHandlerOptions
    import phone_node

    _error = ''
    _heartbeat = 0
    _stop.clear()

    try:
        _context = Context()
        rclpy.init(
            args=[],
            context=_context,
            domain_id=domain,
            signal_handler_options=SignalHandlerOptions.NO,
        )
        cli_args = list(arguments)
        kwargs = dict(
            context=_context,
            namespace=namespace,
            cli_args=['--ros-args', *cli_args] if cli_args else [],
        )

        if hasattr(phone_node, 'create_nodes'):
            _nodes = list(phone_node.create_nodes(**kwargs))
        else:
            _nodes = [phone_node.PhoneNode(**kwargs)]

        if not 1 <= len(_nodes) <= 16 or any(not isinstance(n, Node) for n in _nodes):
            raise ValueError('Python project must return 1..16 ROS nodes')

        _names = [n.get_fully_qualified_name() for n in _nodes]
        _node = _nodes[0]
        _name = _node.get_fully_qualified_name()
        _host = Node(
            'android_python_host',
            namespace='/ros2_android',
            context=_context,
            enable_rosout=False,
            start_parameter_services=False,
        )
        _host.create_timer(1.0, heartbeat)
        _executor = MultiThreadedExecutor(num_threads=3, context=_context)

        for node in _nodes:
            _executor.add_node(node)

        _executor.add_node(_host)
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
            _executor.spin_once(timeout_sec=0.1)
    except BaseException:
        _error = traceback.format_exc()
        print(_error, flush=True)
    finally:
        _running = False


def stop():
    global _context, _executor, _node, _nodes, _names, _host, _thread, _running, _name
    _stop.set()

    if _executor is not None:
        _executor.wake()

    if _thread is not None:
        _thread.join(timeout=8)

        if _thread.is_alive():
            raise RuntimeError('Python callback did not stop; force-stop this runner')

    _running = False

    if _executor is not None:
        if not _executor.shutdown(timeout_sec=8):
            raise RuntimeError('Python workers did not stop; force-stop this runner')

    for node in reversed(_nodes):
        destroy = getattr(node, 'destroy_node', None)
        if callable(destroy):
            destroy()

    _nodes = []
    _names = []

    if _host is not None:
        _host.destroy_node()

    if _context is not None:
        _context.try_shutdown()

    _context = _executor = _node = _host = _thread = None
    _name = ''


def status():
    return json.dumps(
        {
            'running': _running,
            'heartbeat': _heartbeat,
            'node': _name,
            'nodes': list(_names),
            'error': _error,
            'runtime': 'python',
            'python_version': sys.version.split()[0],
        }
    )
