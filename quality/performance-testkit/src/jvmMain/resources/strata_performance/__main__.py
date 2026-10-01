"""Standard-library command line entry point shipped in the measured testkit JAR."""

import argparse
import json
from pathlib import Path

from . import compare_jmh, summarize_jmh


def main():
    parser = argparse.ArgumentParser(description="Validate and summarize collector-bound JMH evidence")
    commands = parser.add_subparsers(dest="command", required=True)
    summary = commands.add_parser("jmh", help="Validate and summarize independent JMH repetitions")
    summary.add_argument("directories", nargs="+", type=Path)
    comparison = commands.add_parser("compare-jmh", help="Compare revalidated baseline and candidate repetitions")
    comparison.add_argument("--baseline", nargs="+", required=True, type=Path)
    comparison.add_argument("--candidate", nargs="+", required=True, type=Path)
    for command in (summary, comparison):
        command.add_argument("--collector-jar", required=True, type=Path)
        command.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    report = (summarize_jmh(arguments.directories, arguments.collector_jar) if arguments.command == "jmh"
              else compare_jmh(arguments.baseline, arguments.candidate, arguments.collector_jar))
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with arguments.output.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")


if __name__ == "__main__":
    main()
