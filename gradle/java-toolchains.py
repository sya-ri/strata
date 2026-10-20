#!/usr/bin/env python3
"""Read ordinary or frozen paired Java toolchains from the Gradle version catalog."""
import argparse
from pathlib import Path
import sys
import tomllib


def main():
    """Print sorted Java majors or the two named frozen Actions outputs."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog", nargs="?", type=Path, default=Path("gradle/libs.versions.toml"))
    parser.add_argument("--paired", action="store_true")
    args = parser.parse_args()
    sys.stdout.reconfigure(newline="\n")
    with args.catalog.open("rb") as source:
        versions = tomllib.load(source)["versions"]
    if args.paired:
        for output, key in (("toolchain", "performance-toolchain"), ("launcher", "performance-launcher")):
            value = versions[key]
            if not isinstance(value, str) or not value or any(character.isspace() for character in value):
                parser.error(f"Malformed frozen Java profile: {key}")
            print(f"{output}={value}")
    else:
        values = [value for key, value in versions.items() if key.startswith("java-")]
        if not values or any(not isinstance(value, str) or not value.isascii() or not value.isdecimal() for value in values):
            parser.error("The version catalog must declare numeric Java toolchain majors")
        print("\n".join(sorted(set(values), key=int)))


if __name__ == "__main__":
    main()
