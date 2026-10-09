"""Explicit supported JMH measurement modes."""
from enum import Enum


class Mode(str, Enum):
    """Explicit supported JMH measurement modes."""
    AVERAGE = "avgt"
    SAMPLE = "sample"
