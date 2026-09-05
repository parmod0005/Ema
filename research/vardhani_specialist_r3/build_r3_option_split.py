#!/usr/bin/env python3
"""Project the parity-rebuilt AIML corpus into the stricter VARDHANI R3 chronology.

Original AIML split labels are preserved only as provenance fields. R3 learning authority is
calendar-locked: <=2024-12-31 train, 2025 validation only, 2026 sealed/excluded.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

TRAIN_CUTOFF = "2024-12-31"
VALIDATION_START = "2025-01-01"
VALIDATION_END = "2025-12-31"
ROW_SCHEMA = "aiml-historical-option-row-v1"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def iter_rows(paths: list[Path]):
    for source in paths:
        source_split = source.stem
        with source.open("r", encoding="utf-8") as fh:
            for line_no, raw in enumerate(fh, 1):
                if not raw.strip():
                    continue
                row = json.loads(raw)
                if row.get("schema") != ROW_SCHEMA:
                    raise RuntimeError(f"{source}:{line_no}: row schema mismatch")
                if row.get("execution_authority") is not False:
                    raise RuntimeError(f"{source}:{line_no}: execution authority must be false")
                yield source_split, row


def build(corpus_dir: Path, output_dir: Path) -> dict:
    inputs = [corpus_dir / "train.ndjson", corpus_dir / "validation.ndjson", corpus_dir / "test.ndjson"]
    for p in inputs:
        if not p.is_file():
            raise FileNotFoundError(p)

    output_dir.mkdir(parents=True, exist_ok=True)
    train_path = output_dir / "options_train_through_2024.ndjson"
    validation_path = output_dir / "options_validation_2025.ndjson"

    counters = {
        "source_rows": 0,
        "train_rows": 0,
        "validation_rows": 0,
        "sealed_2026_rows_rejected": 0,
        "outside_contract_rows_rejected": 0,
    }
    source_split_counts: dict[str, int] = {}
    train_dates: set[str] = set()
    validation_dates: set[str] = set()
    train_min = train_max = None
    val_min = val_max = None

    with train_path.open("w", encoding="utf-8", newline="\n") as train_out, validation_path.open("w", encoding="utf-8", newline="\n") as val_out:
        for source_split, row in iter_rows(inputs):
            counters["source_rows"] += 1
            source_split_counts[source_split] = source_split_counts.get(source_split, 0) + 1
            date = str(row.get("exchange_date", ""))
            if len(date) != 10:
                raise RuntimeError("missing/invalid exchange_date")

            projected = dict(row)
            projected["aiml_original_split"] = row.get("split", source_split)

            if date <= TRAIN_CUTOFF:
                projected["split"] = "r3_train"
                train_out.write(json.dumps(projected, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")
                counters["train_rows"] += 1
                train_dates.add(date)
                train_min = date if train_min is None or date < train_min else train_min
                train_max = date if train_max is None or date > train_max else train_max
            elif VALIDATION_START <= date <= VALIDATION_END:
                projected["split"] = "r3_validation"
                val_out.write(json.dumps(projected, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n")
                counters["validation_rows"] += 1
                validation_dates.add(date)
                val_min = date if val_min is None or date < val_min else val_min
                val_max = date if val_max is None or date > val_max else val_max
            elif date >= "2026-01-01":
                counters["sealed_2026_rows_rejected"] += 1
            else:
                counters["outside_contract_rows_rejected"] += 1

    report = {
        "format": "VARDHANI_SPECIALIST_R3_SPLIT_REPORT_V1",
        "source_files": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in inputs},
        "source_split_counts": source_split_counts,
        "configured_train_cutoff": TRAIN_CUTOFF,
        "configured_validation_start": VALIDATION_START,
        "configured_validation_end": VALIDATION_END,
        "train_min_exchange_date": train_min,
        "train_max_exchange_date": train_max,
        "validation_min_exchange_date": val_min,
        "validation_max_exchange_date": val_max,
        "train_unique_exchange_dates": len(train_dates),
        "validation_unique_exchange_dates": len(validation_dates),
        "contains_2025_training_rows": False,
        "contains_2026_training_rows": False,
        "contains_2026_validation_rows": False,
        "sealed_2026": True,
        "counters": counters,
        "outputs": {
            train_path.name: {"bytes": train_path.stat().st_size, "sha256": sha256(train_path)},
            validation_path.name: {"bytes": validation_path.stat().st_size, "sha256": sha256(validation_path)},
        },
        "execution_authority": False,
    }
    (output_dir / "R3_SPLIT_REPORT.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("rebuilt_corpus_dir", type=Path)
    ap.add_argument("output_dir", type=Path)
    args = ap.parse_args()
    report = build(args.rebuilt_corpus_dir, args.output_dir)
    print(json.dumps(report, indent=2))
    ok = (
        report["counters"]["train_rows"] > 0
        and report["counters"]["validation_rows"] > 0
        and report["contains_2025_training_rows"] is False
        and report["sealed_2026"] is True
    )
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
