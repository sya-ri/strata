"""Run exact-version Paper/Fabric acceptance with fresh worlds and invocation-bound receipts.

All downloaded binaries are verified against the official Paper download manifest.
The isolated offline server binds only to loopback. Existing workspace EULA acceptance is required.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]
USER_AGENT = "StrataAcceptance (https://github.com/sya-ri/strata)"


def request(url: str) -> bytes:
    """Fetch one official manifest or binary with an identifiable caller and bounded timeout."""
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=120) as response:
        return response.read()


def gradle(arguments: list[str], log: Path) -> None:
    """Run a repository task and retain its complete output independently of the server log."""
    command = [str(ROOT / "gradlew.bat" if os.name == "nt" else ROOT / "gradlew"), *arguments, "--max-workers=2", "--console=plain"]
    with log.open("w", encoding="utf-8") as output:
        subprocess.run(command, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=True)


def artifact(module: str, pattern: str) -> Path:
    """Find the freshly built module archive; historical release outputs cannot win over the current build."""
    return max((ROOT / module / "build/libs").glob(pattern), key=lambda path: path.stat().st_mtime_ns)


def properties(path: Path) -> dict[str, str]:
    """Read the simple primitive acceptance receipt format without accepting stale or absent files."""
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)


def main() -> None:
    """Provision a verified Paper build, run development and production clients, then stop the owned server."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--port", type=int, default=25588)
    parser.add_argument("--development-only", action="store_true")
    parser.add_argument("--manual-ime", action="store_true", help="Run a normal 26.2 or 26.3 client for operator-driven OS IME input.")
    arguments = parser.parse_args()
    if not arguments.version.replace(".", "").isdigit():
        parser.error("An exact numeric Minecraft release is required.")
    if not 1024 <= arguments.port <= 65535:
        parser.error("Use an unprivileged local port.")
    if arguments.manual_ime and arguments.version not in ("26.2", "26.3"):
        parser.error("The manual OS IME fixture supports the 26.2 and 26.3 clients.")
    run_id = str(uuid.uuid4())
    output = ROOT / "build/paper-acceptance" / arguments.version / run_id
    output.mkdir(parents=True)
    eula = ROOT / f"integration/minecraft-fabric-{arguments.version}/build/run/clientGameTest/eula.txt"
    if properties(eula).get("eula") != "true":
        raise RuntimeError("Existing workspace EULA acceptance is required before starting a server.")
    versions = json.loads(request("https://fill.papermc.io/v3/projects/paper"))["versions"]
    if arguments.version not in [version for group in versions.values() for version in group]:
        raise RuntimeError("Paper does not distribute the exact requested game version; client-only verification remains separate.")
    manifest = json.loads(request(f"https://fill.papermc.io/v3/projects/paper/versions/{arguments.version}/builds/latest"))
    download = manifest["downloads"]["server:default"]
    expected = download["checksums"]["sha256"]
    cache = ROOT / "build/paper-downloads" / expected
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / download["name"]
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != expected:
        data = request(download["url"])
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError("Official Paper download checksum does not match.")
        jar.write_bytes(data)
    (output / "paper-build.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    gradle([":runtime:paper:pluginJar", ":examples:paper:jar", ":integration:paper:jar"], output / "build.log")
    server = output / "server"
    plugins = server / "plugins"
    plugins.mkdir(parents=True)
    distributions = [artifact("runtime/paper", "strata-runtime-paper-*-plugin.jar"), artifact("examples/paper", "paper-*.jar"), artifact("integration/paper", "paper-*.jar")]
    for index, distribution in enumerate(distributions):
        shutil.copyfile(distribution, plugins / f"strata-{index}.jar")
    shutil.copyfile(eula, server / "eula.txt")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={arguments.port}\nonline-mode=false\nenforce-secure-profile=false\n"
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=1\nlevel-seed=1\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nenable-rcon=false\n",
        encoding="utf-8",
    )
    if arguments.manual_ime:
        with (server / "server.properties").open("a", encoding="utf-8") as properties_file:
            properties_file.write("difficulty=peaceful\n")
    server_log = output / "server.log"
    with server_log.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            [str(arguments.java.resolve()), "-Xms512m", "-Xmx2g", f"-Dstrata.paper.run={run_id}", "-jar", str(jar), "--nogui"],
            cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        try:
            deadline = time.monotonic() + 300
            while "For help, type" not in server_log.read_text(encoding="utf-8", errors="replace"):
                if process.poll() is not None or deadline <= time.monotonic():
                    raise RuntimeError(f"Paper did not start; inspect {server_log}")
                time.sleep(1)
            tasks = ["runClientGameTest"] if arguments.development_only else ["runClientGameTest", "runProductionClientGameTest"]
            if arguments.manual_ime:
                tasks = ["runManualPaperIme"]
            for task in tasks:
                server_receipt = plugins / "StrataPaperAcceptance" / ("manual-server.properties" if arguments.manual_ime else "server.properties")
                server_receipt.unlink(missing_ok=True)
                gradle([f":integration:minecraft-fabric-{arguments.version}:{task}", f"-Pstrata.paper.address=127.0.0.1:{arguments.port}", f"-Pstrata.paper.run={run_id}"], output / f"{task}.log")
                proof = properties(server_receipt)
                if proof.get("runId") != run_id or (proof.get("input") != "operator-confirmed" if arguments.manual_ime else proof.get("slot") != "round-trip"):
                    raise RuntimeError("Missing current server-side action and container evidence.")
                parity = "minecraft-parity" if task == "runClientGameTest" else "minecraft-production-parity"
                if arguments.version.startswith("1."):
                    parity = "minecraft-verification" if task == "runClientGameTest" else "minecraft-production-verification"
                client_receipt = ROOT / f"integration/minecraft-fabric-{arguments.version}/build/{parity}/paper-client.properties"
                if arguments.manual_ime:
                    client_receipt = ROOT / f"integration/minecraft-fabric-{arguments.version}/build/manual-paper-ime-evidence/manual-client.properties"
                if properties(client_receipt).get("runId") != run_id:
                    raise RuntimeError("Missing current client-side Paper evidence.")
                shutil.copyfile(server_receipt, output / f"{task}-server.properties")
                shutil.copyfile(client_receipt, output / f"{task}-client.properties")
            (output / "passed.json").write_text(json.dumps({"runId": run_id, "version": arguments.version, "paperSha256": expected, "tasks": tasks, "artifacts": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in distributions}}, indent=2) + "\n", encoding="utf-8")
            print(f"Paper acceptance passed: {output}")
        finally:
            if process.poll() is None:
                try:
                    process.communicate("stop\n", timeout=60)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=30)


if __name__ == "__main__":
    main()
