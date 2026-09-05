#!/usr/bin/env python3
"""Project the parity-rebuilt AIML corpus into the stricter VARDHANI R3 chronology.

Original AIML split labels are preserved only as provenance fields. R3 learning authority is
calendar-locked: <=2024-12-31 train, 2025 validation only, 2026 sealed/excluded.

This projector is fail-closed: it validates the source-row contract, measures chronology
violations rather than merely declaring their absence, and hashes both source and projected
row streams so a later teacher run can prove it consumed the exact approved projection.
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


def canonical_row_bytes(row: dict) -> bytes:
    return (json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


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
                row_split = row.get("split")
                if row_split != source_split:
                    raise RuntimeError(
                        f"{source}:{line_no}: embedded split {row_split!r} does not match source file {source_split!r}"
                    )
                date = str(row.get("exchange_date", ""))
                if len(date) != 10 or date[4] != "-" or date[7] != "-":
                    raise RuntimeError(f"{source}:{line_no}: missing/invalid exchange_date")
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
        "train_2025_rows": 0,
        "train_2026_or_later_rows": 0,
        "validation_pre_2025_rows": 0,
        "validation_2026_or_later_rows": 0,
        "sealed_2026_rows_rejected": 0,
        "outside_contract_rows_rejected": 0,
    }
    source_split_counts: dict[str, int] = {}
    source_year_counts: dict[str, int] = {}
    projected_split_counts: dict[str, int] = {"r3_train": 0, "r3_validation": 0}
    train_dates: set[str] = set()
    validation_dates: set[str] = set()
    train_min = train_max = None
    val_min = val_max = None
    source_row_digest = hashlib.sha256()
    train_row_digest = hashlib.sha256()
    validation_row_digest = hashlib.sha256()

    with train_path.open("w", encoding="utf-8", newline="\n") as train_out, validation_path.open("w", encoding="utf-8", newline="\n") as val_out:
        for source_split, row in iter_rows(inputs):
            counters["source_rows"] += 1
            source_split_counts[source_split] = source_split_counts.get(source_split, 0) + 1
            date = str(row["exchange_date"])
            year = date[:4]
            source_year_counts[year] = source_year_counts.get(year, 0) + 1
            source_row_digest.update(canonical_row_bytes(row))

            projected = dict(row)
            projected["aiml_original_split"] = source_split

            if date <= TRAIN_CUTOFF:
                projected["split"] = "r3_train"
                if date >= "2025-01-01":
                    counters["train_2025_rows"] += 1
                if date >= "2026-01-01":
                    counters["train_2026_or_later_rows"] += 1
                encoded = json.dumps(projected, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n"
                train_out.write(encoded)
                train_row_digest.update(canonical_row_bytes(projected))
                counters["train_rows"] += 1
                projected_split_counts["r3_train"] += 1
                train_dates.add(date)
                train_min = date if train_min is None or date < train_min else train_min
                train_max = date if train_max is None or date > train_max else train_max
            elif VALIDATION_START <= date <= VALIDATION_END:
                projected["split"] = "r3_validation"
                if date < "2025-01-01":
                    counters["validation_pre_2025_rows"] += 1
                if date >= "2026-01-01":
                    counters["validation_2026_or_later_rows"] += 1
                encoded = json.dumps(projected, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n"
                val_out.write(encoded)
                validation_row_digest.update(canonical_row_bytes(projected))
                counters["validation_rows"] += 1
                projected_split_counts["r3_validation"] += 1
                validation_dates.add(date)
                val_min = date if val_min is None or date < val_min else val_min
                val_max = date if val_max is None or date > val_max else val_max
            elif date >= "2026-01-01":
                counters["sealed_2026_rows_rejected"] += 1
            else:
                counters["outside_contract_rows_rejected"] += 1

    contains_2025_training_rows = counters["train_2025_rows"] > 0
    contains_2026_training_rows = counters["train_2026_or_later_rows"] > 0
    contains_2026_validation_rows = counters["validation_2026_or_later_rows"] > 0
    contains_pre_2025_validation_rows = counters["validation_pre_2025_rows"] > 0

    report = {
        "format": "VARDHANI_SPECIALIST_R3_SPLIT_REPORT_V2",
        "source_files": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in inputs},
        "source_split_counts": source_split_counts,
        "source_year_counts": source_year_counts,
        "source_canonical_row_stream_sha256": source_row_digest.hexdigest(),
        "configured_train_cutoff": TRAIN_CUTOFF,
        "configured_validation_start": VALIDATION_START,
        "configured_validation_end": VALIDATION_END,
        "train_min_exchange_date": train_min,
        "train_max_exchange_date": train_max,
        "validation_min_exchange_date": val_min,
        "validation_max_exchange_date": val_max,
        "train_unique_exchange_dates": len(train_dates),
        "validation_unique_exchange_dates": len(validation_dates),
        "contains_2025_training_rows": contains_2025_training_rows,
        "contains_2026_training_rows": contains_2026_training_rows,
        "contains_2026_validation_rows": contains_2026_validation_rows,
        "contains_pre_2025_validation_rows": contains_pre_2025_validation_rows,
        "sealed_2026": True,
        "projected_split_counts": projected_split_counts,
        "counters": counters,
        "outputs": {
            train_path.name: {
                "bytes": train_path.stat().st_size,
                "sha256": sha256(train_path),
                "canonical_row_stream_sha256": train_row_digest.hexdigest(),
            },
            validation_path.name: {
                "bytes": validation_path.stat().st_size,
                "sha256": sha256(validation_path),
                "canonical_row_stream_sha256": validation_row_digest.hexdigest(),
            },
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
    c = report["counters"]
    ok = (
        c["train_rows"] > 0
        and c["validation_rows"] > 0
        and c["train_2025_rows"] == 0
        and c["train_2026_or_later_rows"] == 0
        and c["validation_pre_2025_rows"] == 0
        and c["validation_2026_or_later_rows"] == 0
        and report["contains_2025_training_rows"] is False
        and report["contains_2026_training_rows"] is False
        and report["contains_2026_validation_rows"] is False
        and report["sealed_2026"] is True
    )
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
