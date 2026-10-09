"""Explicit scheduling order, independent of JMH measurement modes."""
from enum import Enum


class Scheduling(str, Enum):
    """Explicit scheduling order, independent of JMH measurement modes."""
    SERIAL = "serial"
    PARALLEL = "parallel"
