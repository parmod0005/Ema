from __future__ import annotations

import unittest

import rebuild_aiml_option_corpus as rebuild
import verify_corpus_recovery as gate
import verify_corpus_recovery_v2 as gate_v2


HASH64 = "0" * 64


class RecoveryContractTests(unittest.TestCase):
    def candle(self, minute: int, *, close: float = 100.0, high: float = 101.0, low: float = 99.0, date: str = "2024-12-30"):
        # 09:15 IST = 03:45 UTC; only relative one-minute spacing matters here.
        epoch = 1_735_531_500 + minute * 60
        return rebuild.Candle(
            epoch_second=epoch,
            source_timestamp=f"{date}T09:{15 + minute:02d}:00+05:30",
            session_date=date,
            open=100.0,
            high=high,
            low=low,
            close=close,
            volume=10.0,
            oi=100.0,
        )

    def valid_r3_report(self) -> dict:
        return {
            "format": "VARDHANI_SPECIALIST_R3_SPLIT_REPORT_V2",
            "configured_train_cutoff": gate.R3_TRAIN_CUTOFF,
            "configured_validation_start": gate.R3_VALIDATION_START,
            "configured_validation_end": gate.R3_VALIDATION_END,
            "train_min_exchange_date": "2024-09-20",
            "train_max_exchange_date": "2024-12-31",
            "validation_min_exchange_date": "2025-01-02",
            "validation_max_exchange_date": "2025-04-17",
            "contains_2025_training_rows": False,
            "contains_2026_training_rows": False,
            "contains_2026_validation_rows": False,
            "contains_pre_2025_validation_rows": False,
            "sealed_2026": True,
            "execution_authority": False,
            "source_canonical_row_stream_sha256": HASH64,
            "source_split_counts": {"train": 1, "validation": 1, "test": 1},
            "projected_split_counts": {"r3_train": 1, "r3_validation": 1},
            "counters": {
                "source_rows": 3,
                "train_rows": 1,
                "validation_rows": 1,
                "train_2025_rows": 0,
                "train_2026_or_later_rows": 0,
                "validation_pre_2025_rows": 0,
                "validation_2026_or_later_rows": 0,
                "sealed_2026_rows_rejected": 0,
            },
            "outputs": {
                "options_train_through_2024.ndjson": {"sha256": HASH64, "canonical_row_stream_sha256": HASH64},
                "options_validation_2025.ndjson": {"sha256": HASH64, "canonical_row_stream_sha256": HASH64},
            },
        }

    def test_29_expiry_split_is_original_20_4_5(self):
        expiries = [f"2025-01-{i:02d}" for i in range(1, 30)]
        split = rebuild.expiry_split(expiries)
        counts = {name: sum(v == name for v in split.values()) for name in rebuild.SPLITS}
        self.assertEqual(counts, {"train": 20, "validation": 4, "test": 5})

    def test_labels_require_exact_future_minutes(self):
        rows = [self.candle(i, close=100.0 + i) for i in range(16)]
        lookup = {r.epoch_second: r for r in rows}
        out = rebuild.labels(rows[0], lookup)
        self.assertIsNotNone(out)
        self.assertAlmostEqual(out["future_return_1m"], 0.01)
        self.assertAlmostEqual(out["future_return_15m"], 0.15)
        del lookup[rows[3].epoch_second]
        self.assertIsNone(rebuild.labels(rows[0], lookup))

    def test_labels_never_cross_exchange_date(self):
        current = self.candle(0, date="2024-12-30")
        next_day = rebuild.Candle(
            epoch_second=current.epoch_second + 60,
            source_timestamp="2024-12-31T09:15:00+05:30",
            session_date="2024-12-31",
            open=100.0,
            high=101.0,
            low=99.0,
            close=100.0,
            volume=10.0,
            oi=100.0,
        )
        self.assertIsNone(rebuild.labels(current, {next_day.epoch_second: next_day}))

    def test_duplicate_candle_file_is_rejected(self):
        row = self.candle(0)
        parsed = rebuild.ParsedCandles(rows=[row], duplicates=1, invalid=0)
        q = rebuild.assess_quality(parsed)
        self.assertEqual(q.disposition, "REJECT_INVALID")

    def test_r3_chronology_accepts_trading_day_bounds(self):
        self.assertEqual(gate_v2.compare_r3_split_report_v2(self.valid_r3_report()), [])

    def test_r3_chronology_rejects_measured_2025_training(self):
        report = self.valid_r3_report()
        report["train_max_exchange_date"] = "2025-01-02"
        report["contains_2025_training_rows"] = True
        report["counters"]["train_2025_rows"] = 1
        failures = gate_v2.compare_r3_split_report_v2(report)
        self.assertTrue(any("2025" in f or "exceeds" in f for f in failures))

    def test_r3_chronology_rejects_missing_row_hash(self):
        report = self.valid_r3_report()
        report["source_canonical_row_stream_sha256"] = None
        failures = gate_v2.compare_r3_split_report_v2(report)
        self.assertTrue(any("row-stream" in f for f in failures))

    def test_r3_chronology_rejects_count_reconciliation_error(self):
        report = self.valid_r3_report()
        report["projected_split_counts"]["r3_train"] = 2
        failures = gate_v2.compare_r3_split_report_v2(report)
        self.assertTrue(any("reconcile" in f for f in failures))


if __name__ == "__main__":
    unittest.main()
