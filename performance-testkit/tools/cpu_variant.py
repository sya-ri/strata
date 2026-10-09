"""Immutable runtime sides of a complete paired comparison."""
from enum import Enum


class Variant(str, Enum):
    """Immutable runtime sides of a complete paired comparison."""
    BASELINE = "baseline"
    CANDIDATE = "candidate"
