#!/usr/bin/env python3
"""Single source of truth for release artifacts and the Vector injection scope.

Why this module exists
----------------------
Three separate places used to guess "the latest Hook APK" with
``sorted(glob("releases/PenBridge-Hook-tb522fu-v*.apk"))[-1]``. That is a
*lexicographic* max, which is wrong in two ways that both bit us for real:

  * ``v4.5.10`` sorts BEFORE ``v4.5.4`` -> a new build silently loses to an old one;
  * ``releases/`` still contains ``v4.1.3`` / ``v4.1.13``, which are the pre-rename
    package (``com.aclaniakea.lenovopenbridge``). ``scripts/push_to_device.sh``
    hardcoded ``v4.1.3`` and ``scripts/deploy_vector.sh`` defaulted to ``v4.1.13``,
    so a "deploy" could install an APK whose package name no longer matches the
    module id we ``enable`` / ``scope set`` -- the module then never gets injected
    and every custom pen key goes dead. See TODO.md P1.5.

The scope list has exactly one authoring surface: the Xposed manifest read by the
build (``hook/source/resources/META-INF/xposed/scope.list``, mirrored in
``res/values/arrays.xml``). Runtime ``vector-cli scope set`` MUST be derived from
it -- never retyped -- because ``scope set`` overwrites the whole table and a
single dropped entry (``system/0``) silently kills every system_server hook while
all app-scoped hooks keep working, which looks exactly like a code regression.

CLI
---
  python3 scripts/pen_release.py hook-apk     # newest Hook APK (version sorted)
  python3 scripts/pen_release.py module-zip   # newest KernelSU module zip
  python3 scripts/pen_release.py scope        # "system/0 pkg/0 ..." for vector-cli
  python3 scripts/pen_release.py scope-list   # one entry per line
  python3 scripts/pen_release.py adb          # resolved adb binary
"""

from __future__ import annotations

import os
import re
import shutil
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
RELEASES = REPO / "releases"
SCOPE_LIST = REPO / "hook" / "source" / "resources" / "META-INF" / "xposed" / "scope.list"

HOOK_APK_GLOB = "PenBridge-Hook-tb522fu-v*.apk"
MODULE_ZIP_GLOB = "tb522fu-pen-bridge-v*.zip"

# Anything older than this belongs to the pre-rename package
# (com.aclaniakea.lenovopenbridge) and MUST NOT be deployed against the current
# module id. Kept as a floor so a botched build cannot silently fall back to one.
MIN_HOOK_VERSION = (4, 5, 0)

ADB_CANDIDATES = (
    "/opt/homebrew/bin/adb",
    "/usr/local/bin/adb",
    "~/Library/Android/sdk/platform-tools/adb",
)


def version_key(path_or_name: str | Path) -> tuple[int, int, int]:
    """Extract a comparable ``(major, minor, patch)`` from a release file name."""
    name = Path(path_or_name).name
    match = re.search(r"-v(\d+)\.(\d+)\.(\d+)", name) or re.search(r"v(\d+)\.(\d+)\.(\d+)", name)
    if not match:
        return (0, 0, 0)
    return tuple(int(g) for g in match.groups())  # type: ignore[return-value]


def latest_matching(glob: str, releases: Path = RELEASES) -> Path:
    candidates = [p for p in releases.glob(glob) if p.is_file()]
    if not candidates:
        raise FileNotFoundError(f"no artifact matching {glob} in {releases}")
    return max(candidates, key=version_key)


def latest_hook_apk(releases: Path = RELEASES, enforce_floor: bool = True) -> Path:
    apk = latest_matching(HOOK_APK_GLOB, releases)
    if enforce_floor and version_key(apk) < MIN_HOOK_VERSION:
        raise SystemExit(
            f"refusing to use stale Hook APK {apk.name}: older than "
            f"v{'.'.join(map(str, MIN_HOOK_VERSION))} (pre-rename package name). "
            f"Build a new one with hook/tools/build_hook_source.py."
        )
    return apk


def latest_module_zip(releases: Path = RELEASES) -> Path:
    return latest_matching(MODULE_ZIP_GLOB, releases)


def scope_entries(scope_list: Path = SCOPE_LIST) -> list[str]:
    """Package names from the Xposed manifest scope list, comments/blank dropped."""
    if not scope_list.is_file():
        raise FileNotFoundError(f"scope list not found: {scope_list}")
    out: list[str] = []
    for raw in scope_list.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        pkg = line.split()[0]
        if pkg not in out:
            out.append(pkg)
    if not out:
        raise SystemExit(f"scope list is empty: {scope_list}")
    return out


def scope_args(scope_list: Path = SCOPE_LIST) -> list[str]:
    """``vector-cli`` entries: every package on user 0."""
    return [f"{pkg}/0" for pkg in scope_entries(scope_list)]


def scope_string(scope_list: Path = SCOPE_LIST) -> str:
    return " ".join(scope_args(scope_list))


def system_scope_entry(scope_list: Path = SCOPE_LIST) -> str:
    """The ``system/0`` pseudo-package entry -- the one whose absence kills
    system_server injection (stylus keys, haptics, input gate, magnetic dock)."""
    entries = scope_args(scope_list)
    for entry in entries:
        if entry.startswith("system/"):
            return entry
    raise SystemExit(
        f"{scope_list} has no `system` entry; system_server would never be injected"
    )


def resolve_adb(explicit: str | None = None) -> str:
    if explicit:
        return explicit
    env = os.environ.get("ADB")
    if env:
        return env
    found = shutil.which("adb")
    if found:
        return found
    for cand in ADB_CANDIDATES:
        path = Path(os.path.expanduser(cand))
        if path.is_file() and os.access(path, os.X_OK):
            return str(path)
    raise SystemExit(
        "adb not found. Install platform-tools or set ADB=/path/to/adb "
        f"(looked at: {', '.join(ADB_CANDIDATES)})"
    )


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "hook-apk"
    if cmd == "hook-apk":
        print(latest_hook_apk())
    elif cmd == "module-zip":
        print(latest_module_zip())
    elif cmd == "scope":
        print(scope_string())
    elif cmd == "scope-list":
        print("\n".join(scope_entries()))
    elif cmd == "system-entry":
        print(system_scope_entry())
    elif cmd == "version":
        print(".".join(map(str, version_key(latest_hook_apk()))))
    elif cmd == "adb":
        print(resolve_adb())
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
