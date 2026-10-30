"""Android entry point; the ROS package is also runnable with normal Linux ROS tools."""
from ball_follower.nodes import VisionNode, FollowerNode


def create_nodes(**kwargs):
    nodes = []
    try:
        nodes.append(VisionNode(**kwargs))
        nodes.append(FollowerNode(**kwargs))
        return nodes
    except BaseException:
        for node in nodes:
            node.destroy_node()
        raise
