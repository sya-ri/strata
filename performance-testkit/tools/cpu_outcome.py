"""Terminal whole-attempt states; successful fragments are never adopted."""
from enum import Enum


class Outcome(str, Enum):
    """Terminal whole-attempt states; successful fragments are never adopted."""
    PASSED = "passed"
    FAILED = "failed"
