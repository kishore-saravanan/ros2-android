"""Validation for the bounded Android C++ launch schema (no executable Python)."""
import json
import math
import re

IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
NAMESPACE = re.compile(r'/(?:[A-Za-z_][A-Za-z0-9_]*)(?:/[A-Za-z_][A-Za-z0-9_]*)*')
TOPIC = re.compile(r'(?:/|~/)?[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*')


def validate_launch(value, namespace='/demo'):
    def fields(obj, allowed):
        if not isinstance(obj, dict) or set(obj) - allowed:
            raise ValueError('Unsupported launch fields; supported: ' + ', '.join(sorted(allowed)))

    fields(value, {'schemaVersion', 'nodes'})
    if type(value.get('schemaVersion')) is not int or value['schemaVersion'] != 1:
        raise ValueError('Unsupported launch schema')
    nodes = value.get('nodes')
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= 16:
        raise ValueError('Launch requires 1–16 nodes')
    names = set()
    for node in nodes:
        fields(node, {'component', 'name', 'namespace', 'parameters', 'remappings'})
        for key in ('component', 'name'):
            if not isinstance(node.get(key), str) or not IDENTIFIER.fullmatch(node[key]):
                raise ValueError('Invalid component or node name')
        ns = node.get('namespace', namespace)
        if not isinstance(ns, str) or not (ns == '/' or NAMESPACE.fullmatch(ns)):
            raise ValueError('Invalid namespace')
        name = ns.rstrip('/') + '/' + node['name']
        if name in names:
            raise ValueError('Duplicate launched node name: ' + name)
        names.add(name)
        params, remaps = node.get('parameters', {}), node.get('remappings', {})
        if not isinstance(params, dict) or not isinstance(remaps, dict):
            raise ValueError('Parameters and remappings must be objects')
        arguments = ['-r', '__node:=' + node['name'], '-r', '__ns:=' + ns]
        for key, item in params.items():
            if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*', key):
                raise ValueError('Invalid parameter name')
            if type(item) not in (str, bool, int, float) or (isinstance(item, float) and not math.isfinite(item)):
                raise ValueError('Only finite scalar parameters are supported')
            if isinstance(item, int) and not isinstance(item, bool) and not -(2**63) <= item < 2**63:
                raise ValueError('Integer parameter exceeds int64')
            arguments += ['-p', key + ':=' + json.dumps(item, ensure_ascii=False)]
        for source, target in remaps.items():
            if source in ('__node', '__name', '__ns') or not TOPIC.fullmatch(source) or not isinstance(target, str) or not TOPIC.fullmatch(target):
                raise ValueError('Only explicit topic/service remappings are supported')
            arguments += ['-r', source + ':=' + target]
        if len(arguments) > 128 or any(len(a) > 4096 or '\0' in a for a in arguments):
            raise ValueError('Node arguments too large or invalid')
    if len(json.dumps(value, ensure_ascii=False)) > 65536:
        raise ValueError('Launch configuration exceeds 64 KiB')
    return value
