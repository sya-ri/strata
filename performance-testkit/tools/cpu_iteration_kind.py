"""JMH's external iteration phases decoded at the provenance boundary."""
from enum import Enum


class IterationKind(Enum):
    """Separate warm-up proof from the actual measured iteration inventory."""
    WARMUP = "WARMUP"
    MEASUREMENT = "MEASUREMENT"
