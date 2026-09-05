#!/usr/bin/env python3
"""Authoritative V2 full-market corpus builder for VARDHANI Specialist R3.

Supersedes build_full_market_corpus.py before any runtime output was accepted. V2 keeps the
same frozen 74 lower + 23 separate 15m-context R2 feature semantics, but fixes two preflight
issues discovered by static audit:
  1. every archive timestamp is normalized to Asia/Kolkata before deriving exchange day/
     session minute; and
  2. the manifest identifies each physical NPZ artifact directly instead of using pseudo
     aggregate paths.

This program has no training, promotion, prospective-evidence or order-execution authority.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import numpy as np

import build_full_market_corpus as base
import rebuild_aiml_option_corpus as source

IST = ZoneInfo("Asia/Kolkata")
EXPECTED_CLEAN_ROWS = {"NIFTY": 422_138, "SENSEX": 422_144}
REQUIRED_FILES = (
    "nifty_train", "nifty_validation",
    "sensex_train", "sensex_validation",
    "cross_train", "cross_validation",
)


def ist_contract(timestamp: str) -> tuple[str, int]:
    dt = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        raise ValueError("market timestamp must be timezone-aware")
    dt = dt.astimezone(IST)
    if dt.second != 0 or dt.microsecond != 0:
        raise ValueError("market timestamp must be minute-aligned")
    sm = dt.hour * 60 + dt.minute - 555
    return dt.date().isoformat(), sm


def load_symbol_v2(archive: source.Archive, prefix: str) -> tuple[list[base.MarketRow], dict[str, Any]]:
    files = archive.relative_files(prefix.rstrip("/") + "/minutes-1/", ".json")
    if not files:
        raise RuntimeError(f"no historical files for {prefix}")
    rows: dict[int, base.MarketRow] = {}
    integrity_counts: dict[str, int] = {}
    parse_invalid = parse_duplicates = cross_file_duplicates = 0
    outside_session = invalid_ohlc = 0

    for name in files:
        integrity = archive.verify(name)
        integrity_counts[integrity] = integrity_counts.get(integrity, 0) + 1
        if integrity in source.INTEGRITY_REJECTIONS:
            raise RuntimeError(f"integrity rejection for {name}: {integrity}")
        parsed = source.read_candles_bytes(archive.read(name))
        parse_invalid += parsed.invalid
        parse_duplicates += parsed.duplicates
        for c in parsed.rows:
            day, sm = ist_contract(c.source_timestamp)
            if not 0 <= sm < 375:
                outside_session += 1
                continue
            if min(c.open, c.high, c.low, c.close) <= 0.0 or c.high < max(c.open, c.close, c.low) or c.low > min(c.open, c.close, c.high):
                invalid_ohlc += 1
                continue
            if c.epoch_second in rows:
                cross_file_duplicates += 1
            rows[c.epoch_second] = base.MarketRow(
                epoch_second=c.epoch_second,
                source_timestamp=c.source_timestamp,
                day=day,
                session_minute=sm,
                open=c.open,
                high=c.high,
                low=c.low,
                close=c.close,
            )

    ordered = sorted(rows.values(), key=lambda r: r.epoch_second)
    by_day: dict[str, list[base.MarketRow]] = {}
    for row in ordered:
        by_day.setdefault(row.day, []).append(row)
    rejected_days = {
        day for day, rs in by_day.items()
        if max(r.high for r in rs) - min(r.low for r in rs) == 0.0
    }
    clean = [r for r in ordered if r.day not in rejected_days]
    return clean, {
        "source_files": len(files),
        "integrity_counts": integrity_counts,
        "parse_invalid_rows": parse_invalid,
        "parse_duplicate_rows": parse_duplicates,
        "cross_file_duplicate_timestamps": cross_file_duplicates,
        "outside_session_rows": outside_session,
        "invalid_ohlc_rows": invalid_ohlc,
        "rejected_zero_range_sessions": len(rejected_days),
        "rejected_zero_range_session_dates": sorted(rejected_days),
        "rejected_zero_range_rows": sum(len(by_day[d]) for d in rejected_days),
        "rows_after_clean": len(clean),
    }


def split_masks(rows: list[base.MarketRow]) -> tuple[np.ndarray, np.ndarray, np.ndarray, dict[str, int]]:
    days = np.asarray([r.day for r in rows], dtype="U10")
    train = days <= base.TRAIN_END
    validation = (days >= base.VAL_START) & (days <= base.VAL_END)
    sealed = days > base.VAL_END
    measured = {
        "train_2025_rows": int(np.count_nonzero(train & (days >= "2025-01-01"))),
        "train_2026_rows": int(np.count_nonzero(train & (days >= "2026-01-01"))),
        "validation_pre_2025_rows": int(np.count_nonzero(validation & (days < "2025-01-01"))),
        "validation_2026_rows": int(np.count_nonzero(validation & (days >= "2026-01-01"))),
    }
    return train, validation, sealed, measured


def artifact_spec(path: Path, arrays: dict[str, np.ndarray], *, market: str, split: str) -> dict[str, Any]:
    np.savez_compressed(path, **arrays)
    first_day = str(arrays["DAY"][0]) if "DAY" in arrays and len(arrays["DAY"]) else None
    last_day = str(arrays["DAY"][-1]) if "DAY" in arrays and len(arrays["DAY"]) else None
    if "COMMON_TS_EPOCH_SECOND" in arrays:
        rows = len(arrays["COMMON_TS_EPOCH_SECOND"])
    else:
        rows = len(arrays["TS_EPOCH_SECOND"])
    return {
        "path": path.name,
        "market": market,
        "split": split,
        "rows": int(rows),
        "bytes": path.stat().st_size,
        "sha256": base.sha256(path),
        "canonical_row_stream_sha256": base.canonical_npz_digest(arrays),
        "min_day": first_day,
        "max_day": last_day,
    }


def aggregate_digest(specs: list[dict[str, Any]]) -> str:
    payload = [
        {
            "path": x["path"], "rows": x["rows"], "bytes": x["bytes"],
            "sha256": x["sha256"], "canonical_row_stream_sha256": x["canonical_row_stream_sha256"],
        }
        for x in sorted(specs, key=lambda v: v["path"])
    ]
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def date_range(specs: list[dict[str, Any]]) -> tuple[str | None, str | None]:
    mins = [x["min_day"] for x in specs if x.get("min_day")]
    maxs = [x["max_day"] for x in specs if x.get("max_day")]
    return (min(mins) if mins else None, max(maxs) if maxs else None)


def build(raw_zip: Path, output: Path) -> dict[str, Any]:
    if source.sha256_file(raw_zip) != base.RAW_SHA:
        raise RuntimeError("raw archive SHA-256 mismatch")
    output.mkdir(parents=True, exist_ok=True)
    archive = source.Archive(raw_zip)
    try:
        nifty, nstats = load_symbol_v2(archive, "underlying/nifty-50")
        sensex, sstats = load_symbol_v2(archive, "underlying/sensex")
        vix, vstats = load_symbol_v2(archive, "underlying/india-vix")
    finally:
        archive.close()

    if len(nifty) != EXPECTED_CLEAN_ROWS["NIFTY"]:
        raise RuntimeError(f"NIFTY Stage-A row parity failed: expected {EXPECTED_CLEAN_ROWS['NIFTY']}, got {len(nifty)}")
    if len(sensex) != EXPECTED_CLEAN_ROWS["SENSEX"]:
        raise RuntimeError(f"SENSEX Stage-A row parity failed: expected {EXPECTED_CLEAN_ROWS['SENSEX']}, got {len(sensex)}")

    nlo, n15 = base.build_features(nifty, vix)
    slo, s15 = base.build_features(sensex, vix)
    nt, nv, ns, nleak = split_masks(nifty)
    st, sv, ss, sleak = split_masks(sensex)

    ntrain = base.stream_arrays(nifty, nlo, n15, nt)
    nval = base.stream_arrays(nifty, nlo, n15, nv)
    strain = base.stream_arrays(sensex, slo, s15, st)
    sval = base.stream_arrays(sensex, slo, s15, sv)
    ctrain = base.merged_arrays((nifty, nlo, n15), (sensex, slo, s15), "train")
    cval = base.merged_arrays((nifty, nlo, n15), (sensex, slo, s15), "validation")

    files = {
        "nifty_train": artifact_spec(output / "nifty_train.npz", ntrain, market="NIFTY", split="train"),
        "nifty_validation": artifact_spec(output / "nifty_validation.npz", nval, market="NIFTY", split="validation"),
        "sensex_train": artifact_spec(output / "sensex_train.npz", strain, market="SENSEX", split="train"),
        "sensex_validation": artifact_spec(output / "sensex_validation.npz", sval, market="SENSEX", split="validation"),
        "cross_train": artifact_spec(output / "cross_train.npz", ctrain, market="NIFTY_SENSEX_COMMON", split="train"),
        "cross_validation": artifact_spec(output / "cross_validation.npz", cval, market="NIFTY_SENSEX_COMMON", split="validation"),
    }

    schema = {
        "format": "VARDHANI_SPECIALIST_R3_FULL_MARKET_FEATURE_SCHEMA_V2",
        "base_semantics": "Frozen R2FeatureBuilder 74 lower + 23 separate 15m context",
        "lower_features": base.LOWER_FEATURES,
        "context15_features": base.CONTEXT15_FEATURES,
        "lower_feature_count": 74,
        "context15_feature_count": 23,
        "timestamp_timezone": "Asia/Kolkata",
        "session_rule": "09:15-15:29 Asia/Kolkata observed-only",
        "vix_alignment": "causal as-of only; vix timestamp <= market timestamp",
        "cross_stream": "exact common epoch timestamps only",
        "15m_policy": "CONTEXT_NON_VETO",
        "index_volume_used": False,
        "index_oi_used": False,
        "historical_d30_used": False,
        "fabricated_modalities": False,
    }
    schema_path = output / "feature_schema.json"
    schema_path.write_text(json.dumps(schema, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    feature_schema = {"path": schema_path.name, "bytes": schema_path.stat().st_size, "sha256": base.sha256(schema_path)}

    mutation = {
        "NIFTY": base.feature_mutation_audit(nifty, vix),
        "SENSEX": base.feature_mutation_audit(sensex, vix),
    }
    mutation_max = max(mutation.values())
    if mutation_max != 0.0:
        raise RuntimeError(f"future mutation gate failed: {mutation_max}")

    measured_leakage = {
        key: nleak[key] + sleak[key]
        for key in nleak
    }
    if any(measured_leakage.values()):
        raise RuntimeError(f"chronology leakage detected: {measured_leakage}")

    train_specs = [files["nifty_train"], files["sensex_train"], files["cross_train"]]
    val_specs = [files["nifty_validation"], files["sensex_validation"], files["cross_validation"]]
    train_min, train_max = date_range(train_specs)
    val_min, val_max = date_range(val_specs)

    manifest = {
        "format": "VARDHANI_SPECIALIST_R3_FULL_MARKET_CORPUS_MANIFEST_V2",
        "supersedes": "VARDHANI_SPECIALIST_R3_FULL_MARKET_CORPUS_MANIFEST_V1",
        "raw_source_sha256": base.RAW_SHA,
        "sealed_2026": True,
        "execution_authority": False,
        "files": files,
        "train": {
            "artifact_keys": ["nifty_train", "sensex_train", "cross_train"],
            "rows_market_streams": int(files["nifty_train"]["rows"] + files["sensex_train"]["rows"]),
            "rows_cross_stream": int(files["cross_train"]["rows"]),
            "min_timestamp": train_min,
            "max_timestamp": train_max,
            "artifact_ledger_sha256": aggregate_digest(train_specs),
        },
        "validation": {
            "artifact_keys": ["nifty_validation", "sensex_validation", "cross_validation"],
            "rows_market_streams": int(files["nifty_validation"]["rows"] + files["sensex_validation"]["rows"]),
            "rows_cross_stream": int(files["cross_validation"]["rows"]),
            "min_timestamp": val_min,
            "max_timestamp": val_max,
            "artifact_ledger_sha256": aggregate_digest(val_specs),
        },
        "feature_schema": feature_schema,
        "measured_leakage": measured_leakage,
        "sealed_2026_rows_rejected": int(np.count_nonzero(ns) + np.count_nonzero(ss)),
        "source_stats": {"NIFTY": nstats, "SENSEX": sstats, "VIX": vstats},
        "stagea_parity": {
            "NIFTY_clean_rows_expected": EXPECTED_CLEAN_ROWS["NIFTY"],
            "NIFTY_clean_rows_actual": len(nifty),
            "SENSEX_clean_rows_expected": EXPECTED_CLEAN_ROWS["SENSEX"],
            "SENSEX_clean_rows_actual": len(sensex),
            "pass": True,
        },
        "modality_coverage": {
            "NIFTY_rows_observed_only": len(nifty),
            "SENSEX_rows_observed_only": len(sensex),
            "VIX_rows_after_clean": len(vix),
            "cross_train_rows": files["cross_train"]["rows"],
            "cross_validation_rows": files["cross_validation"]["rows"],
            "NIFTY_options_in_this_builder": False,
            "SENSEX_options_in_this_builder": False,
        },
        "future_mutation_audit": {"per_index": mutation, "pre_cut_max_abs_change": mutation_max},
        "hard_rules": {
            "option_overlay_separate": True,
            "sensex_options_fabricated": False,
            "historical_d30_used": False,
            "historical_index_volume_oi_used": False,
            "real_orders": "DISABLED",
        },
    }
    manifest_path = output / "R3_FULL_MARKET_CORPUS_MANIFEST_V2.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True, default=str) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_zip", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        report = build(args.raw_zip, args.output)
    except Exception as exc:
        print(json.dumps({"status": "FAIL_CLOSED", "error": str(exc), "real_orders": "DISABLED"}, indent=2))
        return 2
    print(json.dumps({
        "status": "BUILT_WAITING_FOR_TEACHER_PREFLIGHT_V2",
        "manifest": str(args.output / "R3_FULL_MARKET_CORPUS_MANIFEST_V2.json"),
        "stagea_parity": report["stagea_parity"],
        "future_mutation": report["future_mutation_audit"],
        "real_orders": "DISABLED",
    }, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
