#!/usr/bin/env python3
"""SDL2 Java<->so JNI contract tracer (evidence for audit/10-sdl2-resync-prep.md).

Steps:
  1. Detect embedded SDL version strings inside each plugin's libSDL2.so.
  2. Extract Java_org_libsdl_app_* exports required by each .so.
  3. Strictly extract `native` declarations from vendored org/libsdl/app sources
     (line-based, immune to the cross-token false positives that plagued a naive
     regex -- e.g. it used to count plain method setOrientationBis as native).
  4. Verify A-direction contract: every declared native must have a matching
     export in EACH so, otherwise that engine process would hit
     UnsatisfiedLinkError at the call site.

Usage: python audit/tools/sdl2_contract_trace.py
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VENDOR = ROOT / "engine/src/main/java/org/libsdl/app"
SO = {
    "ons": ROOT / "app/src/main/nativeplugins/ons/arm64-v8a/libSDL2.so",
    "kr2": ROOT / "app/src/main/nativeplugins/kirikiroid2/arm64-v8a/libSDL2.so",
}


def main() -> None:
    # 1) version strings
    for tag, p in SO.items():
        text = p.read_bytes().decode("ascii", errors="ignore")
        vers = sorted(set(re.findall(r"\b2\.\d{1,2}\.\d{1,3}\b", text)))
        print(f"[version] {tag}: {vers or 'none (fork build?)'}")

    # 2) exports demanded by each so
    exports = {}
    for tag, p in SO.items():
        text = p.read_bytes().decode("ascii", errors="ignore")
        exports[tag] = sorted(set(re.findall(r"Java_org_libsdl_app_(\w+)\b", text)))
        print(f"[exports] {tag}: {len(exports[tag])} JNI symbols targeting org.libsdl.app")

    # 3) strict native declaration extraction
    declared: dict[str, list[str]] = {}
    for f in sorted(VENDOR.glob("*.java")):
        lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
        names: list[str] = []
        for i, ln in enumerate(lines):
            if not re.search(r"\bnative\b", ln):
                continue
            stmt = ln
            j = i
            while "{" not in stmt and ";" not in stmt and j + 1 < len(lines) and j - i < 4:
                j += 1
                stmt += lines[j]
            if "{" in stmt.split(";")[0]:
                continue  # has a body -> not a JNI declaration
            m = re.search(r"\bnative\b[^;()]*?(\w+)\s*\(", stmt)
            if m:
                names.append(m.group(1))
        if names:
            declared[f.stem] = sorted(set(names))

    total = sum(len(v) for v in declared.values())
    print(f"[declared] total={total} across {len(declared)} classes")

    # 4) A-direction coverage per so
    hard_gaps: list[str] = []
    single_gaps: list[tuple[str, str, str]] = []
    for cls, methods in sorted(declared.items()):
        for m in methods:
            hits = {t: f"{cls}_{m}" in ex for t, ex in exports.items()}
            missing = [t for t, ok in hits.items() if not ok]
            if missing:
                if len(missing) == len(exports):
                    hard_gaps.append(f"{cls}.{m}")
                    print(f"[HARD-GAP] {cls}.{m} missing in ALL sos")
                else:
                    single_gaps.extend((cls, m, t) for t in missing)

    ons_ok = sum(
        1
        for cls, ms in declared.items()
        for m in ms
        if all(f"{cls}_{m}" in e for e in [exports["ons"]])
    )
    print(f"[verdict] hard-gaps={len(hard_gaps)} single-side-gaps={len(single_gaps)} (all kr2={all(t=='kr2' for _,_,t in single_gaps)})")
    print(f"[verdict] ons(2.26.3 vanilla) covers {ons_ok}/{total} declared natives")
    for cls, m, t in single_gaps:
        print(f"   gap: {cls}.{m} -> absent in {t}")


if __name__ == "__main__":
    main()
