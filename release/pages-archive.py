#!/usr/bin/env python3
"""Import rendered documentation and keep release snapshots independent of future builds."""
import argparse
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile


class VersionMenu(HTMLParser):
    """Read generated navigation as HTML rather than matching renderer formatting."""
    def __init__(self):
        super().__init__()
        self.versions = []
        self.targets = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "a" and attrs.get("role") == "option":
            self.versions.append(attrs.get("title"))
            self.targets.append(attrs["href"])
        if tag == "link" and "multimodule.css" in attrs.get("href", ""):
            self.targets.append(attrs["href"])


def check_navigation(site):
    """Verify version order and concrete menu/stylesheet targets after one generation."""
    older = site / "older"
    directories = sorted((p for p in older.iterdir() if p.is_dir()),
                         key=lambda p: tuple(map(int, p.name.split("."))), reverse=True) if older.is_dir() else []
    version = json.loads((site / "version.json").read_text())["version"]
    expected = [version] + [p.name for p in directories] if directories else []
    for directory in [site] + directories:
        menu = VersionMenu()
        menu.feed((directory / "index.html").read_text(encoding="utf-8"))
        if menu.versions != expected:
            raise ValueError(f"Version navigation differs in {directory}: {menu.versions}")
        for target in menu.targets:
            path = (directory / target.replace("\\", "/")).resolve()
            if not path.is_relative_to(site.resolve()) or not path.is_file():
                raise ValueError(f"Version navigation target is missing: {directory} -> {target}")


def version_metadata(directory, version):
    """Restore Dokka's version template marker in legacy HTML without rebuilding its source."""
    (directory / "version.json").write_text(json.dumps({"version": version}) + "\n")
    marker = re.compile(r'(<div\b[^>]*\bid="library-version"[^>]*>)(.*?)(</div>)', re.DOTALL)
    for path in directory.rglob("*.html"):
        content = path.read_text(encoding="utf-8")
        if "ReplaceVersionsCommand" in content or not marker.search(content):
            continue
        relative = path.relative_to(directory)
        command = escape(json.dumps({"@class": "org.jetbrains.dokka.base.templating.ReplaceVersionsCommand",
                                     "location": relative.as_posix()}), quote=True)
        content = marker.sub(lambda match: match[1] + f'<dokka-template-command data="{command}">' + match[2]
                             + "</dokka-template-command>" + match[3], content)
        # Versioning's standard stylesheet is shared with the development root.
        stylesheet = "../" * (len(relative.parts) + 1) + "styles/multimodule.css"
        content = content.replace("</head>", f'<link href="{stylesheet}" rel="stylesheet" type="text/css">\n</head>', 1)
        path.write_text(content, encoding="utf-8", newline="\n")


def snapshot(source, destination, version):
    """Copy one rendered release, without nesting its older releases inside it."""
    receipt = json.loads((source / "source-receipt.json").read_text())
    if receipt["revision"] != "v" + version or not re.fullmatch(r"[0-9a-f]{40}", receipt["commit"]):
        raise ValueError("Documentation source does not match its release version.")
    if destination.exists():
        old = json.loads((destination / "source-receipt.json").read_text())
        if old != receipt:
            raise ValueError("An existing documentation version belongs to another source.")
        return
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns("older", "releases"))
    version_metadata(destination, version)


def bootstrap(destination):
    """Import a successful legacy Pages artifact once; never rebuild historical tags."""
    import os
    repo = os.environ["GITHUB_REPOSITORY"]
    runs = json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/actions/workflows/pages.yml/runs?status=success&branch=master&per_page=20"]))["workflow_runs"]
    for run in runs:
        artifacts = json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100"]))["artifacts"]
        candidates = [a for a in artifacts if a["name"].startswith("github-pages") and not a["expired"]]
        if not candidates:
            continue
        if len(candidates) != 1:
            raise ValueError("Pages migration artifact is ambiguous.")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["gh", "run", "download", str(run["id"]), "--name", candidates[0]["name"], "--dir", str(root)], check=True)
            with tarfile.open(root / "artifact.tar") as archive:
                archive.extractall(root / "site", filter="data")
            old = root / "site/releases"
            if not old.is_dir():
                old = root / "site/older"
            for source in sorted(old.iterdir()):
                if source.is_dir() and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", source.name):
                    snapshot(source, destination / "older" / source.name, source.name)
        return
    raise ValueError("No saved Pages artifact is available. Supply the existing rendered site; historical tags will not be rebuilt.")


def main():
    """Bootstrap the persistent store or insert one newly generated release."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["bootstrap", "snapshot", "save", "check"])
    parser.add_argument("--store", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--tag")
    args = parser.parse_args()
    if args.operation == "check":
        check_navigation(args.store)
    elif args.operation == "bootstrap":
        bootstrap(args.store)
    elif args.operation == "snapshot":
        if not args.tag or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.tag):
            raise ValueError("Expected a stable release tag.")
        snapshot(args.source, args.store / "older" / args.tag[1:], args.tag[1:])
    else:
        store = args.store.resolve()
        if not store.is_relative_to(Path("build").resolve()) or not (store / ".git").is_file():
            raise ValueError("The generated documentation store must be a worktree inside build/.")
        if not (args.source / "index.html").is_file():
            raise ValueError("The generated documentation is missing.")
        for path in store.iterdir():
            if path.name != ".git":
                if path.is_dir():
                    shutil.rmtree(path)
                else:
                    path.unlink()
        shutil.copytree(args.source, store, dirs_exist_ok=True)


if __name__ == "__main__":
    main()
