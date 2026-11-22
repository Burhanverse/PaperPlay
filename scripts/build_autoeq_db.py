#!/usr/bin/env python3
"""
Build the offline FTS5 AutoEQ SQLite database (`autoeq.db`) from jaakkopasanen/AutoEq (MIT).

Usage:
    python3 scripts/build_autoeq_db.py --out androidApp/src/main/assets/autoeq.db --copy core/src/jvmMain/resources/autoeq.db
    python3 scripts/build_autoeq_db.py --source-dir /path/to/AutoEq --out autoeq.db
"""

import argparse
import io
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
from pathlib import Path

AUTOEQ_REPO_URL = "https://github.com/jaakkopasanen/AutoEq.git"
AUTOEQ_TARBALL_URL = "https://codeload.github.com/jaakkopasanen/AutoEq/tar.gz/refs/heads/master"

PREAMP_RE = re.compile(r"^\s*Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*(?:dB)?\s*$", re.IGNORECASE)
FILTER_RE = re.compile(
    r"^\s*Filter(?:\s+\d+)?\s*:\s*(ON|OFF)\s+([A-Za-z_]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*(?:Hz)?"
    r"(?:\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*(?:dB)?)?"
    r"(?:\s+Q\s+(\d+(?:\.\d+)?))?",
    re.IGNORECASE,
)

KIND_MAP = {
    "PK": 0, "PEQ": 0,
    "LSC": 1, "LS": 1,
    "HSC": 2, "HS": 2,
    "LP": 3, "LPQ": 3,
    "HP": 4, "HPQ": 4,
    "BP": 5, "BPQ": 5,
}


def fmt_num(val: float) -> str:
    r = round(val, 3)
    if r == int(r):
        return str(int(r))
    return f"{r:g}"


def normalize_form(raw_form: str) -> str:
    low = raw_form.lower()
    if "in-ear" in low or "iem" in low:
        return "in-ear"
    if "earbud" in low:
        return "earbud"
    if "over-ear" in low or "on-ear" in low or "headphone" in low:
        return "over-ear"
    return raw_form.strip()


def parse_parametric_txt(text: str):
    preamp = 0.0
    bands = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        pm = PREAMP_RE.match(line)
        if pm:
            preamp = max(-24.0, min(24.0, float(pm.group(1))))
            continue
        fm = FILTER_RE.match(line)
        if not fm:
            continue
        enabled = 1 if fm.group(1).upper() == "ON" else 0
        kind = KIND_MAP.get(fm.group(2).upper())
        if kind is None:
            continue
        fc = max(10.0, min(24000.0, float(fm.group(3))))
        gain = max(-24.0, min(24.0, float(fm.group(4)))) if fm.group(4) is not None else 0.0
        q = max(0.1, min(20.0, float(fm.group(5)))) if fm.group(5) is not None else (1.41 if kind == 0 else 0.707)
        bands.append(f"{kind},0,{enabled},{fmt_num(fc)},{fmt_num(gain)},{fmt_num(q)}")
        if len(bands) >= 31:
            break
    if not bands:
        return None
    return preamp, ";".join(bands)


def iter_from_tar_stream(tar_bytes: bytes):
    with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r:gz") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            name = member.name
            if not name.endswith(" ParametricEQ.txt"):
                continue
            parts = name.split("/")
            # Expected: AutoEq-master/results/<source>/<form>/<model>/<model> ParametricEQ.txt
            try:
                res_idx = parts.index("results")
            except ValueError:
                continue
            rel = parts[res_idx + 1:]
            if len(rel) < 4:
                continue
            source = rel[0]
            raw_form = rel[1]
            model = rel[-2]
            raw_path = "/".join(rel)
            f = tf.extractfile(member)
            if f is None:
                continue
            text = f.read().decode("utf-8", errors="replace")
            yield model, source, normalize_form(raw_form), raw_path, text


def iter_from_dir(source_dir: Path):
    results_dir = source_dir / "results" if (source_dir / "results").is_dir() else source_dir
    for path in results_dir.rglob("* ParametricEQ.txt"):
        rel = path.relative_to(results_dir).parts
        if len(rel) < 4:
            continue
        source = rel[0]
        raw_form = rel[1]
        model = rel[-2]
        raw_path = "/".join(rel)
        text = path.read_text(encoding="utf-8", errors="replace")
        yield model, source, normalize_form(raw_form), raw_path, text


def build_db(entries, out_path: Path):
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if out_path.exists():
        out_path.unlink()
    conn = sqlite3.connect(str(out_path))
    try:
        conn.execute("PRAGMA page_size = 4096;")
        conn.execute("PRAGMA journal_mode = OFF;")
        conn.execute("PRAGMA synchronous = OFF;")
        conn.executescript(
            """
            CREATE TABLE autoeq_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE autoeq_entries (
                id INTEGER PRIMARY KEY,
                model TEXT NOT NULL,
                source TEXT NOT NULL,
                form TEXT NOT NULL,
                preamp_db REAL NOT NULL,
                bands_blob TEXT NOT NULL,
                raw_path TEXT NOT NULL
            );
            CREATE VIRTUAL TABLE autoeq_fts USING fts5(
                model,
                source,
                form,
                content='autoeq_entries',
                content_rowid='id',
                tokenize='unicode61 remove_diacritics 1'
            );
            CREATE INDEX idx_autoeq_source ON autoeq_entries(source);
            CREATE INDEX idx_autoeq_form ON autoeq_entries(form);
            """
        )
        rows = []
        seen = set()
        for model, source, form, raw_path, text in entries:
            key = (model.lower(), source.lower(), form.lower())
            if key in seen:
                continue
            parsed = parse_parametric_txt(text)
            if parsed is None:
                continue
            seen.add(key)
            preamp, blob = parsed
            rows.append((model, source, form, preamp, blob, raw_path))
        rows.sort(key=lambda r: (r[0].lower(), r[1].lower()))
        conn.executemany(
            "INSERT INTO autoeq_entries(model, source, form, preamp_db, bands_blob, raw_path) VALUES (?, ?, ?, ?, ?, ?)",
            rows,
        )
        conn.execute("INSERT INTO autoeq_fts(autoeq_fts) VALUES('rebuild');")
        conn.execute("INSERT INTO autoeq_fts(autoeq_fts) VALUES('optimize');")
        conn.executemany(
            "INSERT INTO autoeq_meta(key, value) VALUES (?, ?)",
            [
                ("schema_version", "1"),
                ("entry_count", str(len(rows))),
                ("built_at", str(int(time.time()))),
                ("source_repo", "jaakkopasanen/AutoEq"),
                ("license", "MIT"),
            ],
        )
        conn.commit()
        conn.execute("VACUUM;")
        return len(rows)
    finally:
        conn.close()


def iter_from_sparse_git(tmp_dir: Path):
    repo_dir = tmp_dir / "AutoEq"
    subprocess.run(
        ["git", "clone", "--depth", "1", "--filter=blob:none", "--no-checkout", AUTOEQ_REPO_URL, str(repo_dir)],
        check=True,
    )
    subprocess.run(
        ["git", "-C", str(repo_dir), "sparse-checkout", "set", "--no-cone", "results/**/*ParametricEQ.txt"],
        check=True,
    )
    subprocess.run(["git", "-C", str(repo_dir), "checkout"], check=True)
    yield from iter_from_dir(repo_dir)


def main():
    ap = argparse.ArgumentParser(description="Build PaperPlay offline AutoEQ FTS5 SQLite DB")
    ap.add_argument("--source-dir", type=Path, default=None, help="Local jaakkopasanen/AutoEq repo checkout")
    ap.add_argument("--out", type=Path, required=True, help="Output SQLite DB path")
    ap.add_argument("--copy", type=Path, action="append", default=[], help="Additional output copy paths")
    args = ap.parse_args()

    t0 = time.time()
    if args.source_dir is not None:
        print(f"Scanning local AutoEq checkout: {args.source_dir}")
        count = build_db(iter_from_dir(args.source_dir), args.out)
    elif shutil.which("git"):
        print(f"Fetching ParametricEQ.txt files via sparse git checkout from {AUTOEQ_REPO_URL} ...")
        with tempfile.TemporaryDirectory(prefix="autoeq-build-") as td:
            count = build_db(iter_from_sparse_git(Path(td)), args.out)
    else:
        print(f"Downloading AutoEq archive from {AUTOEQ_TARBALL_URL} ...")
        req = urllib.request.Request(AUTOEQ_TARBALL_URL, headers={"User-Agent": "PaperPlay-AutoEq-Builder/1.0"})
        with urllib.request.urlopen(req, timeout=180) as resp:
            tar_bytes = resp.read()
        print(f"Downloaded {len(tar_bytes) / (1024 * 1024):.2f} MiB in {time.time() - t0:.1f}s")
        count = build_db(iter_from_tar_stream(tar_bytes), args.out)

    size_kb = args.out.stat().st_size / 1024.0
    print(f"Built {args.out} ({count} profiles, {size_kb:.1f} KiB) in {time.time() - t0:.1f}s")

    for cp in args.copy:
        cp.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(args.out, cp)
        print(f"Copied to {cp}")


if __name__ == "__main__":
    main()
