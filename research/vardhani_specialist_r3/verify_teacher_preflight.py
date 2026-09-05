#!/usr/bin/env python3
"""Fail-closed preflight for VARDHANI Specialist R3 teacher training.

No optimizer is created here.  The script proves that the recovery unlock token, full-market
corpus, option projection, actual architecture implementation and immutable R2 anchors all
match their precommitted contracts.  Any missing or unverifiable item keeps training locked.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

TOKEN_FORMAT = "VARDHANI_SPECIALIST_R3_TEACHER_UNLOCK_TOKEN_V1"
TOKEN_STATUS = "TEACHER_HISTORICAL_TRAINING_UNLOCKED_ONLY"
ARCH_FORMAT = "VARDHANI_SPECIALIST_R3_ARCHITECTURE_MANIFEST_V1"
ARCH_STATUS = "SEALED_IMPLEMENTATION_READY_FOR_GATED_TRAINING"
FULL_MARKET_FORMAT = "VARDHANI_SPECIALIST_R3_FULL_MARKET_CORPUS_MANIFEST_V1"
RAW_SHA = "bd7df469f6e7d95bee62a7c51d794a9119478cbc3c95b1e68debcafb4adc5b20"
TRAIN_END = "2024-12-31"
VAL_START = "2025-01-01"
VAL_END = "2025-12-31"

FROZEN_R2 = {
    "NIFTY_STAGE1": "47312e180dc900f5d05711aa862b7ef82ece6d0ae32a36c607f8efae305bb8e5",
    "NIFTY_STAGE2": "55791e80e6c10c9c5476c719b97b053b960145bb10894ef6208658eb4121bf1d",
    "NIFTY_STAGE3": "6378c36f558ff6a3b292b8d7f74418f7eb1ce38142c235f4cd96d8d8cc49363d",
    "SENSEX_STAGE1": "71b34d978f0efaef7e3faaf0d2509c893deb44b291f8b5125b6150da3034ba63",
    "SENSEX_STAGE2": "a8c97ba5a8df86d246def3d0f1a741aa0adc8afab7be4507a2fdd546142ecd0c",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def load_json(path: Path, failures: list[str], label: str) -> dict[str, Any]:
    if not path.is_file():
        failures.append(f"{label} missing: {path}")
        return {}
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(obj, dict):
            raise TypeError("top-level JSON must be an object")
        return obj
    except Exception as exc:
        failures.append(f"{label} unreadable: {exc}")
        return {}


def require_eq(failures: list[str], label: str, actual: Any, expected: Any) -> None:
    if actual != expected:
        failures.append(f"{label}: expected {expected!r}, got {actual!r}")


def require_hash(failures: list[str], label: str, value: Any) -> None:
    if not isinstance(value, str) or len(value) != 64 or any(c not in "0123456789abcdef" for c in value.lower()):
        failures.append(f"{label}: invalid SHA-256")


def verify_file(root: Path, spec: dict[str, Any], failures: list[str], label: str) -> Path | None:
    rel = spec.get("path")
    expected_sha = spec.get("sha256")
    if not isinstance(rel, str) or not rel:
        failures.append(f"{label}.path missing")
        return None
    require_hash(failures, f"{label}.sha256", expected_sha)
    path = (root / rel).resolve()
    try:
        path.relative_to(root.resolve())
    except Exception:
        failures.append(f"{label}: path escapes declared root")
        return None
    if not path.is_file():
        failures.append(f"{label}: file missing {rel}")
        return None
    if "bytes" in spec and int(spec.get("bytes", -1)) != path.stat().st_size:
        failures.append(f"{label}: byte-size mismatch")
    actual = sha256(path)
    if actual != expected_sha:
        failures.append(f"{label}: SHA-256 mismatch")
    return path


def verify_token(token: dict[str, Any], projection_dir: Path, failures: list[str]) -> None:
    require_eq(failures, "token.format", token.get("format"), TOKEN_FORMAT)
    require_eq(failures, "token.status", token.get("status"), TOKEN_STATUS)
    scope = token.get("training_scope") or {}
    require_eq(failures, "token.train_end", scope.get("train_end"), TRAIN_END)
    require_eq(failures, "token.validation_start", scope.get("validation_start"), VAL_START)
    require_eq(failures, "token.validation_end", scope.get("validation_end"), VAL_END)
    require_eq(failures, "token.sealed_2026", scope.get("sealed_2026"), True)
    require_eq(failures, "token.prospective_authority", scope.get("prospective_authority"), False)
    require_eq(failures, "token.promotion_authority", scope.get("promotion_authority"), False)
    require_eq(failures, "token.execution_authority", scope.get("execution_authority"), False)
    require_eq(failures, "token.raw_source.sha256", (token.get("raw_source") or {}).get("sha256"), RAW_SHA)
    require_eq(failures, "token.frozen_r2_anchors", token.get("frozen_r2_anchors"), FROZEN_R2)
    require_eq(failures, "token.real_orders", token.get("real_orders"), "DISABLED")

    r3 = token.get("r3_projection") or {}
    measured = r3.get("measured_counters") or {}
    for key in [
        "train_2025_rows",
        "train_2026_or_later_rows",
        "validation_pre_2025_rows",
        "validation_2026_or_later_rows",
    ]:
        if key not in measured:
            failures.append(f"token measured counter missing: {key}")
        elif int(measured.get(key, -1)) != 0:
            failures.append(f"token chronology leakage {key}={measured.get(key)}")
    if int(measured.get("train_rows", 0)) <= 0:
        failures.append("token train_rows must be positive")
    if int(measured.get("validation_rows", 0)) <= 0:
        failures.append("token validation_rows must be positive")

    train = projection_dir / "options_train_through_2024.ndjson"
    val = projection_dir / "options_validation_2025.ndjson"
    if not train.is_file():
        failures.append("R3 option training projection missing")
    else:
        expected = r3.get("train_file_sha256")
        require_hash(failures, "token.r3_projection.train_file_sha256", expected)
        if sha256(train) != expected:
            failures.append("R3 option training projection SHA-256 mismatch")
    if not val.is_file():
        failures.append("R3 option validation projection missing")
    else:
        expected = r3.get("validation_file_sha256")
        require_hash(failures, "token.r3_projection.validation_file_sha256", expected)
        if sha256(val) != expected:
            failures.append("R3 option validation projection SHA-256 mismatch")
    require_hash(failures, "token.r3_projection.source_canonical_row_stream_sha256", r3.get("source_canonical_row_stream_sha256"))
    require_hash(failures, "token.r3_projection.train_canonical_row_stream_sha256", r3.get("train_canonical_row_stream_sha256"))
    require_hash(failures, "token.r3_projection.validation_canonical_row_stream_sha256", r3.get("validation_canonical_row_stream_sha256"))


def verify_full_market(manifest: dict[str, Any], root: Path, failures: list[str]) -> None:
    require_eq(failures, "full_market.format", manifest.get("format"), FULL_MARKET_FORMAT)
    require_eq(failures, "full_market.raw_source_sha256", manifest.get("raw_source_sha256"), RAW_SHA)
    require_eq(failures, "full_market.execution_authority", manifest.get("execution_authority"), False)
    require_eq(failures, "full_market.sealed_2026", manifest.get("sealed_2026"), True)

    train = manifest.get("train") or {}
    val = manifest.get("validation") or {}
    verify_file(root, train, failures, "full_market.train")
    verify_file(root, val, failures, "full_market.validation")
    if int(train.get("rows", 0)) <= 0:
        failures.append("full_market.train.rows must be positive")
    if int(val.get("rows", 0)) <= 0:
        failures.append("full_market.validation.rows must be positive")
    if str(train.get("max_timestamp", ""))[:10] > TRAIN_END:
        failures.append("full-market training extends beyond 2024-12-31")
    val_min = str(val.get("min_timestamp", ""))[:10]
    val_max = str(val.get("max_timestamp", ""))[:10]
    if not val_min or val_min < VAL_START:
        failures.append("full-market validation begins before 2025")
    if not val_max or val_max > VAL_END:
        failures.append("full-market validation extends beyond 2025")
    require_hash(failures, "full_market.train.canonical_row_stream_sha256", train.get("canonical_row_stream_sha256"))
    require_hash(failures, "full_market.validation.canonical_row_stream_sha256", val.get("canonical_row_stream_sha256"))

    leakage = manifest.get("measured_leakage") or {}
    for key in [
        "train_2025_rows",
        "train_2026_rows",
        "validation_pre_2025_rows",
        "validation_2026_rows",
    ]:
        if key not in leakage:
            failures.append(f"full-market measured leakage counter missing: {key}")
        elif int(leakage.get(key, -1)) != 0:
            failures.append(f"full-market chronology leakage {key}={leakage.get(key)}")

    feature_schema = manifest.get("feature_schema") or {}
    verify_file(root, feature_schema, failures, "full_market.feature_schema")
    require_hash(failures, "full_market.feature_schema.sha256", feature_schema.get("sha256"))
    mutation = manifest.get("future_mutation_audit") or {}
    if float(mutation.get("pre_cut_max_abs_change", float("inf"))) != 0.0:
        failures.append("full-market future-mutation causal gate is not exactly zero")
    coverage = manifest.get("modality_coverage")
    if not isinstance(coverage, dict) or not coverage:
        failures.append("full-market modality_coverage missing")


def architecture_bundle_digest(files: list[dict[str, Any]]) -> str:
    h = hashlib.sha256()
    for item in sorted(files, key=lambda x: str(x.get("path", ""))):
        h.update(str(item.get("path", "")).encode("utf-8"))
        h.update(b"\0")
        h.update(str(item.get("bytes", "")).encode("ascii"))
        h.update(b"\0")
        h.update(str(item.get("sha256", "")).encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()


def verify_architecture(manifest: dict[str, Any], root: Path, full_market: dict[str, Any], failures: list[str]) -> None:
    require_eq(failures, "architecture.format", manifest.get("format"), ARCH_FORMAT)
    require_eq(failures, "architecture.status", manifest.get("status"), ARCH_STATUS)
    identity = manifest.get("identity") or {}
    if int(identity.get("exact_parameter_count", 0)) <= 0:
        failures.append("architecture exact_parameter_count must be positive")
    files = identity.get("source_files")
    if not isinstance(files, list) or not files:
        failures.append("architecture source_files missing")
        files = []
    for index, spec in enumerate(files):
        if not isinstance(spec, dict):
            failures.append(f"architecture source_files[{index}] invalid")
            continue
        verify_file(root, spec, failures, f"architecture.source_files[{index}]")
    expected_bundle = identity.get("source_bundle_sha256")
    require_hash(failures, "architecture.source_bundle_sha256", expected_bundle)
    if files and expected_bundle != architecture_bundle_digest(files):
        failures.append("architecture source_bundle_sha256 does not match declared source-file ledger")

    input_contract = manifest.get("input_contract") or {}
    full_schema_sha = ((full_market.get("feature_schema") or {}).get("sha256"))
    require_eq(
        failures,
        "architecture.full_market_feature_schema_sha256",
        input_contract.get("full_market_feature_schema_sha256"),
        full_schema_sha,
    )
    require_eq(failures, "architecture.15m_policy", input_contract.get("15m_policy"), "CONTEXT_NON_VETO")
    for field in ["option_feature_schema_sha256", "modality_mask_schema_sha256"]:
        require_hash(failures, f"architecture.{field}", input_contract.get(field))

    model = manifest.get("model_contract") or {}
    for field in [
        "temporal_encoder", "multi_timeframe_fusion", "specialist_experts", "meta_judgement",
        "uncertainty_head", "episodic_embedding_head", "output_heads",
    ]:
        if field not in model or model.get(field) in (None, "", [], {}):
            failures.append(f"architecture model_contract.{field} missing")

    training = manifest.get("training_contract") or {}
    for field in ["loss_heads", "loss_weights", "optimizer", "scheduler", "deterministic_seeds", "precision_policy", "gradient_clipping", "checkpoint_selection_metric"]:
        if field not in training or training.get(field) in (None, "", [], {}):
            failures.append(f"architecture training_contract.{field} missing")

    safety = manifest.get("safety_contract") or {}
    for field in ["frozen_r2_untouched", "validation_gradients_forbidden", "sealed_2026", "historical_d30_forbidden", "fabricated_modalities_forbidden", "real_orders_disabled"]:
        require_eq(failures, f"architecture safety_contract.{field}", safety.get(field), True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("unlock_token", type=Path)
    parser.add_argument("architecture_manifest", type=Path)
    parser.add_argument("architecture_root", type=Path)
    parser.add_argument("full_market_manifest", type=Path)
    parser.add_argument("full_market_root", type=Path)
    parser.add_argument("option_projection_dir", type=Path)
    parser.add_argument("--output", type=Path, default=Path("R3_TEACHER_PREFLIGHT_REPORT.json"))
    args = parser.parse_args()

    failures: list[str] = []
    token = load_json(args.unlock_token, failures, "unlock token")
    full_market = load_json(args.full_market_manifest, failures, "full-market manifest")
    architecture = load_json(args.architecture_manifest, failures, "architecture manifest")
    if token:
        verify_token(token, args.option_projection_dir, failures)
    if full_market:
        verify_full_market(full_market, args.full_market_root, failures)
    if architecture:
        verify_architecture(architecture, args.architecture_root, full_market, failures)

    allowed = not failures
    result = {
        "format": "VARDHANI_SPECIALIST_R3_TEACHER_PREFLIGHT_REPORT_V1",
        "teacher_optimizer_start_allowed": allowed,
        "model_construction_allowed": allowed,
        "training_scope": "<=2024 only" if allowed else "LOCKED",
        "validation_scope": "2025 read-only" if allowed else "LOCKED",
        "sealed_2026": True,
        "frozen_r2_anchors": FROZEN_R2,
        "real_orders": "DISABLED",
        "failures": failures,
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if allowed else 2


if __name__ == "__main__":
    sys.exit(main())
