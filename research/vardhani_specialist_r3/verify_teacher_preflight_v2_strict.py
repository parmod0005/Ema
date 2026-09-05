#!/usr/bin/env python3
"""Strict accounting layer over the authoritative V2 R3 teacher preflight.

Adds whole-history conservation: every observed-only NIFTY/SENSEX Stage-A row must be
accounted for exactly once as <=2024 development, 2025 validation, or sealed-2026 rejection.
No model construction or optimizer start is possible if even one market row is unaccounted.
"""
from __future__ import annotations

import sys

import verify_teacher_preflight as pre
import verify_teacher_preflight_v2 as v2


def verify_full_market_v2_strict(manifest, root, failures):
    v2.verify_full_market_v2(manifest, root, failures)
    files = manifest.get("files") or {}
    keys = ["nifty_train", "nifty_validation", "sensex_train", "sensex_validation"]
    if not all(isinstance(files.get(k), dict) for k in keys):
        failures.append("full-market conservation cannot run because a market artifact is missing")
        return
    emitted_market_rows = sum(int(files[k].get("rows", -1)) for k in keys)
    sealed = int(manifest.get("sealed_2026_rows_rejected", -1))
    expected = sum(v2.EXPECTED_STAGEA.values())
    if emitted_market_rows < 0 or sealed < 0:
        failures.append("full-market conservation has invalid row counts")
        return
    if emitted_market_rows + sealed != expected:
        failures.append(
            f"full-market row conservation failed: emitted={emitted_market_rows} sealed={sealed} expected={expected}"
        )


pre.verify_full_market = verify_full_market_v2_strict

if __name__ == "__main__":
    sys.exit(pre.main())
