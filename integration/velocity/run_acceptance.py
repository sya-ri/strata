"""Verify real Paper and Velocity coexistence, input, and backend switching with fresh loopback servers.

Uses checksum-verified official distributions and the existing native development/production client suite.
The proxy accepts offline clients only on loopback; existing workspace Minecraft EULA acceptance is required.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager, ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tomllib
import uuid
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "paper"))
from run_acceptance import ROOT, artifact, gradle, prepare_paper_bootstrap, properties, request


def download(project: str, version: str, output: Path) -> tuple[Path, str]:
    """Verify a distribution against its official manifest; retain the exact manifest beside receipts."""
    manifest = json.loads(request(f"https://fill.papermc.io/v3/projects/{project}/versions/{version}/builds/latest"))
    release = manifest["downloads"]["server:default"]
    checksum = release["checksums"]["sha256"]
    jar = ROOT / f"build/{project}-downloads" / checksum / release["name"]
    jar.parent.mkdir(parents=True, exist_ok=True)
    if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != checksum:
        content = request(release["url"])
        if hashlib.sha256(content).hexdigest() != checksum:
            raise RuntimeError(f"The official {project} checksum does not match.")
        jar.write_bytes(content)
    (output / f"{project}-build.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return jar, checksum


@contextmanager
def running(java: Path, jar: Path, directory: Path, output: Path, run: str, proxy: bool = False):
    """Own one hidden process from startup through graceful shutdown, including failed client verification."""
    log_path = output / f"{directory.name}.log"
    arguments = [str(java.resolve()), "-Xms256m", "-Xmx2g", f"-Dstrata.paper.run={run}", f"-Dstrata.velocity.run={run}", "-jar", str(jar)]
    if not proxy:
        prepare_paper_bootstrap(jar, directory)
        arguments.append("--nogui")
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(arguments, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                   text=True, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 300
            ready = "Done (" if proxy else "For help, type"
            while ready not in log_path.read_text(encoding="utf-8", errors="replace"):
                if process.poll() is not None or deadline <= time.monotonic():
                    raise RuntimeError(f"Server startup failed; inspect {log_path}")
                time.sleep(1)
            yield
        finally:
            if process.poll() is None:
                try:
                    process.communicate("shutdown\n" if proxy else "stop\n", timeout=60)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=30)


def main() -> None:
    """Provision two Paper backends and a proxy, then assert invocation-bound receipts from every endpoint."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--java", type=Path, required=True, help="JVM for the requested Paper release.")
    parser.add_argument("--proxy-java", type=Path, required=True, help="JVM for the catalog's Velocity release.")
    parser.add_argument("--port", type=int, default=25588)
    parser.add_argument("--development-only", action="store_true")
    arguments = parser.parse_args()
    if not arguments.version.replace(".", "").isdigit() or not 1024 <= arguments.port <= 65533:
        parser.error("Use a numeric Minecraft release and three adjacent unprivileged ports.")
    run = str(uuid.uuid4())
    output = ROOT / "build/velocity-acceptance" / arguments.version / run
    output.mkdir(parents=True)
    print(f"Velocity acceptance: {output}", flush=True)
    eula = ROOT / f"integration/minecraft-fabric-{arguments.version}/build/run/clientGameTest/eula.txt"
    if properties(eula).get("eula") != "true":
        raise RuntimeError("Existing workspace EULA acceptance is required.")
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))
    velocity, velocity_sha = download("velocity", catalog["versions"]["velocity-api"], output)
    paper, paper_sha = download("paper", arguments.version, output)
    modules = ["runtime/paper", "examples/paper", "integration/paper", "runtime/velocity", "examples/velocity", "integration/velocity"]
    gradle([f":{module.replace('/', ':')}:{'pluginJar' if module.startswith('runtime/') else 'jar'}" for module in modules], output / "build.log")
    distributions = {module: artifact(module, "*-plugin.jar" if module.startswith("runtime/") else f"{module.split('/')[-1]}-*.jar") for module in modules}
    backends = [output / "first", output / "second"]
    for index, directory in enumerate(backends):
        plugins = directory / "plugins"
        plugins.mkdir(parents=True)
        shutil.copyfile(eula, directory / "eula.txt")
        for module in modules[:3]:
            shutil.copyfile(distributions[module], plugins / f"strata-{module.split('/')[0]}.jar")
        (directory / "server.properties").write_text(
            f"server-ip=127.0.0.1\nserver-port={arguments.port + index + 1}\nonline-mode=false\nenforce-secure-profile=false\n"
            "white-list=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=1\nlevel-seed=1\n"
            "level-type=minecraft:flat\ngenerate-structures=false\nenable-rcon=false\ndifficulty=peaceful\n", encoding="utf-8")
    proxy = output / "proxy"
    (proxy / "plugins").mkdir(parents=True)
    for module in modules[3:]:
        shutil.copyfile(distributions[module], proxy / "plugins" / f"strata-{module.split('/')[0]}.jar")
    with ZipFile(velocity) as archive:
        config = archive.read("default-velocity.toml").decode("utf-8")
    config = re.sub(r'(?m)^bind = .*$', f'bind = "127.0.0.1:{arguments.port}"', config)
    config = re.sub(r'(?m)^(online-mode|force-key-authentication) = true$', r'\1 = false', config)
    config = re.sub(r'(?ms)^\[servers\].*?(?=^\[)', f'[servers]\nfirst = "127.0.0.1:{arguments.port + 1}"\nsecond = "127.0.0.1:{arguments.port + 2}"\ntry = ["first"]\n\n', config)
    config = re.sub(r'(?ms)^\[forced-hosts\].*?(?=^\[)', '[forced-hosts]\n\n', config)
    (proxy / "velocity.toml").write_text(config, encoding="utf-8")
    tasks = ["runClientGameTest"] if arguments.development_only else ["runClientGameTest", "runProductionClientGameTest"]
    with ExitStack() as stack:
        for directory in backends:
            stack.enter_context(running(arguments.java, paper, directory, output, run))
        stack.enter_context(running(arguments.proxy_java, velocity, proxy, output, run, proxy=True))
        for task in tasks:
            receipts = [directory / "plugins/StrataPaperAcceptance/server.properties" for directory in backends]
            receipts.append(proxy / "plugins/strata-acceptance/server.properties")
            for receipt in receipts:
                receipt.unlink(missing_ok=True)
            gradle([f":integration:minecraft-fabric-{arguments.version}:{task}", f"-Pstrata.paper.address=127.0.0.1:{arguments.port}", f"-Pstrata.paper.run={run}", f"-Pstrata.velocity.run={run}"], output / f"{task}.log")
            for index, receipt in enumerate(receipts):
                proof = properties(receipt)
                if proof.get("runId") != run or (proof.get("slot") != "round-trip" if index < 2 else proof.get("activations") != "2"):
                    raise RuntimeError(f"Missing current endpoint evidence: {receipt}")
                if any(proof.get(key) != "confirmed" for key in ("uiPresentations", "uiEvents")):
                    raise RuntimeError(f"Missing current endpoint HUD and lifecycle evidence: {receipt}")
                shutil.copyfile(receipt, output / f"{task}-server-{index}.properties")
            family = "verification" if arguments.version.startswith("1.") else "parity"
            parity = f"minecraft-{family}" if task == "runClientGameTest" else f"minecraft-production-{family}"
            client = ROOT / f"integration/minecraft-fabric-{arguments.version}/build/{parity}/velocity-client.properties"
            if properties(client).get("runId") != run:
                raise RuntimeError("Missing current client-side Velocity evidence.")
            shutil.copyfile(client, output / f"{task}-client.properties")
    (output / "passed.json").write_text(json.dumps({"runId": run, "version": arguments.version, "paperSha256": paper_sha, "velocitySha256": velocity_sha, "tasks": tasks,
        "artifacts": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in distributions.values()}}, indent=2) + "\n", encoding="utf-8")
    print(f"Velocity acceptance passed: {output}", flush=True)


if __name__ == "__main__":
    main()
