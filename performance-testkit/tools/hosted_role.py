"""Decode the five fixed workflow roles at the hosted adapter boundary."""
from enum import Enum


class Role(str, Enum):
    """Persistent job bindings; an executor is never replaced inside this campaign."""
    COORDINATOR = "coordinator"
    WORKER_1 = "worker-1"
    WORKER_2 = "worker-2"
    WORKER_3 = "worker-3"
    WORKER_4 = "worker-4"

    @property
    def shard(self):
        """Return the original fixed partition index for a worker."""
        if self is Role.COORDINATOR:
            raise ValueError("The coordinator cannot collect a worker shard")
        return "shard-" + str(list(Role).index(self))

    @property
    def executor(self):
        """Return the executor ID used by the existing frozen plan."""
        return "executor-" + str(list(Role).index(self))
