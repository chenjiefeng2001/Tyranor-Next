#!/usr/bin/env python3
"""Emulator telemetry collector for Tyranor-Next (audit/11).

Phases:
  install      (re)install debug APK and CLEAR app data
  firstlaunch  single cold start on fresh data (includes native-plugin provisioning IO)
  cold         N force-stop -> am start -W cycles, collect TotalTime/WaitTime/LaunchState
  mem          dumpsys meminfo snapshot (summary)
  ui           bottom-nav tab traversal via uiautomator bounds + gfxinfo jank stats
  perfetto     capture a system trace around one cold start, pull .pftrace

Usage:
  python audit/tools/emulator_telemetry.py --serial emulator-5554 --phase all
Outputs:
  audit/artifacts/telemetry.json
  audit/artifacts/perfetto-coldstart.pftrace (when phase perfetto runs)
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / "audit" / "artifacts"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PKG = "com.tyranor.next"
MAIN_ACT = f"{PKG}/com.tyranor.next.MainActivity"


def _resolve_adb() -> str:
    env = os.environ.get("ADB")
    if env:
        return env
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        lp = ROOT / "local.properties"
        if lp.exists():
            m = re.search(r"sdk\.dir=(.+)", lp.read_text(encoding="utf-8"))
            if m:
                sdk = m.group(1).replace("\\:", ":").strip()
    cand = Path(sdk) / "platform-tools" / "adb.exe" if sdk else Path("adb")
    return str(cand) if cand.exists() else "adb"


ADB = _resolve_adb()


def adb(serial: str, *args: str, timeout: int = 120) -> str:
    res = subprocess.run(
        [ADB, "-s", serial, *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
    )
    return res.stdout


def start_w(serial: str) -> dict:
    out = adb(serial, "shell", "am", "start", "-W", "-n", MAIN_ACT)
    fields = {}
    for key in ("TotalTime", "WaitTime", "LaunchState"):
        m = re.search(rf"{key}:\s*(\w+)", out)
        if m:
            fields[key] = int(m.group(1)) if m.group(1).isdigit() else m.group(1)
    return fields


def phase_install(serial: str) -> None:
    print(adb(serial, "install", "-r", str(APK)).strip().splitlines()[-1])
    print("pm clear:", adb(serial, "shell", "pm", "clear", PKG).strip())


def phase_firstlaunch(serial: str, results: dict) -> None:
    results["firstLaunch"] = start_w(serial)
    time.sleep(3)
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME")


def phase_cold(serial: str, n: int, results: dict) -> None:
    samples = []
    for _ in range(n):
        adb(serial, "shell", "am", "force-stop", PKG)
        time.sleep(2)
        samples.append(start_w(serial))
        time.sleep(2)
    results["coldStarts"] = samples
    totals = [s["TotalTime"] for s in samples if "TotalTime" in s]
    if totals:
        results["coldStartSummary"] = {
            "n": len(totals),
            "median": statistics.median(totals),
            "min": min(totals),
            "max": max(totals),
            "stdev": round(statistics.stdev(totals), 1) if len(totals) > 1 else 0,
        }


def phase_mem(serial: str, results: dict) -> None:
    out = adb(serial, "shell", "dumpsys", "meminfo", PKG)
    keep = {}
    for pat, key in [
        (r"TOTAL PSS:\s+(\d+)", "totalPssKb"),
        (r"Java Heap:\s+(\d+)", "javaHeapKb"),
        (r"Native Heap:\s+(\d+)", "nativeHeapKb"),
        (r"Code:\s+(\d+)", "codeKb"),
        (r"Graphics:\s+(\d+)", "graphicsKb"),
    ]:
        m = re.search(pat, out)
        if m:
            keep[key] = int(m.group(1))
    results["meminfo"] = keep


def _bounds_of(text_labels: list[str]) -> tuple[int, int] | None:
    dump = adb_serial_dump()
    for label in text_labels:
        m = re.search(
            r'<node[^>]*text="' + label + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump
        )
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


DUMP_CACHE = ""


def adb_serial_dump() -> str:
    global DUMP_CACHE
    return DUMP_CACHE


def phase_ui(serial: str, rounds: int, results: dict) -> None:
    global DUMP_CACHE
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    start_w(serial)
    time.sleep(4)

    adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "reset")
    janky = {}
    tabs = [["首页"], ["游戏"], ["书库"], ["设置"]]
    for r in range(rounds):
        for t in tabs:
            DUMP_CACHE = adb(serial, "shell", "uiautomator", "dump", "/dev/tty").replace("\\r", "")
            # uiautomator dump to tty may wrap; fallback to file pull
            if "<hierarchy" not in DUMP_CACHE:
                adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
                DUMP_CACHE = adb(serial, "shell", "cat", "/sdcard/ui.xml")
            pos = _bounds_of(t)
            if pos:
                adb(serial, "shell", "input", "tap", str(pos[0]), str(pos[1]))
                time.sleep(1.6)
    out = adb(serial, "shell", "dumpsys", "gfxinfo", PKG)
    m_total = re.search(r"Total frames rendered: (\d+)", out)
    m_janky = re.search(r"Janky frames: (\d+) \(([\d.]+)%\)", out)
    p50 = re.search(r"50th percentile: (\d+)ms", out)
    p90 = re.search(r"90th percentile: (\d+)ms", out)
    p95 = re.search(r"95th percentile: (\d+)ms", out)
    results["uiTraversal"] = {
        "rounds": rounds,
        "totalFrames": int(m_total.group(1)) if m_total else None,
        "jankyFrames": int(m_janky.group(1)) if m_janky else None,
        "jankyPct": float(m_janky.group(2)) if m_janky else None,
        "frameMsP50": int(p50.group(1)) if p50 else None,
        "frameMsP90": int(p90.group(1)) if p90 else None,
        "frameMsP95": int(p95.group(1)) if p95 else None,
    }
    janky.update(results["uiTraversal"])


def phase_perfetto(serial: str, seconds: int, results: dict) -> None:
    ART.mkdir(parents=True, exist_ok=True)
    remote = "/data/misc/perfetto-traces/tyranor-cold.pftrace"
    categories = "sched freq idle am activity view dalvik binder_driver"
    proc = subprocess.Popen(
        [ADB, "-s", serial, "shell", f"perfetto -o {remote} -t {seconds}s {categories}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    time.sleep(3)
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(2)
    start_w(serial)
    time.sleep(max(3, seconds - 8))
    proc.wait(timeout=seconds + 30)
    adb(serial, "pull", remote, str(ART / "perfetto-coldstart.pftrace"))
    size = (ART / "perfetto-coldstart.pftrace").stat().st_size
    results["perfetto"] = {"file": "audit/artifacts/perfetto-coldstart.pftrace", "bytes": size}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--phase", default="all",
                    choices=["install", "firstlaunch", "cold", "mem", "ui", "perfetto", "all"])
    ap.add_argument("--cold-n", type=int, default=5)
    ap.add_argument("--ui-rounds", type=int, default=3)
    args = ap.parse_args()

    results: dict = {"serial": args.serial, "apk": str(APK.name)}
    todo = [args.phase] if args.phase != "all" else [
        "install", "firstlaunch", "cold", "mem", "ui", "perfetto",
    ]
    for ph in todo:
        print(f"== phase {ph} ==")
        if ph == "install":
            phase_install(args.serial)
        elif ph == "firstlaunch":
            phase_firstlaunch(args.serial, results)
        elif ph == "cold":
            phase_cold(args.serial, args.cold_n, results)
        elif ph == "mem":
            phase_mem(args.serial, results)
        elif ph == "ui":
            phase_ui(args.serial, args.ui_rounds, results)
        elif ph == "perfetto":
            phase_perfetto(args.serial, 14, results)

    ART.mkdir(parents=True, exist_ok=True)
    out = ART / "telemetry.json"
    existing = {}
    if out.exists() and args.phase != "all":
        existing = json.loads(out.read_text(encoding="utf-8"))
    existing.update(results)
    out.write_text(json.dumps(existing, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(existing, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
