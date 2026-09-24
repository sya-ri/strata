#!/usr/bin/env python3
"""Derive Central receipt counts from the selected source publication inventory."""
import argparse
from pathlib import Path
import re


def count_files(coordinates, publication_files):
    """Validate file ownership before counting; old tagged JVM releases retain their five-file contract."""
    owners = [line.strip() for line in coordinates.read_text(encoding="utf-8").splitlines() if line.strip()]
    owners = [":".join(owner.split(":")[:2]) for owner in owners]
    if not owners or len(owners) != len(set(owners)):
        raise ValueError("Maven coordinate inventory is empty or duplicated.")
    if publication_files.is_symlink():
        raise ValueError("Maven publication inventory must be a regular file.")
    if not publication_files.exists():
        return len(owners) * 5
    files = [line.strip() for line in publication_files.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not files or len(files) != len(set(files)):
        raise ValueError("Maven publication file inventory is empty or duplicated.")
    grouped = {}
    for entry in files:
        owner, suffix = entry.rsplit(":", 1)
        if owner not in owners or not re.fullmatch(r"(?:-[A-Za-z0-9_-]+)?\.[A-Za-z0-9]+", suffix) or suffix.endswith(".asc"):
            raise ValueError("Maven publication file owner or suffix differs.")
        grouped.setdefault(owner, set()).add(suffix)
    if set(grouped) != set(owners) or any(not {".pom", ".module"} <= suffixes or len(suffixes) <= 2 for suffixes in grouped.values()):
        raise ValueError("Maven publication file inventory is incomplete.")
    return len(files)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("coordinates", type=Path)
    parser.add_argument("--files", type=Path)
    args = parser.parse_args()
    print(count_files(args.coordinates, args.files or args.coordinates.with_name("maven-files.txt")))
