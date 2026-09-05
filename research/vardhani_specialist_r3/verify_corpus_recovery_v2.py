#!/usr/bin/env python3
"""V2 chronology verifier for the specialist corpus recovery gate.

Uses the frozen raw-source/original-corpus checks from verify_corpus_recovery and replaces
only the R3 calendar check. The R3 projection must report measured leakage counters and
canonical row-stream hashes; declarations alone are not accepted.
"""
from __future__ import annotations

import sys
import verify_corpus_recovery as gate


def compare_r3_split_report_v2(report: dict) -> list[str]:
    failures: list[str] = []
    if report.get("format") != "VARDHANI_SPECIALIST_R3_SPLIT_REPORT_V2":
        failures.append("R3 split report format mismatch")

    if report.get("configured_train_cutoff") != gate.R3_TRAIN_CUTOFF:
        failures.append(f"configured_train_cutoff must be {gate.R3_TRAIN_CUTOFF}")
    if report.get("configured_validation_start") != gate.R3_VALIDATION_START:
        failures.append(f"configured_validation_start must be {gate.R3_VALIDATION_START}")
    if report.get("configured_validation_end") != gate.R3_VALIDATION_END:
        failures.append(f"configured_validation_end must be {gate.R3_VALIDATION_END}")

    train_min = report.get("train_min_exchange_date")
    train_max = report.get("train_max_exchange_date")
    val_min = report.get("validation_min_exchange_date")
    val_max = report.get("validation_max_exchange_date")

    if train_min is None or train_max is None:
        failures.append("R3 training date range is empty")
    elif train_max > gate.R3_TRAIN_CUTOFF:
        failures.append(f"R3 train_max_exchange_date exceeds {gate.R3_TRAIN_CUTOFF}")

    if val_min is None or val_max is None:
        failures.append("R3 validation date range is empty")
    else:
        if val_min < gate.R3_VALIDATION_START:
            failures.append(f"R3 validation_min_exchange_date precedes {gate.R3_VALIDATION_START}")
        if val_max > gate.R3_VALIDATION_END:
            failures.append(f"R3 validation_max_exchange_date exceeds {gate.R3_VALIDATION_END}")

    counters = report.get("counters") or {}
    required_zero = {
        "train_2025_rows": "R3 measured 2025 training leakage",
        "train_2026_or_later_rows": "R3 measured 2026+ training leakage",
        "validation_pre_2025_rows": "R3 measured pre-2025 validation leakage",
        "validation_2026_or_later_rows": "R3 measured 2026+ validation leakage",
    }
    for key, label in required_zero.items():
        if key not in counters:
            failures.append(f"missing measured chronology counter {key}")
        elif int(counters.get(key, -1)) != 0:
            failures.append(f"{label}: {counters.get(key)} rows")

    if int(counters.get("train_rows", 0)) <= 0:
        failures.append("R3 train_rows must be positive")
    if int(counters.get("validation_rows", 0)) <= 0:
        failures.append("R3 validation_rows must be positive")
    if int(counters.get("sealed_2026_rows_rejected", 0)) < 0:
        failures.append("invalid sealed_2026_rows_rejected count")

    if report.get("contains_2025_training_rows") is not False:
        failures.append("R3 split contains 2025 training rows")
    if report.get("contains_2026_training_rows") is not False:
        failures.append("R3 split contains 2026 training rows")
    if report.get("contains_2026_validation_rows") is not False:
        failures.append("R3 split contains 2026 validation rows")
    if report.get("contains_pre_2025_validation_rows") is not False:
        failures.append("R3 split contains pre-2025 validation rows")
    if report.get("sealed_2026") is not True:
        failures.append("R3 split does not declare 2026 sealed")
    if report.get("execution_authority") is not False:
        failures.append("R3 split must have execution_authority=false")

    source_stream_hash = report.get("source_canonical_row_stream_sha256")
    if not isinstance(source_stream_hash, str) or len(source_stream_hash) != 64:
        failures.append("missing/invalid source canonical row-stream SHA-256")

    outputs = report.get("outputs") or {}
    for name in ["options_train_through_2024.ndjson", "options_validation_2025.ndjson"]:
        item = outputs.get(name) or {}
        for field in ["sha256", "canonical_row_stream_sha256"]:
            value = item.get(field)
            if not isinstance(value, str) or len(value) != 64:
                failures.append(f"{name} missing/invalid {field}")

    source_counts = report.get("source_split_counts") or {}
    source_rows = int(counters.get("source_rows", -1))
    if source_rows < 0 or sum(int(v) for v in source_counts.values()) != source_rows:
        failures.append("source split counts do not reconcile to source_rows")

    projected = report.get("projected_split_counts") or {}
    if int(projected.get("r3_train", -1)) != int(counters.get("train_rows", -2)):
        failures.append("projected r3_train count does not reconcile")
    if int(projected.get("r3_validation", -1)) != int(counters.get("validation_rows", -2)):
        failures.append("projected r3_validation count does not reconcile")
    return failures


gate.compare_r3_split_report = compare_r3_split_report_v2

if __name__ == "__main__":
    sys.exit(gate.main())
