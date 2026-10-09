"""Run a real production Fabric server and exact-version clients with fresh paired acceptance receipts."""

import argparse
import os
import shutil
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def properties(path: Path) -> dict[str, str]:
    """Read detached primitive evidence written only by the current invocation."""
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)


def command(arguments: list[str]) -> list[str]:
    """Launch the workspace-pinned toolchain and wrapper without shell interpolation."""
    mise = shutil.which("mise.exe" if os.name == "nt" else "mise")
    toolchain = [mise, "exec", "--"] if mise else []
    return [*toolchain, str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), *arguments, "--no-daemon", "--max-workers=2", "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain"]


def main() -> None:
    """Own one isolated loopback server, verify both ends, and stop that server on every exit."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--port", type=int, default=25589)
    parser.add_argument("--development-only", action="store_true")
    args = parser.parse_args()
    if not args.version.replace(".", "").isdigit() or not 1024 <= args.port <= 65535:
        parser.error("An exact numeric release and an unprivileged local port are required.")
    invocation = str(uuid.uuid4())
    output = ROOT / "build/fabric-acceptance" / args.version / invocation
    server = output / "server"
    settings = output / "settings"
    settings.mkdir(parents=True)
    receipt = output / "server.properties"
    stop = output / "stop"
    # configureTests already accepts the EULA for this repository's isolated loaded acceptance environments.
    (settings / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (settings / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nwhite-list=false\nenforce-secure-profile=false\n"
        "view-distance=2\nsimulation-distance=2\nspawn-protection=0\nmax-players=4\npause-when-empty-seconds=-1\nlevel-seed=1\n"
        "level-type=minecraft:flat\ngenerate-structures=false\nenable-rcon=false\ndifficulty=peaceful\n",
        encoding="utf-8",
    )
    server_args = [f":integration:minecraft-fabric-{args.version}:runProductionServerUiTest",
                   f"-Pstrata.fabric.run={invocation}", f"-Pstrata.fabric.serverDirectory={server}", f"-Pstrata.fabric.serverSettings={settings}",
                   f"-Pstrata.fabric.serverReceipt={receipt}", f"-Pstrata.fabric.serverStop={stop}"]
    log_path = output / "server.log"
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command(server_args), cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 900
            while 'Done (' not in log_path.read_text(encoding="utf-8", errors="replace"):
                if process.poll() is not None:
                    raise RuntimeError(f"Fabric server startup failed; inspect {log_path}")
                if deadline <= time.monotonic():
                    raise TimeoutError(f"Fabric server startup timed out; inspect {log_path}")
                time.sleep(1)
            runs = ["runClientGameTest"] if args.development_only else ["runClientGameTest", "runProductionClientGameTest"]
            for task in runs:
                previous = int(properties(receipt).get("completions", "0")) if receipt.exists() else 0
                is_production = task == "runProductionClientGameTest"
                candidates = [ROOT / f"integration/minecraft-fabric-{args.version}/build" / name / "fabric-client.properties"
                              for name in (["minecraft-production-verification", "minecraft-production-parity"] if is_production else ["minecraft-verification", "minecraft-parity"])]
                for candidate in candidates:
                    candidate.unlink(missing_ok=True)
                with (output / f"{task}.log").open("w", encoding="utf-8") as client_log:
                    subprocess.run(command([f":integration:minecraft-fabric-{args.version}:{task}",
                                            f"-Pstrata.fabric.address=127.0.0.1:{args.port}",
                                            f"-Pstrata.fabric.run={invocation}"]), cwd=ROOT, stdout=client_log,
                                   stderr=subprocess.STDOUT, check=True, timeout=1800)
                client = properties(next(path for path in candidates if path.is_file()))
                authoritative = properties(receipt)
                if client.get("runId") != invocation or client.get("reconnect") != "confirmed":
                    raise RuntimeError("Fresh client input, update, and reconnect evidence is missing.")
                if authoritative.get("runId") != invocation or int(authoritative.get("completions", "0")) != previous + 2:
                    raise RuntimeError("The production server did not confirm both independent UI sessions.")
                (output / f"{task}.properties").write_text("\n".join(f"{key}={value}" for key, value in client.items()) + "\n", encoding="utf-8")
        finally:
            stop.touch()
            try:
                process.wait(timeout=180)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], check=True, stdout=subprocess.DEVNULL)
                else:
                    process.terminate()
                process.wait(timeout=30)
                raise RuntimeError(f"The isolated server process required forced cleanup; inspect {log_path}")
            if process.returncode != 0:
                raise RuntimeError(f"The owned Fabric server did not stop cleanly; inspect {log_path}")
    if properties(receipt).get("shutdown") != "confirmed":
        raise RuntimeError("The server shutdown callback did not produce terminal evidence.")
    print(f"Verified Fabric server input, updates, reconnection, and shutdown: {output}")


if __name__ == "__main__":
    main()
