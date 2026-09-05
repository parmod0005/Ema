from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

import verify_teacher_preflight as pre


def write(root: Path, name: str, data: bytes) -> dict:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


class TeacherPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.proj = self.root / "projection"
        self.market_root = self.root / "market"
        self.arch_root = self.root / "arch"
        self.proj.mkdir()
        self.market_root.mkdir()
        self.arch_root.mkdir()

        self.option_train = write(self.proj, "options_train_through_2024.ndjson", b"train-option\n")
        self.option_val = write(self.proj, "options_validation_2025.ndjson", b"val-option\n")
        self.market_train = write(self.market_root, "train.ndjson", b"train-market\n")
        self.market_val = write(self.market_root, "validation.ndjson", b"val-market\n")
        self.feature_schema = write(self.market_root, "feature_schema.json", b"{}\n")
        self.arch_source = write(self.arch_root, "model.py", b"# immutable architecture\n")

    def tearDown(self):
        self.temp.cleanup()

    def token(self):
        return {
            "format": pre.TOKEN_FORMAT,
            "status": pre.TOKEN_STATUS,
            "training_scope": {
                "train_end": pre.TRAIN_END,
                "validation_start": pre.VAL_START,
                "validation_end": pre.VAL_END,
                "sealed_2026": True,
                "prospective_authority": False,
                "promotion_authority": False,
                "execution_authority": False,
            },
            "raw_source": {"sha256": pre.RAW_SHA},
            "frozen_r2_anchors": pre.FROZEN_R2,
            "r3_projection": {
                "train_file_sha256": self.option_train["sha256"],
                "validation_file_sha256": self.option_val["sha256"],
                "source_canonical_row_stream_sha256": "1" * 64,
                "train_canonical_row_stream_sha256": "2" * 64,
                "validation_canonical_row_stream_sha256": "3" * 64,
                "measured_counters": {
                    "source_rows": 3,
                    "train_rows": 1,
                    "validation_rows": 1,
                    "train_2025_rows": 0,
                    "train_2026_or_later_rows": 0,
                    "validation_pre_2025_rows": 0,
                    "validation_2026_or_later_rows": 0,
                },
            },
            "real_orders": "DISABLED",
        }

    def full_market(self):
        return {
            "format": pre.FULL_MARKET_FORMAT,
            "raw_source_sha256": pre.RAW_SHA,
            "execution_authority": False,
            "sealed_2026": True,
            "train": {
                **self.market_train,
                "rows": 1,
                "min_timestamp": "2022-01-03T09:15:00+05:30",
                "max_timestamp": "2024-12-31T15:29:00+05:30",
                "canonical_row_stream_sha256": "4" * 64,
            },
            "validation": {
                **self.market_val,
                "rows": 1,
                "min_timestamp": "2025-01-01T09:15:00+05:30",
                "max_timestamp": "2025-12-31T15:29:00+05:30",
                "canonical_row_stream_sha256": "5" * 64,
            },
            "feature_schema": self.feature_schema,
            "measured_leakage": {
                "train_2025_rows": 0,
                "train_2026_rows": 0,
                "validation_pre_2025_rows": 0,
                "validation_2026_rows": 0,
            },
            "future_mutation_audit": {"pre_cut_max_abs_change": 0.0},
            "modality_coverage": {"NIFTY": 1.0, "SENSEX": 1.0, "VIX": 0.9},
        }

    def architecture(self):
        files = [self.arch_source]
        return {
            "format": pre.ARCH_FORMAT,
            "status": pre.ARCH_STATUS,
            "identity": {
                "teacher_name": "test",
                "architecture_version": "test",
                "exact_parameter_count": 568000000,
                "source_files": files,
                "source_bundle_sha256": pre.architecture_bundle_digest(files),
            },
            "input_contract": {
                "full_market_feature_schema_sha256": self.feature_schema["sha256"],
                "option_feature_schema_sha256": "6" * 64,
                "modality_mask_schema_sha256": "7" * 64,
                "state_cache_contract": "streaming",
                "15m_policy": "CONTEXT_NON_VETO",
            },
            "model_contract": {
                "temporal_encoder": "x",
                "multi_timeframe_fusion": "x",
                "specialist_experts": ["trend"],
                "meta_judgement": "x",
                "uncertainty_head": "x",
                "episodic_embedding_head": "x",
                "output_heads": ["action"],
            },
            "training_contract": {
                "loss_heads": ["action"],
                "loss_weights": {"action": 1.0},
                "optimizer": {"name": "test"},
                "scheduler": {"name": "test"},
                "deterministic_seeds": [1],
                "precision_policy": "test",
                "gradient_clipping": 1.0,
                "checkpoint_selection_metric": "test",
            },
            "safety_contract": {
                "frozen_r2_untouched": True,
                "validation_gradients_forbidden": True,
                "sealed_2026": True,
                "historical_d30_forbidden": True,
                "fabricated_modalities_forbidden": True,
                "real_orders_disabled": True,
            },
        }

    def test_valid_contract_components_pass(self):
        failures = []
        token = self.token()
        market = self.full_market()
        arch = self.architecture()
        pre.verify_token(token, self.proj, failures)
        pre.verify_full_market(market, self.market_root, failures)
        pre.verify_architecture(arch, self.arch_root, market, failures)
        self.assertEqual(failures, [])

    def test_token_measured_2025_training_leakage_fails(self):
        token = self.token()
        token["r3_projection"]["measured_counters"]["train_2025_rows"] = 1
        failures = []
        pre.verify_token(token, self.proj, failures)
        self.assertTrue(any("leakage" in f for f in failures))

    def test_full_market_future_mutation_nonzero_fails(self):
        market = self.full_market()
        market["future_mutation_audit"]["pre_cut_max_abs_change"] = 1e-9
        failures = []
        pre.verify_full_market(market, self.market_root, failures)
        self.assertTrue(any("future-mutation" in f for f in failures))

    def test_full_market_2026_validation_fails(self):
        market = self.full_market()
        market["validation"]["max_timestamp"] = "2026-01-02T15:29:00+05:30"
        market["measured_leakage"]["validation_2026_rows"] = 1
        failures = []
        pre.verify_full_market(market, self.market_root, failures)
        self.assertTrue(any("2026" in f or "beyond 2025" in f for f in failures))

    def test_architecture_missing_exact_parameter_count_fails(self):
        arch = self.architecture()
        arch["identity"]["exact_parameter_count"] = 0
        failures = []
        pre.verify_architecture(arch, self.arch_root, self.full_market(), failures)
        self.assertTrue(any("exact_parameter_count" in f for f in failures))

    def test_architecture_source_hash_mismatch_fails(self):
        arch = self.architecture()
        arch["identity"]["source_files"][0]["sha256"] = "f" * 64
        arch["identity"]["source_bundle_sha256"] = pre.architecture_bundle_digest(arch["identity"]["source_files"])
        failures = []
        pre.verify_architecture(arch, self.arch_root, self.full_market(), failures)
        self.assertTrue(any("SHA-256 mismatch" in f for f in failures))

    def test_architecture_15m_veto_policy_fails(self):
        arch = self.architecture()
        arch["input_contract"]["15m_policy"] = "DIRECT_VETO"
        failures = []
        pre.verify_architecture(arch, self.arch_root, self.full_market(), failures)
        self.assertTrue(any("15m_policy" in f for f in failures))

    def test_architecture_real_orders_enabled_fails(self):
        arch = self.architecture()
        arch["safety_contract"]["real_orders_disabled"] = False
        failures = []
        pre.verify_architecture(arch, self.arch_root, self.full_market(), failures)
        self.assertTrue(any("real_orders_disabled" in f for f in failures))


if __name__ == "__main__":
    unittest.main()
