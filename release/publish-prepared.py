#!/usr/bin/env python3
"""Publish one destination from an immutable prepared release, without rebuilding it."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import uuid

CONTROLLER = Path(__file__).resolve().parent.parent


def tool(name):
    """Load the existing service clients from the frozen controller checkout."""
    spec = importlib.util.spec_from_file_location(name, CONTROLLER / "release" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepared = tool("prepared-release")


def reconcile(operation, root):
    """Run only the controller's remote clients; product projects are never configured."""
    subprocess.run(["bash", str(CONTROLLER / "gradlew"), "-p", str(CONTROLLER / "release/tools"),
                    "reconcile", "-PreleaseOperation=" + operation, "-PpreparedRelease=" + str(root)], check=True)
    return prepared.read_json(root.parent / "receipts" / (operation + ".json"))


def verify_signatures(repository, key):
    """Check detached signatures against the public key saved by the preparation job."""
    with tempfile.TemporaryDirectory() as directory:
        command = ["gpg", "--batch", "--homedir", directory]
        subprocess.run(command + ["--import", str(key)], capture_output=True, check=True)
        signatures = list(repository.rglob("*.asc"))
        prepared.require(signatures, "The distribution has no signatures.")
        for signature in signatures:
            subprocess.run(command + ["--verify", str(signature), str(signature.with_suffix(""))], capture_output=True, check=True)


def central(root, operation, allow_upload):
    """Reconcile Central before the single non-retried Portal upload."""
    verify_signatures(root / "maven", root / "signing-key.asc")
    if operation == "release":
        public = reconcile("central-preflight", root)
        portal = reconcile("portal-preflight", root)
        prepared.require(public["state"] != "exact" or portal["state"] == "exact", "Central and Portal states conflict.")
        if public["state"] == "absent" and portal["state"] == "absent":
            prepared.require(allow_upload, "Maven publication is disabled and the release is absent.")
            username = os.environ["ORG_GRADLE_PROJECT_mavenCentralUsername"]
            password = os.environ["ORG_GRADLE_PROJECT_mavenCentralPassword"]
            token = base64.b64encode(f"{username}:{password}".encode()).decode()
            boundary = uuid.uuid4().hex
            data = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
                    'Content-Type: application/octet-stream\r\n\r\n').encode()
            data += (root / "central-bundle.zip").read_bytes() + f"\r\n--{boundary}--\r\n".encode()
            request = urllib.request.Request("https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC",
                                             data=data, headers={"Authorization": "Bearer " + token,
                                                                 "Content-Type": "multipart/form-data; boundary=" + boundary})
            with urllib.request.build_opener(tool("hangar-release").NoRedirect()).open(request, timeout=300) as response:
                deployment_id = response.read(1024).decode()
            prepared.write_json(root.parent / "receipts/central-upload.json", {"deploymentId": str(uuid.UUID(deployment_id.strip()))})
    reconcile("portal-verify", root)
    reconcile("central-verify", root)
    verify_signatures(root.parent / "receipts/central", root / "signing-key.asc")


def github_release(root, operation, identity):
    """Append missing draft assets, verifying exact content before making a release public."""
    bundle = root.parent / "github-bundle"
    bundle.mkdir(parents=True, exist_ok=True)
    manifest = prepared.read_json(root / "modrinth/manifest.json")
    for artifact in manifest["artifacts"]:
        name = artifact["githubAssetName"]
        shutil.copyfile(root / "modrinth" / artifact["relativePath"], bundle / name)
        version = identity["tag"].removeprefix("v")
        coordinate = "strata-runtime-minecraft-fabric-" + artifact["gameVersion"]
        url = f"https://repo1.maven.org/maven2/dev/s7a/strata/{coordinate}/{version}/{name}.asc"
        with urllib.request.urlopen(url, timeout=60) as response:
            (bundle / (name + ".asc")).write_bytes(response.read(128 * 1024))
    verify_signatures(bundle, root / "signing-key.asc")
    shutil.copyfile(root.parent / "release-prepared.tar.gz", bundle / "release-prepared.tar.gz")
    (bundle / "SHA256SUMS").write_text("".join(f"{prepared.sha256(path)}  {path.name}\n" for path in sorted(bundle.iterdir())
                                               if path.name != "SHA256SUMS"), encoding="ascii")
    os.environ.update(RELEASE_TAG=identity["tag"], CENTRAL_STATE="exact")
    os.environ.setdefault("GITHUB_API_URL", "https://api.github.com")
    preflight = ["bash", str(CONTROLLER / "release/github-release-preflight.sh"), str(bundle)]
    subprocess.run(preflight, check=True)

    read = ["bash", str(CONTROLLER / "release/github-release-read.sh"), "find"]
    remote = json.loads(subprocess.check_output(read))
    if operation == "verify":
        prepared.require(remote is not None and not remote["draft"], "GitHub Release is not public.")
        return
    if remote is None:
        subprocess.run(["gh", "release", "create", identity["tag"], "--verify-tag", "--draft", "--title",
                        "Strata " + identity["tag"].removeprefix("v"), "--notes-file", str(root / "release-notes.md")], check=True)
        remote = json.loads(subprocess.check_output(read))
    if remote["draft"]:
        existing = {asset["name"] for asset in remote["assets"]}
        for path in sorted(bundle.iterdir()):
            if path.name not in existing:
                subprocess.run(["gh", "release", "upload", identity["tag"], str(path)], check=True)
        subprocess.run(preflight, check=True)
        subprocess.run(["gh", "api", "--method", "PATCH", f"repos/{os.environ['GITHUB_REPOSITORY']}/releases/{remote['id']}",
                        "-F", "draft=false", "-f", "make_latest=legacy"], stdout=subprocess.DEVNULL, check=True)
    subprocess.run(preflight, check=True)


def main():
    """Execute exactly one selected destination and retain its independent receipt."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", choices=["central", "github", "modrinth", "curseforge", "hangar"])
    parser.add_argument("operation", choices=["release", "verify"])
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--allow-maven-upload", action="store_true")
    args = parser.parse_args()
    root = args.prepared.resolve()
    identity = prepared.read_json(root / "prepared.json")
    prepared.validate(root, identity["tag"], identity["sourceCommit"])
    output = root.parent / "receipts"
    output.mkdir(exist_ok=True)
    os.chdir(root)
    if args.destination == "central":
        central(root, args.operation, args.allow_maven_upload)
    elif args.destination == "github":
        github_release(root, args.operation, identity)
    elif args.destination == "modrinth":
        reconcile("modrinth-stage" if args.operation == "release" else "modrinth-verify", root)
    elif args.destination == "hangar":
        os.environ["STRATA_PREPARED_RELEASE"] = str(root)
        os.environ["STRATA_RELEASE_CONTROLLER"] = str(CONTROLLER)
        subprocess.run(["python3", str(CONTROLLER / "release/hangar-release.py"), "stage" if args.operation == "release" else "verify",
                        "--manifest", "hangar/manifest.json", "--project", str(CONTROLLER / "release/hangar-project.json"),
                        "--source-commit", identity["sourceCommit"], "--receipt", str(output / "hangar/receipt.json")], check=True)
    else:
        tool("curseforge-receipts").restore(os.environ["GITHUB_REPOSITORY"], identity["tag"], identity["sourceCommit"],
                                             int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"]), output / "curseforge")
        command = ["python3", str(CONTROLLER / "release/curseforge-release.py"), "stage" if args.operation == "release" else "verify",
                   "--manifest", "modrinth/manifest.json", "--project", str(CONTROLLER / "release/curseforge-project.json"),
                   "--source-commit", identity["sourceCommit"], "--receipt", str(output / "curseforge/receipt.json"),
                   "--history", str(output / "curseforge/history.json")]
        if args.operation != "verify" or os.environ.get("CURSEFORGE_API_KEY"):
            subprocess.run(command, check=True)
        else:
            print("CurseForge public verification skipped: no read API key.")


if __name__ == "__main__":
    main()
