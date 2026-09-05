#!/usr/bin/env python3
"""V2 wrapper for the specialist corpus recovery gate.

Uses the frozen V1 source/corpus checks and replaces only the R3 calendar check so
actual first/last observed trading dates are validated as bounds rather than being
forced to equal calendar boundary dates.
"""
from __future__ import annotations

import sys
import verify_corpus_recovery as gate


def compare_r3_split_report_v2(report: dict) -> list[str]:
    failures: list[str] = []
    if report.get("format") != "VARDHANI_SPECIALIST_R3_SPLIT_REPORT_V1":
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

    if report.get("contains_2025_training_rows") is not False:
        failures.append("R3 split contains 2025 training rows")
    if report.get("contains_2026_training_rows") is not False:
        failures.append("R3 split contains 2026 training rows")
    if report.get("contains_2026_validation_rows") is not False:
        failures.append("R3 split contains 2026 validation rows")
    if report.get("sealed_2026") is not True:
        failures.append("R3 split does not declare 2026 sealed")

    counters = report.get("counters") or {}
    if int(counters.get("train_rows", 0)) <= 0:
        failures.append("R3 train_rows must be positive")
    if int(counters.get("validation_rows", 0)) <= 0:
        failures.append("R3 validation_rows must be positive")
    if int(counters.get("sealed_2026_rows_rejected", 0)) < 0:
        failures.append("invalid sealed_2026_rows_rejected count")
    return failures


gate.compare_r3_split_report = compare_r3_split_report_v2

if __name__ == "__main__":
    sys.exit(gate.main())
