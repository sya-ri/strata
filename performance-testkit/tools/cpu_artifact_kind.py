"""Actual source preservation formats used by compiled admission."""
from enum import Enum


class ArtifactKind(str, Enum):
    """Actual source preservation formats used by compiled admission."""
    FILE = "file"
    TREE = "tree"
