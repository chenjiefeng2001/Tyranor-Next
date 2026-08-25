#!/usr/bin/env python3
"""Deep verifiable tracer for Tyranor-Next.

Extracts machine-checkable evidence chains:
  A. Manifest components (activities/services/receivers/providers x process)
  B. Hidden dependencies: System.loadLibrary/System.load, dlopen/dlsym,
     Class.forName / FindClass / getDeclaredMethod / getDeclaredField
  C. Intent extras contract: producers (putXExtra) vs consumers (getXExtra),
     split by module (app/engine)
  D. SharedPreferences contract: who opens which prefs file (read/write side)
  E. JNI surface: Kotlin `external fun` / Java `native` methods vs
     JNIEXPORT exports and RegisterNatives entries in cpp
  F. Asset references: assets.open/loadAsset literal paths

Every row carries file:line so each claim in the report can be re-verified
with rg. Outputs markdown-ish stdout + deep-trace-data.json.

Usage: python audit/tools/deep_trace.py
"""
from __future__ import annotations

import json
import re
import subprocess
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"

SRC_DIRS = ["app/src/main/java", "engine/src/main/java"]
MANIFESTS = ["app/src/main/AndroidManifest.xml", "engine/src/main/AndroidManifest.xml"]
CPP_DIRS = ["engine/src/main/cpp"]


def rel(p: Path) -> str:
    return p.relative_to(ROOT).as_posix().replace("\\", "/")


def iter_sources() -> list[Path]:
    out: list[Path] = []
    for d in SRC_DIRS + CPP_DIRS:
        base = ROOT / d
        if base.exists():
            out += [p for p in base.rglob("*") if p.suffix in {".kt", ".java", ".cpp", ".h"}]
    return sorted(out)


def scan_lines(text: str, pattern: str) -> list[tuple[int, str]]:
    hits = []
    for i, ln in enumerate(text.splitlines(), 1):
        m = re.search(pattern, ln)
        if m:
            hits.append((i, m))
    return hits


def main() -> None:
    sources = iter_sources()
    texts = {p: p.read_text(encoding="utf-8", errors="replace") for p in sources}

    # ---------- A. manifest components ----------
    components = []
    for mf in MANIFESTS:
        root = ET.parse(ROOT / mf).getroot()
        app = root.find("application")
        for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
            for el in app.findall(tag):
                name = el.get(f"{ANDROID}name", "?")
                proc = el.get(f"{ANDROID}process", ":main")
                exported = el.get(f"{ANDROID}exported", "-")
                components.append({
                    "manifest": mf,
                    "kind": tag,
                    "name": name.removeprefix("."),
                    "process": proc,
                    "exported": exported,
                })

    # ---------- B. hidden deps ----------
    patterns = {
        "System.loadLibrary": r'System\.loadLibrary\s*\(\s*"([^"]+)"',
        "System.load(abs)": r"System\.load\s*\(",
        "dlopen": r'\bdlopen\s*\(\s*"([^"]+)"',
        "dlsym": r"\bdlsym\s*\(",
        "Class.forName": r'Class\.forName\s*\(\s*"([^"]+)"',
        "FindClass(jni)": r'FindClass\s*\(\s*"([^"]+)"',
        "getDeclaredMethod": r"getDeclaredMethod\s*\(",
        "getDeclaredField": r"getDeclaredField\s*\(",
        "loadLibrary(SDL wrapper)": r"(?:SDL\.loadLibrary|\bloadLibrary)\s*\(\s*\"([^\"]+)\"",
    }
    hidden = defaultdict(list)
    for p, text in texts.items():
        for label, pat in patterns.items():
            for ln, m in scan_lines(text, pat):
                hidden[label].append({
                    "where": f"{rel(p)}:{ln}",
                    "module": rel(p).split("/")[0],
                    "arg": m.group(1) if m.groups() else "",
                })

    # ---------- C. intent extras ----------
    put_re = r'\bput(?:String|Int|Boolean|Long|Double|Float|Byte)?(?:ArrayList)?Extra\s*\(\s*"([^"]+)"'
    get_re = r'\bget(?:String|Int|Boolean|Long|Double|Float|Byte)?(?:ArrayList)?Extra\s*\(\s*"([^"]+)"'
    extras = defaultdict(lambda: {"app_put": [], "app_get": [], "engine_put": [], "engine_get": []})
    for p, text in texts.items():
        mod = rel(p).split("/")[0]
        for ln, m in scan_lines(text, put_re):
            extras[m.group(1)][f"{mod}_put"].append(f"{rel(p)}:{ln}")
        for ln, m in scan_lines(text, get_re):
            extras[m.group(1)][f"{mod}_get"].append(f"{rel(p)}:{ln}")

    def status(v: dict) -> str:
        ap, ep, ag, eg = bool(v["app_put"]), bool(v["engine_put"]), bool(v["app_get"]), bool(v["engine_get"])
        if ap and eg and not ag and not ep:
            return "app→engine"
        if ep and ag and not eg and not ap:
            return "engine→app"
        if ap and ag:
            return "app内部"
        if ep and eg:
            return "engine内部"
        if (ap or ep) and not (ag or eg):
            return "仅生产(疑似死键/原生消费)"
        return "混合"

    extras_rows = []
    for key in sorted(extras):
        v = extras[key]
        extras_rows.append({
            "key": key,
            "flow": status(v),
            "producers": v["app_put"] + v["engine_put"],
            "consumers": v["app_get"] + v["engine_get"],
        })

    # ---------- D. shared preferences ----------
    sp_re = r'getSharedPreferences\s*\(\s*"([^"]+)"'
    sp_use = defaultdict(list)
    for p, text in texts.items():
        for ln, m in scan_lines(text, sp_re):
            sp_use[m.group(1)].append(f"{rel(p)}:{ln}")
    KNOWN_WRITERS = {
        "game_scanner": "app",
        "yukihub_prefs": "app(写)/engine(读)",
        "onsyuri": "app(写)/engine(读)",
        "tyranor_game_overrides": "app",
        "app_settings": "app",
    }
    sp_rows = [
        {"file": f, "expected_owner": KNOWN_WRITERS.get(f, "??"), "access_points": locs}
        for f, locs in sorted(sp_use.items())
    ]

    # ---------- E. jni surface ----------
    kt_native, java_native = [], []
    cpp_exports, reg_natives = [], []
    for p, text in texts.items():
        r = rel(p)
        if r.endswith(".kt"):
            for ln, m in scan_lines(text, r"\bexternal\s+fun\s+(\w+)"):
                kt_native.append({"sym": m.group(1), "where": f"{r}:{ln}"})
        elif r.endswith(".java"):
            for ln, m in scan_lines(text, r"\bnative\s+[\w<>[\],.\s]+\s(\w+)\s*\("):
                java_native.append({"sym": m.group(1), "where": f"{r}:{ln}"})
        else:
            for ln, m in scan_lines(text, r"JNIEXPORT[^)]*JNICALL\s+(Java_[\w_]+)"):
                cpp_exports.append({"sym": m.group(1), "where": f"{r}:{ln}"})
            for ln, m in scan_lines(text, r'\{const_cast<char\*>\("(\w+)"\)'):
                reg_natives.append({"sym": m.group(1), "where": f"{r}:{ln}"})

    # ---------- F. assets ----------
    asset_refs = []
    for p, text in texts.items():
        for ln, m in scan_lines(text, r'(?:assets\.open|loadAsset)\s*\(\s*"([^"]+)"'):
            asset_refs.append({"path": m.group(1), "where": f"{rel(p)}:{ln}"})

    # ---------- emit ----------
    def dump(title: str, rows) -> None:
        print(f"\n### {title}")
        if isinstance(rows, list):
            for r in rows:
                print(json.dumps(r, ensure_ascii=False))
        else:
            print(json.dumps(rows, ensure_ascii=False, indent=1))

    print(f"# deep trace @ {subprocess.run(['git','rev-parse','--short','HEAD'],cwd=ROOT,capture_output=True,text=True).stdout.strip()}")

    print("\n### A. MANIFEST COMPONENTS")
    for c in components:
        print(f"{c['process']:<18} {c['kind']:<14} exp={c['exported']:<5} {c['name']}")

    print("\n### B. HIDDEN DEPENDENCIES")
    for label, rows in hidden.items():
        print(f"[{label}] n={len(rows)}")
        seen = set()
        for row in rows:
            sig = (row["arg"], row["where"])
            if sig in seen:
                continue
            seen.add(sig)
            print(f"   {row['where']}  ->  {row['arg']}")

    print("\n### C. INTENT EXTRAS MATRIX")
    for r in extras_rows:
        print(f"[{r['flow']:<22}] {r['key']}")
        print(f"     put : {' '.join(r['producers']) or '-'}")
        print(f"     get : {' '.join(r['consumers']) or '-'}")

    print("\n### D. SHARED PREFERENCES")
    for r in sp_rows:
        print(f"{r['file']:<24} owner={r['expected_owner']:<16} refs={len(r['access_points'])}")
        for a in r["access_points"]:
            print(f"     {a}")

    print("\n### E. JNI SURFACE")
    print("[kotlin external fun]")
    for n in kt_native:
        print(f"   {n['where']}  {n['sym']}")
    print("[java native methods] n=%d (sample)" % len(java_native))
    for n in java_native[:40]:
        print(f"   {n['where']}  {n['sym']}")
    print("[cpp JNIEXPORT]")
    for n in cpp_exports:
        print(f"   {n['where']}  {n['sym']}")
    print("[RegisterNatives names]")
    for n in reg_natives:
        print(f"   {n['where']}  {n['sym']}")

    print("\n### F. ASSET REFERENCES")
    for a in asset_refs:
        print(f"   {a['where']}  {a['path']}")

    out = ROOT / "audit" / "tools" / "deep-trace-data.json"
    out.write_text(json.dumps({
        "components": components,
        "hidden_deps": {k: v for k, v in hidden.items()},
        "intent_extras": extras_rows,
        "shared_prefs": sp_rows,
        "jni": {"kotlin_external": kt_native, "java_native": java_native,
                "cpp_exports": cpp_exports, "register_natives": reg_natives},
        "assets": asset_refs,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
