"""
Tromp track recovery utility — break-glass tool for reconstructing a
session from `autostop.log` when the in-DB `track_point` rows are
missing or corrupt.

Background: Tromp's DebugLog writes every accepted location fix to
`Android/data/com.comtekglobal.tromp/files/autostop.log` with
lat/lon/speed/accuracy/altitude. That's a 2 MB rolling buffer (older
entries get truncated). Within the buffer, this script can rebuild a
GPX (universal track format) and a CSV matching the in-app CsvWriter
column shape. Step counts, pressure, bearing, and the GPS-vs-baro
altitude split aren't logged — those columns emit blank.

Originally written 2026-05-04 to recover a hike lost to the v1.14
persistActivity race (fixed in v1.14.1). Kept in-tree so future
break-glass moments don't need it rewritten.

Pull the log first:
  adb pull /sdcard/Android/data/com.comtekglobal.tromp/files/autostop.log

Then run:
  python scripts/recover_track_from_log.py autostop.log [out_prefix]

Outputs `<out_prefix>.gpx` and `<out_prefix>.csv` in the current dir.
If the log contains multiple sessions (a startTracking/stopTracking
pair each), files are suffixed `_1`, `_2`, etc.
"""
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

LINE_RE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) "
    r"(?P<tag>SVC|FIX) (?P<rest>.*)$"
)
FIX_RE = re.compile(
    r"lat=(?P<lat>-?\d+\.\d+) lon=(?P<lon>-?\d+\.\d+) "
    r"spd=(?P<spd>-?\d+\.\d+) acc=(?P<acc>-?\d+\.\d+) "
    r"alt=(?P<alt>-?\d+(?:\.\d+)?|NaN) "
    r"autoPaused=(?P<paused>true|false)"
)
START_RE = re.compile(r"^startTracking type=(?P<type>\S+) qnh=(?P<qnh>\S+)$")
STOP_RE = re.compile(r"^stopTracking trimAfterMs=(?P<trim>\S+)$")


def parse_local_ts(s: str) -> datetime:
    # The log writes timestamps in the device's local timezone with no
    # offset suffix. We treat them as naive local time; for ISO output we
    # tag them with the running machine's local offset, which is the best
    # we can do without knowing the device's TZ at log time.
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S.%f").astimezone()


def haversine_m(a_lat, a_lon, b_lat, b_lon):
    from math import radians, sin, cos, asin, sqrt
    R = 6371000.0
    dlat = radians(b_lat - a_lat)
    dlon = radians(b_lon - a_lon)
    h = sin(dlat / 2) ** 2 + cos(radians(a_lat)) * cos(radians(b_lat)) * sin(dlon / 2) ** 2
    return 2 * R * asin(sqrt(h))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    log_path = Path(sys.argv[1])
    out_prefix = sys.argv[2] if len(sys.argv) > 2 else "recovered_track"

    sessions = []
    current = None
    with log_path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = LINE_RE.match(line.rstrip("\n"))
            if not m:
                continue
            ts = parse_local_ts(m["ts"])
            rest = m["rest"]
            if m["tag"] == "SVC":
                if (sm := START_RE.match(rest)):
                    current = {
                        "start": ts,
                        "type": sm["type"],
                        "qnh": sm["qnh"],
                        "stop": None,
                        "trim_after_ms": None,
                        "fixes": [],
                    }
                    sessions.append(current)
                elif (em := STOP_RE.match(rest)) and current is not None:
                    current["stop"] = ts
                    trim_str = em["trim"]
                    current["trim_after_ms"] = int(trim_str) if trim_str.lstrip("-").isdigit() else None
                    current = None
            elif m["tag"] == "FIX" and current is not None:
                fm = FIX_RE.match(rest)
                if not fm:
                    continue
                alt_raw = fm["alt"]
                alt = None if alt_raw == "NaN" else float(alt_raw)
                current["fixes"].append({
                    "ts": ts,
                    "lat": float(fm["lat"]),
                    "lon": float(fm["lon"]),
                    "spd": float(fm["spd"]),
                    "acc": float(fm["acc"]),
                    "alt": alt,
                    "auto_paused": fm["paused"] == "true",
                })

    if not sessions:
        print("No SVC startTracking events found in log.")
        sys.exit(1)

    for idx, s in enumerate(sessions):
        suffix = "" if len(sessions) == 1 else f"_{idx + 1}"
        write_gpx(s, Path(f"{out_prefix}{suffix}.gpx"))
        write_csv(s, Path(f"{out_prefix}{suffix}.csv"))
        write_summary(s, idx)


def write_summary(s, idx):
    fixes = s["fixes"]
    if not fixes:
        print(f"[session {idx + 1}] start={s['start']} no fixes captured")
        return
    total_m = 0.0
    for a, b in zip(fixes, fixes[1:]):
        if a["auto_paused"] or b["auto_paused"]:
            continue
        total_m += haversine_m(a["lat"], a["lon"], b["lat"], b["lon"])
    elapsed = (fixes[-1]["ts"] - fixes[0]["ts"]).total_seconds()
    print(
        f"[session {idx + 1}] start={s['start']} stop={s['stop']} "
        f"type={s['type']} fixes={len(fixes)} "
        f"distance={total_m:.1f} m ({total_m / 1609.344:.2f} mi) "
        f"elapsed={elapsed / 60:.1f} min"
    )


def write_gpx(s, path: Path):
    fixes = s["fixes"]
    name = f"Tromp recovered {s['start'].strftime('%Y-%m-%d %H:%M')}"
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="recover_track_from_log.py" '
        'xmlns="http://www.topografix.com/GPX/1/1">',
        f"  <metadata><name>{xml_escape(name)}</name>"
        f"<time>{s['start'].astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}</time></metadata>",
        f"  <trk><name>{xml_escape(name)}</name><trkseg>",
    ]
    for p in fixes:
        if p["auto_paused"]:
            continue
        utc = p["ts"].astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        ele = f"<ele>{p['alt']:.1f}</ele>" if p["alt"] is not None else ""
        lines.append(
            f'    <trkpt lat="{p["lat"]:.7f}" lon="{p["lon"]:.7f}">'
            f"{ele}<time>{utc}</time></trkpt>"
        )
    lines += ["  </trkseg></trk>", "</gpx>"]
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {path}")


def write_csv(s, path: Path):
    fixes = s["fixes"]
    # Column shape mirrors the in-app CsvWriter so a recovered track and a
    # natively-exported one are interchangeable in Excel. The log doesn't
    # capture pressure, bearing, gps-alt-vs-baro split, or step counts, so
    # those columns stay blank for recovered tracks.
    cols = [
        "seq", "time_utc", "time_local", "tMs",
        "lat", "lon", "alt_m", "gps_alt_m",
        "pressure_hpa", "horiz_acc_m",
        "speed_mps", "bearing_deg",
        "step_count", "is_auto_paused",
        "dist_from_prev_m", "dt_from_prev_s",
        "bearing_change_deg", "steps_delta",
        "stride_m_per_step", "cadence_spm",
        "vertical_rate_mps",
    ]
    with path.open("w", encoding="utf-8", newline="") as f:
        f.write(f"# Tromp recovered track (from autostop.log)\n")
        f.write(f"# start_local,{s['start'].isoformat()}\n")
        f.write(f"# stop_local,{s['stop'].isoformat() if s['stop'] else ''}\n")
        f.write(f"# type,{s['type']}\n")
        f.write(f"# qnh_hpa,{s['qnh']}\n")
        f.write(f"# trim_after_ms,{s['trim_after_ms'] if s['trim_after_ms'] is not None else ''}\n")
        f.write(f"# fixes,{len(fixes)}\n")
        f.write("#\n")
        f.write(",".join(cols) + "\n")
        prev = None
        for i, p in enumerate(fixes):
            ts_ms = int(p["ts"].timestamp() * 1000)
            utc = p["ts"].astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            local = p["ts"].strftime("%Y-%m-%d %H:%M:%S")
            if prev is None:
                dist, dt_s, vrate = "", "", ""
            else:
                dist_m = haversine_m(prev["lat"], prev["lon"], p["lat"], p["lon"])
                dt = (p["ts"] - prev["ts"]).total_seconds()
                dist = f"{dist_m:.3f}"
                dt_s = f"{dt:.3f}"
                if dt > 0 and p["alt"] is not None and prev["alt"] is not None:
                    vrate = f"{(p['alt'] - prev['alt']) / dt:.3f}"
                else:
                    vrate = ""
            row = [
                str(i),
                utc,
                local,
                str(ts_ms),
                f"{p['lat']:.7f}",
                f"{p['lon']:.7f}",
                f"{p['alt']:.3f}" if p["alt"] is not None else "",
                "",                       # gps_alt_m — log doesn't split baro vs GPS
                "",                       # pressure_hpa — not logged
                f"{p['acc']:.2f}",
                f"{p['spd']:.3f}",
                "",                       # bearing_deg — not logged
                "",                       # step_count — not logged
                "1" if p["auto_paused"] else "0",
                dist,
                dt_s,
                "",                       # bearing_change_deg — bearing not logged
                "",                       # steps_delta — step_count not logged
                "",                       # stride_m_per_step — needs steps
                "",                       # cadence_spm — needs steps
                vrate,
            ]
            f.write(",".join(row) + "\n")
            prev = p
    print(f"wrote {path}")


def xml_escape(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;")
             .replace(">", "&gt;").replace('"', "&quot;"))


if __name__ == "__main__":
    main()