#!/usr/bin/env python3
"""Validate a signed tag without depending on another release or Pages deployment."""
import json
import os
import re
import subprocess


def main():
    """Freeze the source and preparation producer for every publication job."""
    inputs = json.loads(os.environ["RELEASE_INPUTS"])
    tag, commit = inputs["tag"], inputs["source_commit"]
    if os.environ["GITHUB_REF"] != "refs/heads/master" or inputs["confirmation"] != inputs["operation"] + " " + tag:
        raise ValueError("Release source or confirmation differs.")
    if not re.fullmatch(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", tag) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected a stable tag and full product commit.")
    subprocess.run(["bash", "release/verify-release-tag.sh", tag, commit], check=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, os.environ["GITHUB_SHA"]], check=True)
    source = subprocess.check_output(["git", "show", commit + ":build.gradle.kts"], text=True)
    if re.search(r'^version = "' + re.escape(tag[1:]) + '"$', source, re.MULTILINE) is None:
        raise ValueError("Root project version differs from the release tag.")
    subprocess.run(["bash", "release/verify-github-tag-ruleset.sh", "release/github-release-tag-ruleset.json",
                    "release/github-release-tag-ruleset-receipt.json"], check=True)
    run = inputs.get("prepared_run_id") or (os.environ["GITHUB_RUN_ID"] if inputs["operation"] == "release" else "")
    if not re.fullmatch(r"[1-9][0-9]*", run):
        raise ValueError("Verification requires prepared_run_id; artifacts will not be rebuilt.")
    destinations = [name for name, key in [("github", "github_release"), ("modrinth", "modrinth"),
                                          ("curseforge", "curseforge"), ("hangar", "hangar")] if inputs[key]]
    if not destinations and not inputs["maven_central"]:
        raise ValueError("Select at least one publication destination.")
    tag_object = subprocess.check_output(["git", "rev-parse", "refs/tags/" + tag], text=True).strip()
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"tag_object={tag_object}\nprepared_run_id={run}\ndestinations={json.dumps(destinations)}\n")
        output.write("has_destinations=" + str(bool(destinations)).lower() + "\n")


if __name__ == "__main__":
    main()
