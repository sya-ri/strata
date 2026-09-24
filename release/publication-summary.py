#!/usr/bin/env python3
"""Record the selected publication request and summarize destination receipts without credentials."""
import json
import os
from pathlib import Path


def summarize(root, selections, operation):
    """Distinguish completed uploads from approval; missing evidence never becomes success."""
    states = {}
    for destination, enabled in selections.items():
        if not enabled:
            states[destination] = "disabled"
            continue
        if destination == "hangar":
            path = root / "hangar/receipt.json"
            states[destination] = json.loads(path.read_text())["state"] if path.is_file() else "not completed"
        elif destination == "curseforge":
            path = root / "curseforge/receipt.json"
            files = json.loads(path.read_text()).get("files", {}) if path.is_file() else {}
            values = {file["state"] for file in files.values()}
            states[destination] = "exact" if values == {"verified"} else "pending" if values <= {"pending", "verified"} and values else "not completed"
        elif destination == "modrinth":
            names = ["verify", "finalize_project", "submit", "stage"] if operation == "verify" else ["finalize_project", "submit", "stage"]
            path = next((root / f"modrinth-receipts/{name}.json" for name in names if (root / f"modrinth-receipts/{name}.json").is_file()), None)
            states[destination] = json.loads(path.read_text()).get("projectStatus", "unknown") if path else "not completed"
        else:
            states[destination] = "see workflow step"
    return states


def main():
    """Write the producer-bound request and the Actions job summary, including failed-run evidence."""
    request = json.loads(os.environ["PUBLICATION_REQUEST"])
    root = Path("build/release")
    states = summarize(root, request["destinations"], request["operation"])
    request.update(schemaVersion=1, runId=int(os.environ["GITHUB_RUN_ID"]), runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]),
                   controllerCommit=os.environ["GITHUB_SHA"], results=states)
    root.mkdir(parents=True, exist_ok=True)
    (root / "publication-request.json").write_text(json.dumps(request, indent=2) + "\n", encoding="utf-8")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"## Publication {request['tag']}\n\n| Destination | Result |\n| --- | --- |\n")
        for destination, state in states.items():
            summary.write(f"| {destination} | {state} |\n")
        summary.write("\nAccepted uploads may still await review. Public verification is separate from upload acceptance.\n")
        summary.write("The approval monitor requests final verification automatically after selected distributions become public.\n")


if __name__ == "__main__":
    main()
