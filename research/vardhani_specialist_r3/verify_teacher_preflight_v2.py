#!/usr/bin/env python3
"""Authoritative V2 teacher preflight for VARDHANI Specialist R3.

Supersedes only the full-market verifier in verify_teacher_preflight.py. The option unlock
and architecture-source checks remain unchanged. V2 verifies every physical NPZ file,
recomputes canonical content hashes, checks physical timestamp/date/shape chronology, proves
the cross-index timestamps equal the exact NIFTY/SENSEX intersection, reconciles artifact
ledgers, and requires frozen Stage-A observed-row parity before teacher construction can start.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np

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
IST_OFFSET_SECONDS = 19_800


def canonical_npz_digest(arrays: dict[str, np.ndarray]) -> str:
    h = hashlib.sha256()
    for name in sorted(arrays):
        arr = np.ascontiguousarray(arrays[name])
        h.update(name.encode("utf-8") + b"\0")
        h.update(str(arr.dtype).encode("ascii") + b"\0")
        h.update(json.dumps(list(arr.shape), separators=(",", ":")).encode("ascii") + b"\0")
        h.update(arr.tobytes(order="C"))
        h.update(b"\n")
    return h.hexdigest()


def aggregate_digest(specs: list[dict[str, Any]]) -> str:
    payload = [
        {
            "path": x["path"], "rows": int(x["rows"]), "bytes": int(x["bytes"]),
            "sha256": x["sha256"], "canonical_row_stream_sha256": x["canonical_row_stream_sha256"],
        }
        for x in sorted(specs, key=lambda v: v["path"])
    ]
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def ist_days_from_epoch(ts: np.ndarray) -> np.ndarray:
    local_seconds = ts.astype(np.int64) + IST_OFFSET_SECONDS
    return local_seconds.astype("datetime64[s]").astype("datetime64[D]").astype("U10")


def verify_split_days(days: np.ndarray, split: str, failures: list[str], label: str) -> None:
    if len(days) == 0:
        failures.append(f"{label}: empty day vector")
        return
    if split == "train":
        bad = int(np.count_nonzero(days > pre.TRAIN_END))
        if bad:
            failures.append(f"{label}: {bad} physical rows after 2024-12-31")
    elif split == "validation":
        before = int(np.count_nonzero(days < pre.VAL_START))
        after = int(np.count_nonzero(days > pre.VAL_END))
        if before or after:
            failures.append(f"{label}: physical validation rows outside 2025 before={before} after={after}")
    else:
        failures.append(f"{label}: unknown split {split!r}")


def verify_npz_artifact(root: Path, spec: dict[str, Any], market: str, split: str, failures: list[str], label: str) -> np.ndarray | None:
    path = pre.verify_file(root, spec, failures, label)
    if path is None:
        return None
    try:
        with np.load(path, allow_pickle=False) as z:
            arrays = {name: z[name] for name in z.files}
    except Exception as exc:
        failures.append(f"{label}: NPZ unreadable: {exc}")
        return None

    declared_rows = int(spec.get("rows", -1))
    declared_canonical = spec.get("canonical_row_stream_sha256")
    pre.require_hash(failures, f"{label}.canonical_row_stream_sha256", declared_canonical)
    actual_canonical = canonical_npz_digest(arrays)
    if actual_canonical != declared_canonical:
        failures.append(f"{label}: canonical content SHA-256 mismatch")

    if market in {"NIFTY", "SENSEX"}:
        required = {"TS_EPOCH_SECOND", "DAY", "SESSION_MIN", "CLOSE", "XLO", "X15"}
        missing = sorted(required - arrays.keys())
        if missing:
            failures.append(f"{label}: missing arrays {missing}")
            return None
        ts = np.asarray(arrays["TS_EPOCH_SECOND"], dtype=np.int64)
        days = np.asarray(arrays["DAY"]).astype("U10")
        sm = np.asarray(arrays["SESSION_MIN"], dtype=np.int64)
        close = np.asarray(arrays["CLOSE"], dtype=np.float64)
        xlo = np.asarray(arrays["XLO"])
        x15 = np.asarray(arrays["X15"])
        n = len(ts)
        if n != declared_rows:
            failures.append(f"{label}: physical rows {n} != declared {declared_rows}")
        expected_shapes = {
            "DAY": (n,), "SESSION_MIN": (n,), "CLOSE": (n,), "XLO": (n, 74), "X15": (n, 23)
        }
        for name, shape in expected_shapes.items():
            if tuple(np.asarray(arrays[name]).shape) != shape:
                failures.append(f"{label}: {name} shape {tuple(np.asarray(arrays[name]).shape)} != {shape}")
        if n > 1 and not bool(np.all(ts[1:] > ts[:-1])):
            failures.append(f"{label}: timestamps are not strictly increasing")
        if not np.isfinite(close).all() or bool(np.any(close <= 0.0)):
            failures.append(f"{label}: CLOSE contains non-finite/non-positive values")
        if not np.isfinite(xlo).all() or not np.isfinite(x15).all():
            failures.append(f"{label}: feature arrays contain non-finite values")
        derived_days = ist_days_from_epoch(ts)
        if not np.array_equal(days, derived_days):
            failures.append(f"{label}: DAY does not equal Asia/Kolkata date derived from epoch timestamps")
        local_minute = ((ts + IST_OFFSET_SECONDS) % 86_400) // 60 - 555
        if not np.array_equal(sm, local_minute):
            failures.append(f"{label}: SESSION_MIN does not equal Asia/Kolkata exchange minute")
        if bool(np.any(sm < 0)) or bool(np.any(sm >= 375)):
            failures.append(f"{label}: physical rows outside 09:15-15:29 session")
        verify_split_days(days, split, failures, label)
        if n:
            pre.require_eq(failures, f"{label}.min_day", spec.get("min_day"), str(days[0]))
            pre.require_eq(failures, f"{label}.max_day", spec.get("max_day"), str(days[-1]))
        return ts

    required = {
        "COMMON_TS_EPOCH_SECOND", "NIFTY_XLO", "NIFTY_X15", "NIFTY_CLOSE",
        "SENSEX_XLO", "SENSEX_X15", "SENSEX_CLOSE",
    }
    missing = sorted(required - arrays.keys())
    if missing:
        failures.append(f"{label}: missing arrays {missing}")
        return None
    ts = np.asarray(arrays["COMMON_TS_EPOCH_SECOND"], dtype=np.int64)
    n = len(ts)
    if n != declared_rows:
        failures.append(f"{label}: physical rows {n} != declared {declared_rows}")
    expected_shapes = {
        "NIFTY_XLO": (n, 74), "NIFTY_X15": (n, 23), "NIFTY_CLOSE": (n,),
        "SENSEX_XLO": (n, 74), "SENSEX_X15": (n, 23), "SENSEX_CLOSE": (n,),
    }
    for name, shape in expected_shapes.items():
        arr = np.asarray(arrays[name])
        if tuple(arr.shape) != shape:
            failures.append(f"{label}: {name} shape {tuple(arr.shape)} != {shape}")
        if not np.isfinite(arr).all():
            failures.append(f"{label}: {name} contains non-finite values")
    if bool(np.any(np.asarray(arrays["NIFTY_CLOSE"], dtype=np.float64) <= 0.0)) or bool(np.any(np.asarray(arrays["SENSEX_CLOSE"], dtype=np.float64) <= 0.0)):
        failures.append(f"{label}: cross close arrays contain non-positive values")
    if n > 1 and not bool(np.all(ts[1:] > ts[:-1])):
        failures.append(f"{label}: common timestamps are not strictly increasing")
    verify_split_days(ist_days_from_epoch(ts), split, failures, label)
    return ts


def verify_full_market_v2(manifest: dict[str, Any], root: Path, failures: list[str]) -> None:
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
    physical_ts: dict[str, np.ndarray] = {}
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
        ts = verify_npz_artifact(root, spec, market, split, failures, f"full_market.files.{key}")
        if ts is not None:
            physical_ts[key] = ts

    for split in ["train", "validation"]:
        nk = f"nifty_{split}"; sk = f"sensex_{split}"; ck = f"cross_{split}"
        if nk in physical_ts and sk in physical_ts and ck in physical_ts:
            exact = np.intersect1d(physical_ts[nk], physical_ts[sk], assume_unique=True)
            if not np.array_equal(exact, physical_ts[ck]):
                failures.append(f"cross_{split}: physical timestamps are not exact NIFTY/SENSEX intersection")

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


# V1 main resolves this global at call time. Override only full-market validation; token,
# architecture, frozen-R2 and result logic remain the reviewed V1 implementation.
pre.verify_full_market = verify_full_market_v2

if __name__ == "__main__":
    sys.exit(pre.main())
