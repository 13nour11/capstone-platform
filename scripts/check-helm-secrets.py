#!/usr/bin/env python3
"""Fails when a Helm values file references a Kubernetes Secret that nothing creates.

A `secretName` / `extraSecretNames` entry becomes an `envFrom.secretRef` in the Deployment. If that
Secret does not exist, the kubelet never starts the container: the pod sits in
CreateContainerConfigError and the only clue is a kubectl describe. This happened in this repo --
every service pointed at `<service>-secrets` while the script created `db-credentials`.

The check is deliberately narrow: it compares names only, which is unambiguous. It does not try to
guess whether a property is satisfied, because Spring resolves the same property from several
differently named sources.

Usage:  python3 scripts/check-helm-secrets.py      # exit 1 when a referenced Secret is never created
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VALUES = ROOT / "deployment/helm/values"
SECRETS_SCRIPT = ROOT / "scripts/create-k8s-secrets.sh"

SECRET_NAME = re.compile(r"^secretName:\s*(\S+)", re.M)
EXTRA_BLOCK = re.compile(r"^extraSecretNames:\s*\n((?:\s+-\s*\S+\s*\n)+)", re.M)
CREATED = re.compile(r"apply\s+(?:secret\s+generic|secret\s+docker-registry|configmap)\s+([a-z0-9][a-z0-9-]*)")


def referenced() -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for f in sorted(VALUES.glob("*.yaml")):
        text = f.read_text(encoding="utf-8")
        names = {n.strip().strip('"\'') for n in SECRET_NAME.findall(text) if n.strip().strip('"\'')}
        block = EXTRA_BLOCK.search(text)
        if block:
            names |= {line.strip().lstrip("-").strip().strip('"\'') for line in block.group(1).splitlines() if line.strip()}
        for n in names:
            out.setdefault(n, set()).add(f.name)
    return out


def main() -> int:
    if not SECRETS_SCRIPT.exists():
        print(f"ERROR: {SECRETS_SCRIPT} not found")
        return 1

    created = set(CREATED.findall(SECRETS_SCRIPT.read_text(encoding="utf-8")))
    refs = referenced()
    missing = {name: files for name, files in refs.items() if name not in created}

    for name, files in sorted(refs.items()):
        mark = "OK     " if name in created else "MISSING"
        print(f"  {mark} {name:28} <- {', '.join(sorted(files))}")

    if missing:
        print(f"\n{len(missing)} Secret(s) referenced by Helm values are never created by "
              f"{SECRETS_SCRIPT.relative_to(ROOT)}:")
        for name, files in sorted(missing.items()):
            print(f"  {name}  (referenced by {', '.join(sorted(files))})")
        print("\nPods using them stay in CreateContainerConfigError. Add the Secret to the script, "
              "or correct the name in the values file.")
        return 1

    print(f"\nOK: all {len(refs)} referenced Secrets are created by the script.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
