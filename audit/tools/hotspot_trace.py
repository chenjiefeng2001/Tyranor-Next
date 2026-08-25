#!/usr/bin/env python3
"""Static hotspot tracer for Tyranor-Next.

Computes per-file metrics over Kotlin/Java sources:
  - SLOC (non-blank lines)
  - function declarations
  - cyclone-ish complexity proxy (branch keywords)
  - fan-out  : distinct intra-project top-level symbols referenced by this file
  - fan-in   : number of other source files referencing this file's symbols
  - churn    : git commits touching the file (full history)

Composite HEAT score = 0.35*fan_in + 0.25*churn + 0.25*sloc + 0.15*fan_out
(all components min-max normalised).

Outputs:
  - stdout tables (top hotspots, package aggregates)
  - <repo>/audit/tools/hotspot-data.json
  - <repo>/audit/07-hotspot-map.svg

Usage: python audit/tools/hotspot_trace.py
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC_DIRS = ["app/src/main/java", "engine/src/main/java"]

SYM_DECL = re.compile(
    r"^\s*(?:public\s+|private\s+|internal\s+|protected\s+|static\s+|final\s+|abstract\s+|open\s+|sealed\s+)*"
    r"(?:class|interface|object|enum\s+class)\s+([A-Za-z_]\w*)",
    re.M,
)
FUN_DECL_KT = re.compile(r"^\s*(?:.*\bsuspend\s+)?fun\s+[<A-Za-z_]", re.M)
METHOD_DECL_JAVA = re.compile(
    r"^\s*(?:public|private|protected|static|final|native|synchronized)+[^;={}]*\([^;]*\)\s*(?:throws [\w., ]+)?\{",
    re.M,
)
BRANCH_KT = re.compile(r"\b(if|when|for|while|catch|try)\b")
BRANCH_JAVA = re.compile(r"\b(if|switch|for|while|catch|try)\b")


def list_sources() -> list[Path]:
    out: list[Path] = []
    for d in SRC_DIRS:
        base = ROOT / d
        if not base.exists():
            continue
        out += [p for p in base.rglob("*") if p.suffix in {".kt", ".java"}]
    return sorted(out)


def git_churn() -> dict[str, int]:
    res = subprocess.run(
        ["git", "log", "--name-only", "--pretty=format:"],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    counter: Counter[str] = Counter()
    for line in res.stdout.splitlines():
        line = line.strip().replace("\\", "/")
        if line:
            counter[line] += 1
    return counter


def norm(values: dict[Path, float]) -> dict[Path, float]:
    if not values:
        return {}
    lo, hi = min(values.values()), max(values.values())
    span = hi - lo or 1.0
    return {k: (v - lo) / span for k, v in values.items()}


def main() -> None:
    sources = list_sources()
    texts: dict[Path, str] = {}
    for p in sources:
        try:
            texts[p] = p.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            print(f"WARN read failed {p}: {exc}", file=sys.stderr)
            texts[p] = ""

    # ---- per-file raw metrics -------------------------------------------------
    sym_owner: dict[str, set[Path]] = defaultdict(set)
    meta: dict[Path, dict] = {}
    for p, text in texts.items():
        rel = p.relative_to(ROOT).as_posix()
        module = "app" if rel.startswith("app/") else "engine"
        pkg_m = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg_m.group(1) if pkg_m else "?"
        syms = {m.group(1) for m in SYM_DECL.finditer(text)}
        for s in syms:
            sym_owner[s].add(p)
        branches = len(BRANCH_KT.findall(text)) if p.suffix == ".kt" else len(BRANCH_JAVA.findall(text))
        funs = len(FUN_DECL_KT.findall(text)) if p.suffix == ".kt" else len(METHOD_DECL_JAVA.findall(text))
        meta[p] = {
            "path": rel,
            "module": module,
            "package": pkg,
            "sloc": sum(1 for ln in text.splitlines() if ln.strip()),
            "functions": funs,
            "branches": branches,
            "symbols": sorted(syms),
            "imports": len(re.findall(r"^\s*import\s+", text, re.M)),
        }

    # ---- fan-in / fan-out -----------------------------------------------------
    fan_in_files: dict[Path, set[Path]] = {p: set() for p in texts}
    fan_out_count: dict[Path, int] = {}
    for p, text in texts.items():
        refs: set[Path] = set()
        words = set(re.findall(r"\b[A-Z][A-Za-z0-9_]{3,}\b", text))
        for w in words:
            owners = sym_owner.get(w)
            if not owners:
                continue
            for owner in owners:
                if owner != p:
                    refs.add(owner)
                    fan_in_files[owner].add(p)
        fan_out_count[p] = len(refs)

    churn_raw = git_churn()

    rows: dict[Path, dict] = {}
    fin_n = norm({p: float(len(fan_in_files[p])) for p in texts})
    fout_n = norm({p: float(fan_out_count[p]) for p in texts})
    sloc_n = norm({p: float(meta[p]["sloc"]) for p in texts})
    churn_vals = {p: float(churn_raw.get(meta[p]["path"], 0)) for p in texts}
    churn_n = norm(churn_vals)

    for p in texts:
        m = meta[p]
        heat = 100 * (
            0.35 * fin_n[p] + 0.25 * churn_n[p] + 0.25 * sloc_n[p] + 0.15 * fout_n[p]
        )
        rows[p] = {
            **m,
            "fan_in": len(fan_in_files[p]),
            "fan_out": fan_out_count[p],
            "churn": int(churn_vals[p]),
            "heat": round(heat, 1),
        }

    ordered = sorted(rows.values(), key=lambda r: r["heat"], reverse=True)

    # ---- console report -------------------------------------------------------
    print(f"files={len(rows)} total_sloc={sum(r['sloc'] for r in rows.values())}")
    by_mod: dict[str, dict[str, int]] = defaultdict(lambda: {"files": 0, "sloc": 0})
    for r in rows.values():
        by_mod[r["module"]]["files"] += 1
        by_mod[r["module"]]["sloc"] += r["sloc"]
    print(json.dumps(by_mod, ensure_ascii=False))

    print("\n=== TOP 25 HOTSPOTS ===")
    print(f"{'heat':>5} {'fin':>4} {'fout':>4} {'churn':>5} {'sloc':>5} {'fun':>4} {'br':>4}  path")
    for r in ordered[:25]:
        print(f"{r['heat']:>5.1f} {r['fan_in']:>4} {r['fan_out']:>4} {r['churn']:>5} "
              f"{r['sloc']:>5} {r['functions']:>4} {r['branches']:>4}  {r['path']}")

    print("\n=== PACKAGE AGGREGATES (top 20 by mean heat) ===")
    pkgs: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in rows.values():
        pkgs[(r["module"], r["package"])].append(r)
    pkg_rows = []
    for (mod, pkg), items in pkgs.items():
        pkg_rows.append({
            "module": mod, "package": pkg, "files": len(items),
            "sloc": sum(i["sloc"] for i in items),
            "mean_heat": round(sum(i["heat"] for i in items) / len(items), 1),
            "max_heat": max(i["heat"] for i in items),
        })
    for pr in sorted(pkg_rows, key=lambda x: x["mean_heat"], reverse=True)[:20]:
        print(f"{pr['mean_heat']:>6.1f} {pr['max_heat']:>6.1f} f={pr['files']:>3} sloc={pr['sloc']:>6}  {pr['module']}/{pr['package']}")

    # ---- artifacts ------------------------------------------------------------
    data = {
        "generated_for_commit": subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
            capture_output=True, text=True).stdout.strip(),
        "weights": {"fan_in": 0.35, "churn": 0.25, "sloc": 0.25, "fan_out": 0.15},
        "files": [rows[p] for p in sorted(rows, key=lambda q: q.as_posix())],
    }
    out_json = ROOT / "audit" / "tools" / "hotspot-data.json"
    out_json.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")

    render_svg(ordered)
    print(f"\nwrote {out_json}")
    print(f"wrote {ROOT / 'audit' / '07-hotspot-map.svg'}")


def heat_color(score: float) -> str:
    """0..100 -> blue-green-yellow-red ramp."""
    stops = [(0, "#2c7bb6"), (35, "#abd9e9"), (55, "#ffffbf"),
             (75, "#fdae61"), (90, "#d7191c")]
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        if score <= b:
            t = (score - a) / (b - a)
            pa = [int(ca[i:i + 2], 16) for i in (1, 3, 5)]
            pb = [int(cb[i:i + 2], 16) for i in (1, 3, 5)]
            rgb = tuple(round(pa[i] + (pb[i] - pa[i]) * t) for i in range(3))
            return f"#{rgb[0]:02x}{rgb[1]:02x}{rgb[2]:02x}"
    return stops[-1][1]


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def render_svg(ordered: list[dict]) -> None:
    cols_per_row = 16
    cell = 26
    label_w = 330
    margin_x, margin_y = 24, 96
    row_gap = 34

    groups: list[tuple[str, list[dict]]] = []
    key_fn = lambda r: (r["module"], r["package"])
    current_key, bucket = None, []
    for r in ordered:
        k = key_fn(r)
        if k != current_key and bucket:
            groups.append((current_key, bucket))
            bucket = []
        current_key = k
        bucket.append(r)
    if bucket:
        groups.append((current_key, bucket))

    width = label_w + cols_per_row * cell + margin_x * 2
    height = margin_y + len(groups) * row_gap + 60
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" font-family="Consolas,monospace">',
        f'<rect width="{width}" height="{height}" fill="#111418"/>',
        '<text x="24" y="36" fill="#e8e8e8" font-size="20" font-weight="bold">'
        'Tyranor-Next code hotspot map (static trace)</text>',
        '<text x="24" y="58" fill="#9aa4ad" font-size="12">'
        'cell = file, sorted left-to-right by heat within each package group; '
        'hover a cell for metrics</text>',
    ]
    legend = [("0–34 low", 30), ("35–54 moderate", 45), ("55–74 elevated", 65),
              ("75–89 high", 82), ("90–100 critical", 95)]
    lx = 24
    y = 74
    for label, sample in legend:
        parts.append(f'<rect x="{lx}" y="{y - 10}" width="14" height="12" rx="2" fill="{heat_color(sample)}"/>')
        parts.append(f'<text x="{lx + 18}" y="{y}" fill="#cfd6dc" font-size="11">{esc(label)}</text>')
        lx += 150

    gy = margin_y
    for (mod, pkg), items in groups:
        short = pkg.replace("com.tyranor.next.", "…").replace("com.core.", "…").replace("org.libsdl3.app", "libsdl3").replace("org.libsdl.app", "libsdl2")
        parts.append(
            f'<text x="{margin_x}" y="{gy + 17}" fill="#8fb4d9" font-size="13">{esc(mod)} · {esc(short)} '
            f'<tspan fill="#66707a">({len(items)})</tspan></text>')
        for idx, r in enumerate(items):
            col, rowi = divmod(idx, cols_per_row)
            x = margin_x + label_w - 300 + col * cell
            yy = gy + rowi * cell
            color = heat_color(r["heat"])
            tip = (f"{r['path']}\nheat={r['heat']} fan-in={r['fan_in']} fan-out={r['fan_out']} "
                   f"churn={r['churn']} sloc={r['sloc']} fun={r['functions']} branch={r['branches']}")
            name = r["path"].rsplit("/", 1)[-1]
            short_name = name if len(name) <= 22 else name[:21] + "…"
            parts.append(
                f'<g><title>{esc(tip)}</title>'
                f'<rect x="{x}" y="{yy}" width="{cell - 4}" height="{cell - 4}" rx="4" '
                f'fill="{color}" stroke="#000" stroke-opacity="0.35"/>'
                f'<text x="{x + 2}" y="{yy + 15}" font-size="9" fill="#101010" opacity="0.85">'
                f'{esc(short_name[:9])}</text></g>')
        gy += row_gap + ((len(items) - 1) // cols_per_row + 1) * cell - cell + 8

    parts.append("</svg>")
    out_svg = ROOT / "audit" / "07-hotspot-map.svg"
    out_svg.write_text("\n".join(parts), encoding="utf-8")


if __name__ == "__main__":
    main()
