"""Outer execution phases, separate from application timing and JMH modes."""
from enum import Enum


class Phase(str, Enum):
    """Measured preparation and transport operations supplied by the executor owner."""
    PREPARATION = "preparation"
    TRANSFER = "transfer"
