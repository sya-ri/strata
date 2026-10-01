"""Standard-library command line entry point shipped in the measured testkit JAR."""

import argparse
import json
from pathlib import Path

from . import summarize_jmh


def main():
    parser = argparse.ArgumentParser(description="Validate and summarize collector-bound JMH evidence")
    parser.add_argument("command", choices=("jmh",))
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--collector-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    report = summarize_jmh(arguments.directories, arguments.collector_jar)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")


if __name__ == "__main__":
    main()
