"""
Manager Agent - Planning and reasoning workflow.

Two variants available:
- ManagerAgent: Stateful, maintains chat history
- StatelessManagerAgent: Stateless, rebuilds context each turn
"""

from mobilerun.agent.droid.events import ManagerInputEvent, ManagerPlanEvent
from mobilerun.agent.manager.events import (
    ManagerContextEvent,
    ManagerPlanDetailsEvent,
    ManagerResponseEvent,
)
from mobilerun.agent.manager.manager_agent import ManagerAgent
from mobilerun.agent.manager.prompts import parse_manager_response
from mobilerun.agent.manager.stateless_manager_agent import StatelessManagerAgent

__all__ = [
    "ManagerAgent",
    "StatelessManagerAgent",
    "ManagerInputEvent",
    "ManagerPlanEvent",
    "ManagerContextEvent",
    "ManagerResponseEvent",
    "ManagerPlanDetailsEvent",
    "parse_manager_response",
]
