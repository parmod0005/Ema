#!/usr/bin/env python3
"""Authoritative V2 teacher preflight for VARDHANI Specialist R3.

Supersedes only the full-market manifest verifier in verify_teacher_preflight.py.  The option
unlock-token and architecture-source checks remain unchanged.  V2 verifies every physical
full-market NPZ file, reconciles the declared train/validation ledgers, and requires the exact
Stage-A observed-row parity before teacher construction can be unlocked.
"""
from __future__ import annotations

import hashlib
import json
import sys
from typing import Any

import verify_teacher_preflight as pre

FULL_MARKET_FORMAT_V2 = "VARDHANI_SPECIALIST_R3_FULL_MARKET_CORPUS_MANIFEST_V2"
REQUIRED_FILES = {
    "nifty_train": ("NIFTY", "train"),
    "nifty_validation": ("NIFTY", "validation"),
    "sensex_train": ("SENSEX", "train"),
    "sensex_validation": ("SENSEX", "validation"),
    "cross_train": ("NIFTY_SENSEX_COMMON", "train"),
    "cross_validation": ("NIFTY_SENSEX_COMMON", "validation"),
}
EXPECTED_STAGEA = {"NIFTY": 422_138, "SENSEX": 422_144}


def aggregate_digest(specs: list[dict[str, Any]]) -> str:
    payload = [
        {
            "path": x["path"], "rows": int(x["rows"]), "bytes": int(x["bytes"]),
            "sha256": x["sha256"], "canonical_row_stream_sha256": x["canonical_row_stream_sha256"],
        }
        for x in sorted(specs, key=lambda v: v["path"])
    ]
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def verify_full_market_v2(manifest: dict[str, Any], root, failures: list[str]) -> None:
    pre.require_eq(failures, "full_market.format", manifest.get("format"), FULL_MARKET_FORMAT_V2)
    pre.require_eq(failures, "full_market.supersedes", manifest.get("supersedes"), pre.FULL_MARKET_FORMAT)
    pre.require_eq(failures, "full_market.raw_source_sha256", manifest.get("raw_source_sha256"), pre.RAW_SHA)
    pre.require_eq(failures, "full_market.execution_authority", manifest.get("execution_authority"), False)
    pre.require_eq(failures, "full_market.sealed_2026", manifest.get("sealed_2026"), True)

    files = manifest.get("files")
    if not isinstance(files, dict):
        failures.append("full_market.files missing")
        files = {}
    physical: dict[str, dict[str, Any]] = {}
    for key, (market, split) in REQUIRED_FILES.items():
        spec = files.get(key)
        if not isinstance(spec, dict):
            failures.append(f"full_market.files.{key} missing")
            continue
        physical[key] = spec
        pre.require_eq(failures, f"full_market.files.{key}.market", spec.get("market"), market)
        pre.require_eq(failures, f"full_market.files.{key}.split", spec.get("split"), split)
        if int(spec.get("rows", 0)) <= 0:
            failures.append(f"full_market.files.{key}.rows must be positive")
        pre.require_hash(failures, f"full_market.files.{key}.canonical_row_stream_sha256", spec.get("canonical_row_stream_sha256"))
        pre.verify_file(root, spec, failures, f"full_market.files.{key}")

    train = manifest.get("train") or {}
    validation = manifest.get("validation") or {}
    expected_train_keys = ["nifty_train", "sensex_train", "cross_train"]
    expected_val_keys = ["nifty_validation", "sensex_validation", "cross_validation"]
    pre.require_eq(failures, "full_market.train.artifact_keys", train.get("artifact_keys"), expected_train_keys)
    pre.require_eq(failures, "full_market.validation.artifact_keys", validation.get("artifact_keys"), expected_val_keys)

    if all(k in physical for k in expected_train_keys):
        train_specs = [physical[k] for k in expected_train_keys]
        market_rows = int(physical["nifty_train"]["rows"]) + int(physical["sensex_train"]["rows"])
        cross_rows = int(physical["cross_train"]["rows"])
        pre.require_eq(failures, "full_market.train.rows_market_streams", int(train.get("rows_market_streams", -1)), market_rows)
        pre.require_eq(failures, "full_market.train.rows_cross_stream", int(train.get("rows_cross_stream", -1)), cross_rows)
        pre.require_eq(failures, "full_market.train.artifact_ledger_sha256", train.get("artifact_ledger_sha256"), aggregate_digest(train_specs))
    if all(k in physical for k in expected_val_keys):
        val_specs = [physical[k] for k in expected_val_keys]
        market_rows = int(physical["nifty_validation"]["rows"]) + int(physical["sensex_validation"]["rows"])
        cross_rows = int(physical["cross_validation"]["rows"])
        pre.require_eq(failures, "full_market.validation.rows_market_streams", int(validation.get("rows_market_streams", -1)), market_rows)
        pre.require_eq(failures, "full_market.validation.rows_cross_stream", int(validation.get("rows_cross_stream", -1)), cross_rows)
        pre.require_eq(failures, "full_market.validation.artifact_ledger_sha256", validation.get("artifact_ledger_sha256"), aggregate_digest(val_specs))

    train_min = str(train.get("min_timestamp", ""))[:10]
    train_max = str(train.get("max_timestamp", ""))[:10]
    val_min = str(validation.get("min_timestamp", ""))[:10]
    val_max = str(validation.get("max_timestamp", ""))[:10]
    if not train_min or not train_max:
        failures.append("full-market training date range missing")
    elif train_max > pre.TRAIN_END:
        failures.append("full-market training extends beyond 2024-12-31")
    if not val_min or val_min < pre.VAL_START:
        failures.append("full-market validation begins before 2025")
    if not val_max or val_max > pre.VAL_END:
        failures.append("full-market validation extends beyond 2025")

    for key in ["nifty_train", "sensex_train"]:
        if key in physical and str(physical[key].get("max_day", "")) > pre.TRAIN_END:
            failures.append(f"{key} physically contains a declared day beyond 2024")
    for key in ["nifty_validation", "sensex_validation"]:
        if key in physical:
            mn = str(physical[key].get("min_day", "")); mx = str(physical[key].get("max_day", ""))
            if not mn or mn < pre.VAL_START or not mx or mx > pre.VAL_END:
                failures.append(f"{key} declared date range is outside 2025")

    leakage = manifest.get("measured_leakage") or {}
    for key in ["train_2025_rows", "train_2026_rows", "validation_pre_2025_rows", "validation_2026_rows"]:
        if key not in leakage:
            failures.append(f"full-market measured leakage counter missing: {key}")
        elif int(leakage.get(key, -1)) != 0:
            failures.append(f"full-market chronology leakage {key}={leakage.get(key)}")
    if int(manifest.get("sealed_2026_rows_rejected", -1)) < 0:
        failures.append("full-market sealed_2026_rows_rejected missing/invalid")

    parity = manifest.get("stagea_parity") or {}
    pre.require_eq(failures, "stagea_parity.pass", parity.get("pass"), True)
    for market, expected in EXPECTED_STAGEA.items():
        pre.require_eq(failures, f"stagea_parity.{market}_clean_rows_expected", int(parity.get(f"{market}_clean_rows_expected", -1)), expected)
        pre.require_eq(failures, f"stagea_parity.{market}_clean_rows_actual", int(parity.get(f"{market}_clean_rows_actual", -1)), expected)

    stats = manifest.get("source_stats") or {}
    for market, expected in EXPECTED_STAGEA.items():
        actual = int((stats.get(market) or {}).get("rows_after_clean", -1))
        pre.require_eq(failures, f"source_stats.{market}.rows_after_clean", actual, expected)

    schema = manifest.get("feature_schema") or {}
    pre.verify_file(root, schema, failures, "full_market.feature_schema")
    pre.require_hash(failures, "full_market.feature_schema.sha256", schema.get("sha256"))

    mutation = manifest.get("future_mutation_audit") or {}
    if float(mutation.get("pre_cut_max_abs_change", float("inf"))) != 0.0:
        failures.append("full-market future-mutation causal gate is not exactly zero")
    per_index = mutation.get("per_index") or {}
    for market in ["NIFTY", "SENSEX"]:
        if float(per_index.get(market, float("inf"))) != 0.0:
            failures.append(f"full-market {market} future-mutation gate is not exactly zero")

    coverage = manifest.get("modality_coverage")
    if not isinstance(coverage, dict) or not coverage:
        failures.append("full-market modality_coverage missing")
    else:
        pre.require_eq(failures, "coverage.NIFTY_rows_observed_only", int(coverage.get("NIFTY_rows_observed_only", -1)), EXPECTED_STAGEA["NIFTY"])
        pre.require_eq(failures, "coverage.SENSEX_rows_observed_only", int(coverage.get("SENSEX_rows_observed_only", -1)), EXPECTED_STAGEA["SENSEX"])
        pre.require_eq(failures, "coverage.NIFTY_options_in_this_builder", coverage.get("NIFTY_options_in_this_builder"), False)
        pre.require_eq(failures, "coverage.SENSEX_options_in_this_builder", coverage.get("SENSEX_options_in_this_builder"), False)

    hard = manifest.get("hard_rules") or {}
    pre.require_eq(failures, "hard_rules.option_overlay_separate", hard.get("option_overlay_separate"), True)
    pre.require_eq(failures, "hard_rules.sensex_options_fabricated", hard.get("sensex_options_fabricated"), False)
    pre.require_eq(failures, "hard_rules.historical_d30_used", hard.get("historical_d30_used"), False)
    pre.require_eq(failures, "hard_rules.historical_index_volume_oi_used", hard.get("historical_index_volume_oi_used"), False)
    pre.require_eq(failures, "hard_rules.real_orders", hard.get("real_orders"), "DISABLED")


# The original preflight main resolves this global at call time. Override only this verifier;
# token, architecture, frozen-R2 and CLI output logic remain the reviewed V1 implementation.
pre.verify_full_market = verify_full_market_v2

if __name__ == "__main__":
    sys.exit(pre.main())
