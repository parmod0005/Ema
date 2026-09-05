#!/usr/bin/env python3
"""Build the full observed-only R3 market-experience corpus from the sealed Upstox archive.

The 74 lower-timeframe + 23 separate 15m-context features are a Python parity port of the
frozen Android R2FeatureBuilder semantics.  This builder does not train any model.  It emits
separate NIFTY/SENSEX streams plus exact common-timestamp mappings, split strictly into
<=2024 development and 2025 read-only validation.  2026 rows are counted and rejected.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

import numpy as np

import rebuild_aiml_option_corpus as source

RAW_SHA = "bd7df469f6e7d95bee62a7c51d794a9119478cbc3c95b1e68debcafb4adc5b20"
TRAIN_END = "2024-12-31"
VAL_START = "2025-01-01"
VAL_END = "2025-12-31"

LOWER_FEATURES = [
    "ret1","body_atr","range_atr","upper_wick_atr","lower_wick_atr","atr_pct","ema_sep_atr","ema9_slope_atr",
    "rsi14","macdh_atr","adx","di_spread","bb_pos","bb_width_atr","loc20","breakout20","breakdown20","rv5",
    "rv15","rv30","eff10","eff20","from_open_atr","from_high_atr","from_low_atr","session_progress","minute_sin",
    "minute_cos","dow","gap_before_min","gap_event","overnight_ret","calendar_gap_days","weekend_gap","ready20",
    "vix_level","vix_ret1","vix_range","vix_ema_sep","vix_exact","vix_age","vix_available",
    "tf3_form_body_atr","tf3_form_range_atr","tf3_form_close_loc","tf3_progress","tf3_coverage","tf3_missing_sofar",
    "tf3_nominal_close","tf3_complete_close","tf3_closed_bar_ret","tf3_closed_bar_range_atr","tf3_closed_ema_sep_atr",
    "tf3_closed_rsi9","tf3_closed_macdh_atr","tf3_closed_loc20","tf3_closed_age","tf3_closed_available",
    "tf5_form_body_atr","tf5_form_range_atr","tf5_form_close_loc","tf5_progress","tf5_coverage","tf5_missing_sofar",
    "tf5_nominal_close","tf5_complete_close","tf5_closed_bar_ret","tf5_closed_bar_range_atr","tf5_closed_ema_sep_atr",
    "tf5_closed_rsi9","tf5_closed_macdh_atr","tf5_closed_loc20","tf5_closed_age","tf5_closed_available",
]
CONTEXT15_FEATURES = [
    "tf15_form_body_atr","tf15_form_range_atr","tf15_form_close_loc","tf15_progress","tf15_coverage","tf15_missing_sofar",
    "tf15_nominal_close","tf15_complete_close","tf15_closed_bar_ret","tf15_closed_bar_range_atr","tf15_closed_ema_sep_atr",
    "tf15_closed_rsi9","tf15_closed_macdh_atr","tf15_closed_loc20","tf15_closed_age","tf15_closed_available",
    "session_progress","gap_event","overnight_ret","weekend_gap","vix_level","vix_age","vix_available",
]
assert len(LOWER_FEATURES) == 74 and len(CONTEXT15_FEATURES) == 23


@dataclass(frozen=True)
class MarketRow:
    epoch_second: int
    source_timestamp: str
    day: str
    session_minute: int
    open: float
    high: float
    low: float
    close: float


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


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


def parse_session_minute(timestamp: str) -> int:
    dt = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        raise ValueError("market timestamp must be timezone-aware")
    # Archive timestamps are exchange-local; preserve the displayed local clock.
    sm = dt.hour * 60 + dt.minute - 555
    return sm


def load_symbol(archive: source.Archive, prefix: str) -> tuple[list[MarketRow], dict[str, int]]:
    files = archive.relative_files(prefix.rstrip("/") + "/minutes-1/", ".json")
    if not files:
        raise RuntimeError(f"no historical files for {prefix}")
    rows: dict[int, MarketRow] = {}
    integrity_counts: dict[str, int] = {}
    invalid = duplicates = 0
    for name in files:
        integrity = archive.verify(name)
        integrity_counts[integrity] = integrity_counts.get(integrity, 0) + 1
        if integrity in source.INTEGRITY_REJECTIONS:
            raise RuntimeError(f"integrity rejection for {name}: {integrity}")
        parsed = source.read_candles_bytes(archive.read(name))
        invalid += parsed.invalid
        duplicates += parsed.duplicates
        for c in parsed.rows:
            sm = parse_session_minute(c.source_timestamp)
            if sm < 0 or sm >= 375:
                continue
            if min(c.open, c.high, c.low, c.close) <= 0:
                continue
            if c.high < max(c.open, c.close, c.low) or c.low > min(c.open, c.close, c.high):
                continue
            rows[c.epoch_second] = MarketRow(
                epoch_second=c.epoch_second,
                source_timestamp=c.source_timestamp,
                day=c.session_date,
                session_minute=sm,
                open=c.open,
                high=c.high,
                low=c.low,
                close=c.close,
            )
    ordered = sorted(rows.values(), key=lambda r: r.epoch_second)
    by_day: dict[str, list[MarketRow]] = {}
    for row in ordered:
        by_day.setdefault(row.day, []).append(row)
    rejected_days = {d for d, rs in by_day.items() if max(r.high for r in rs) - min(r.low for r in rs) == 0.0}
    clean = [r for r in ordered if r.day not in rejected_days]
    return clean, {
        "source_files": len(files),
        "parse_invalid_rows": invalid,
        "parse_duplicate_rows": duplicates,
        "rejected_zero_range_sessions": len(rejected_days),
        "rejected_zero_range_rows": sum(len(by_day[d]) for d in rejected_days),
        "rows_after_clean": len(clean),
        "verified_files": integrity_counts.get("VERIFIED", 0),
    }


def std_last(values: list[float], width: int) -> float:
    if not values:
        return 0.0
    xs = values[-width:]
    mu = sum(xs) / len(xs)
    var = sum((x - mu) ** 2 for x in xs) / len(xs)
    return math.sqrt(var) * math.sqrt(float(width))


def align_vix(market: list[MarketRow], vix: list[MarketRow]) -> np.ndarray:
    vf: list[tuple[float, float, float, float]] = []
    e9 = e21 = prev = 0.0
    current_day = None
    for c in vix:
        if c.day != current_day:
            current_day = c.day
            e9 = c.close
            e21 = c.close
            vals = (c.close / 50.0, 0.0, (c.high - c.low) / c.close, 0.0)
        else:
            ret = c.close / prev - 1.0
            e9 += (2.0 / 10.0) * (c.close - e9)
            e21 += (2.0 / 22.0) * (c.close - e21)
            vals = (c.close / 50.0, ret, (c.high - c.low) / c.close, (e9 - e21) / c.close)
        prev = c.close
        vf.append(vals)
    out = np.zeros((len(market), 7), dtype=np.float64)
    j = -1
    for i, m in enumerate(market):
        while j + 1 < len(vix) and vix[j + 1].epoch_second <= m.epoch_second:
            j += 1
        if j >= 0:
            exact = 1.0 if vix[j].epoch_second == m.epoch_second else 0.0
            age = max(0.0, (m.epoch_second - vix[j].epoch_second) / 60.0 / 60.0)
            out[i, :4] = vf[j]
            out[i, 4:] = (exact, age, 1.0)
    return out


def build_features(market: list[MarketRow], vix: list[MarketRow]) -> tuple[np.ndarray, np.ndarray]:
    vx = align_vix(market, vix)
    xlo = np.zeros((len(market), 74), dtype=np.float32)
    x15 = np.zeros((len(market), 23), dtype=np.float32)

    atr = e9 = e21 = e12 = e26 = sig = 0.0
    rg = rl = dmp = dmm = adx = 0.0
    adx_started = False
    prev_session_close = 0.0
    has_prev_session = False
    current_day = prev_day = None
    session_rows: list[MarketRow] = []
    session_rets: list[float] = []
    sess_open = sess_high = sess_low = 0.0
    day_dow = calendar_gap = weekend_gap = 0.0

    tfs = (3, 5, 15)
    form_bucket = [-999, -999, -999]
    form_o = [0.0] * 3; form_h = [0.0] * 3; form_l = [0.0] * 3; form_c = [0.0] * 3; form_count = [0] * 3
    ha = [0.0] * 3; he9 = [0.0] * 3; he21 = [0.0] * 3; he12 = [0.0] * 3; he26 = [0.0] * 3; hsig = [0.0] * 3
    hrg = [0.0] * 3; hrl = [0.0] * 3; hprev = [0.0] * 3
    hhas = [False] * 3; hlast_ts = [0] * 3; havail = [False] * 3; hlast = [[0.0] * 6 for _ in range(3)]
    ph = [[] for _ in range(3)]; pl = [[] for _ in range(3)]

    for i, candle in enumerate(market):
        sm = candle.session_minute
        is_new = i == 0 or candle.day != current_day
        if is_new:
            if i > 0:
                prev_session_close = market[i - 1].close
                has_prev_session = True
            today = date.fromisoformat(candle.day)
            gap_days = float((today - date.fromisoformat(prev_day)).days) if prev_day is not None else 0.0
            day_dow = today.weekday() / 6.0
            calendar_gap = gap_days
            weekend_gap = 1.0 if gap_days >= 3.0 else 0.0
            prev_day = candle.day
            current_day = candle.day
            atr = e9 = e21 = e12 = e26 = sig = 0.0
            rg = rl = dmp = dmm = adx = 0.0
            adx_started = False
            session_rows = []
            session_rets = []
            sess_open = candle.open
            sess_high = -float("inf")
            sess_low = float("inf")
            for q in range(3):
                form_bucket[q] = -999
                form_count[q] = 0

        sess_high = max(sess_high, candle.high)
        sess_low = min(sess_low, candle.low)
        sj = len(session_rows)
        if sj == 0:
            atr = candle.high - candle.low
            e9 = e21 = e12 = e26 = candle.close
            sig = 0.0
            ret = esep = ema9s = macdh = di = gap_before = gap_event = 0.0
            rsi = 0.5
            adx = 0.0
            overnight = candle.close / prev_session_close - 1.0 if has_prev_session else 0.0
        else:
            pc = session_rows[-1].close
            ret = candle.close / pc - 1.0
            tr = max(candle.high - candle.low, abs(candle.high - pc), abs(candle.low - pc))
            atr = (13.0 * atr + tr) / 14.0
            pe9 = e9
            e9 += (2.0 / 10.0) * (candle.close - e9)
            e21 += (2.0 / 22.0) * (candle.close - e21)
            ema9s = (e9 - pe9) / atr
            esep = (e9 - e21) / atr
            e12 += (2.0 / 13.0) * (candle.close - e12)
            e26 += (2.0 / 27.0) * (candle.close - e26)
            macd = e12 - e26
            sig += (2.0 / 10.0) * (macd - sig)
            macdh = (macd - sig) / atr
            dc = candle.close - pc
            rg = (13.0 * rg + max(dc, 0.0)) / 14.0
            rl = (13.0 * rl + max(-dc, 0.0)) / 14.0
            rsi = rg / (rg + rl) if rl > 0.0 else 0.5
            up = candle.high - session_rows[-1].high
            dn = session_rows[-1].low - candle.low
            pp = up if up > dn and up > 0.0 else 0.0
            qq = dn if dn > up and dn > 0.0 else 0.0
            dmp = (13.0 * dmp + pp) / 14.0
            dmm = (13.0 * dmm + qq) / 14.0
            dip = dmp / atr; dim = dmm / atr; di = dip - dim; den = dip + dim
            dx = abs(di) / den if den > 0 else 0.0
            if den > 0 and not adx_started:
                adx = dx; adx_started = True
            elif adx_started:
                adx = (13.0 * adx + dx) / 14.0
            raw_gap = sm - session_rows[-1].session_minute
            gap_before = float(min(20, max(0, raw_gap)))
            gap_event = 1.0 if raw_gap > 1 else 0.0
            overnight = 0.0

        session_rows.append(candle)
        session_rets.append(ret)
        recent = session_rows[-20:]
        mu = sum(r.close for r in recent) / len(recent)
        sd = math.sqrt(sum((r.close - mu) ** 2 for r in recent) / len(recent))
        hi = max(r.high for r in recent); lo = min(r.low for r in recent)
        bb_pos = (candle.close - (mu - 2.0 * sd)) / (4.0 * sd) if sd > 1e-9 else 0.5
        bb_width = min(2.0, 4.0 * sd / atr / 10.0)
        loc20 = (candle.close - lo) / (hi - lo) if hi > lo else 0.5
        breakout = breakdown = 0.0
        if sj > 0:
            prior = session_rows[max(0, len(session_rows) - 21):-1]
            phi = max(r.high for r in prior); plo = min(r.low for r in prior)
            breakout = 1.0 if candle.close > phi else 0.0
            breakdown = 1.0 if candle.close < plo else 0.0
        rv5 = std_last(session_rets, 5); rv15 = std_last(session_rets, 15); rv30 = std_last(session_rets, 30)
        eff10 = eff20 = 0.0
        if sj >= 10:
            den = sum(abs(session_rows[z].close - session_rows[z - 1].close) for z in range(sj - 9, sj + 1))
            eff10 = abs(session_rows[sj].close - session_rows[sj - 10].close) / den if den > 0 else 0.0
        if sj >= 20:
            den = sum(abs(session_rows[z].close - session_rows[z - 1].close) for z in range(sj - 19, sj + 1))
            eff20 = abs(session_rows[sj].close - session_rows[sj - 20].close) / den if den > 0 else 0.0
        session_progress = sm / 374.0
        angle = 2.0 * math.pi * sm / 375.0
        from_open = max(-20.0, min(20.0, (candle.close - sess_open) / atr))
        from_high = max(-20.0, min(20.0, (candle.close - sess_high) / atr))
        from_low = max(-20.0, min(20.0, (candle.close - sess_low) / atr))
        vals = [
            ret,(candle.close-candle.open)/atr,(candle.high-candle.low)/atr,(candle.high-max(candle.open,candle.close))/atr,
            (min(candle.open,candle.close)-candle.low)/atr,atr/candle.close,esep,ema9s,rsi,macdh,adx,di,bb_pos,bb_width,
            loc20,breakout,breakdown,rv5,rv15,rv30,eff10,eff20,from_open,from_high,from_low,session_progress,
            math.sin(angle),math.cos(angle),day_dow,gap_before,gap_event,overnight,calendar_gap,weekend_gap,1.0 if sj>=19 else 0.0,
            *vx[i].tolist(),
        ]
        xlo[i, :42] = np.asarray(vals, dtype=np.float32)

        hout = [[0.0] * 16 for _ in range(3)]
        for q, tf in enumerate(tfs):
            bucket = (sm // tf) * tf
            if form_bucket[q] != bucket:
                form_bucket[q] = bucket; form_o[q] = candle.open; form_h[q] = candle.high; form_l[q] = candle.low; form_c[q] = candle.close; form_count[q] = 1
            else:
                form_h[q] = max(form_h[q], candle.high); form_l[q] = min(form_l[q], candle.low); form_c[q] = candle.close; form_count[q] += 1
            off = sm % tf; expected = off + 1; progress = expected / tf; coverage = form_count[q] / expected
            missing = (expected - form_count[q]) / tf; nominal = 1.0 if off == tf - 1 else 0.0; complete = 1.0 if nominal > 0 and form_count[q] == tf else 0.0
            fbody = (form_c[q] - form_o[q]) / atr; fran = min(20.0, (form_h[q] - form_l[q]) / atr)
            floc = (form_c[q] - form_l[q]) / (form_h[q] - form_l[q]) if form_h[q] > form_l[q] else 0.5
            if complete > 0:
                cc, oo, hh, ll = form_c[q], form_o[q], form_h[q], form_l[q]
                ema = 0.0; rr = 0.5; mh = 0.0; lloc = 0.5
                if not hhas[q]:
                    ha[q] = hh - ll; he9[q] = he21[q] = he12[q] = he26[q] = cc; hsig[q] = 0.0
                else:
                    trh = max(hh - ll, abs(hh - hprev[q]), abs(ll - hprev[q])); ha[q] = (13.0 * ha[q] + trh) / 14.0
                    he9[q] += (2.0/10.0)*(cc-he9[q]); he21[q] += (2.0/22.0)*(cc-he21[q]); ema = (he9[q]-he21[q])/ha[q]
                    dc = cc-hprev[q]; hrg[q]=(8.0*hrg[q]+max(dc,0.0))/9.0; hrl[q]=(8.0*hrl[q]+max(-dc,0.0))/9.0; rr=hrg[q]/(hrg[q]+hrl[q]) if hrl[q]>0 else 0.5
                    he12[q]+=(2.0/13.0)*(cc-he12[q]); he26[q]+=(2.0/27.0)*(cc-he26[q]); mm=he12[q]-he26[q]; hsig[q]+=(2.0/10.0)*(mm-hsig[q]); mh=(mm-hsig[q])/ha[q]
                    if ph[q]:
                        phi=max(ph[q]); plo=min(pl[q]); lloc=(cc-plo)/(phi-plo) if phi>plo else 0.5
                hlast[q] = [cc/oo-1.0,(hh-ll)/ha[q],ema,rr,mh,lloc]
                ph[q].append(hh); pl[q].append(ll)
                if len(ph[q]) > 20: ph[q].pop(0); pl[q].pop(0)
                hprev[q]=cc; hhas[q]=True; hlast_ts[q]=candle.epoch_second; havail[q]=True
            age = min(1.0, max(0.0, ((candle.epoch_second - hlast_ts[q]) / 60.0) / (20.0 * tf))) if havail[q] else 0.0
            hout[q][:8] = [fbody,fran,floc,progress,coverage,missing,nominal,complete]
            hout[q][8:14] = hlast[q]
            hout[q][14] = age; hout[q][15] = 1.0 if havail[q] else 0.0

        xlo[i,42:58] = np.asarray(hout[0],np.float32); xlo[i,58:74] = np.asarray(hout[1],np.float32)
        x15[i,:16] = np.asarray(hout[2],np.float32)
        x15[i,16:] = np.asarray([vals[25],vals[30],vals[31],vals[33],vals[35],vals[40],vals[41]],np.float32)

    if not np.isfinite(xlo).all() or not np.isfinite(x15).all():
        raise RuntimeError("non-finite causal features")
    return xlo, x15


def stream_arrays(rows: list[MarketRow], xlo: np.ndarray, x15: np.ndarray, mask: np.ndarray) -> dict[str, np.ndarray]:
    ids = np.flatnonzero(mask)
    return {
        "TS_EPOCH_SECOND": np.asarray([rows[i].epoch_second for i in ids], dtype=np.int64),
        "DAY": np.asarray([rows[i].day for i in ids], dtype="U10"),
        "SESSION_MIN": np.asarray([rows[i].session_minute for i in ids], dtype=np.int16),
        "CLOSE": np.asarray([rows[i].close for i in ids], dtype=np.float64),
        "XLO": xlo[ids].astype(np.float32, copy=False),
        "X15": x15[ids].astype(np.float32, copy=False),
    }


def merged_arrays(nifty: tuple[list[MarketRow], np.ndarray, np.ndarray], sensex: tuple[list[MarketRow], np.ndarray, np.ndarray], split: str) -> dict[str, np.ndarray]:
    nr,nlo,n15 = nifty; sr,slo,s15 = sensex
    def allowed(day: str) -> bool:
        return day <= TRAIN_END if split == "train" else VAL_START <= day <= VAL_END
    ni = {r.epoch_second:i for i,r in enumerate(nr) if allowed(r.day)}
    si = {r.epoch_second:i for i,r in enumerate(sr) if allowed(r.day)}
    common = np.asarray(sorted(set(ni).intersection(si)), dtype=np.int64)
    nidx = np.asarray([ni[int(t)] for t in common], dtype=np.int64); sidx = np.asarray([si[int(t)] for t in common], dtype=np.int64)
    return {
        "COMMON_TS_EPOCH_SECOND": common,
        "NIFTY_XLO": nlo[nidx].astype(np.float32,copy=False),
        "NIFTY_X15": n15[nidx].astype(np.float32,copy=False),
        "NIFTY_CLOSE": np.asarray([nr[i].close for i in nidx],dtype=np.float64),
        "SENSEX_XLO": slo[sidx].astype(np.float32,copy=False),
        "SENSEX_X15": s15[sidx].astype(np.float32,copy=False),
        "SENSEX_CLOSE": np.asarray([sr[i].close for i in sidx],dtype=np.float64),
    }


def save_npz(path: Path, arrays: dict[str,np.ndarray]) -> dict[str,Any]:
    np.savez_compressed(path, **arrays)
    return {"path": path.name, "bytes": path.stat().st_size, "sha256": sha256(path), "canonical_row_stream_sha256": canonical_npz_digest(arrays)}


def feature_mutation_audit(rows: list[MarketRow], vix: list[MarketRow]) -> float:
    # Causal construction audit on an actual observed prefix. Mutate only future OHLC.
    sample = rows[:min(len(rows), 1200)]
    if len(sample) < 600:
        raise RuntimeError("insufficient rows for future-mutation audit")
    cut = len(sample) // 2
    a_lo,a_15 = build_features(sample,vix)
    mutated = list(sample)
    for i in range(cut+1,len(mutated)):
        r=mutated[i]; scale=1.0 + 0.015 * (1 + (i-cut)%5)
        mutated[i]=MarketRow(r.epoch_second,r.source_timestamp,r.day,r.session_minute,r.open*scale,r.high*scale,r.low*scale,r.close*scale)
    b_lo,b_15 = build_features(mutated,vix)
    return max(float(np.max(np.abs(a_lo[:cut+1]-b_lo[:cut+1]))),float(np.max(np.abs(a_15[:cut+1]-b_15[:cut+1]))))


def build(raw_zip: Path, output: Path) -> dict[str,Any]:
    if source.sha256_file(raw_zip) != RAW_SHA:
        raise RuntimeError("raw archive SHA-256 mismatch")
    output.mkdir(parents=True,exist_ok=True)
    archive=source.Archive(raw_zip)
    try:
        nifty,nstats=load_symbol(archive,"underlying/nifty-50")
        sensex,sstats=load_symbol(archive,"underlying/sensex")
        vix,vstats=load_symbol(archive,"underlying/india-vix")
    finally:
        archive.close()
    nlo,n15=build_features(nifty,vix); slo,s15=build_features(sensex,vix)

    def masks(rows):
        d=np.asarray([r.day for r in rows],dtype="U10")
        return d<=TRAIN_END,(d>=VAL_START)&(d<=VAL_END),d>VAL_END
    nt,nv,ns=masks(nifty); st,sv,ss=masks(sensex)
    ntrain=stream_arrays(nifty,nlo,n15,nt); nval=stream_arrays(nifty,nlo,n15,nv)
    strain=stream_arrays(sensex,slo,s15,st); sval=stream_arrays(sensex,slo,s15,sv)
    ctrain=merged_arrays((nifty,nlo,n15),(sensex,slo,s15),"train"); cval=merged_arrays((nifty,nlo,n15),(sensex,slo,s15),"validation")

    files={
        "nifty_train":save_npz(output/"nifty_train.npz",ntrain),
        "nifty_validation":save_npz(output/"nifty_validation.npz",nval),
        "sensex_train":save_npz(output/"sensex_train.npz",strain),
        "sensex_validation":save_npz(output/"sensex_validation.npz",sval),
        "cross_train":save_npz(output/"cross_train.npz",ctrain),
        "cross_validation":save_npz(output/"cross_validation.npz",cval),
    }
    schema={
        "format":"VARDHANI_SPECIALIST_R3_FULL_MARKET_FEATURE_SCHEMA_V1",
        "lower_features":LOWER_FEATURES,
        "context15_features":CONTEXT15_FEATURES,
        "15m_policy":"CONTEXT_NON_VETO",
        "cross_stream":"exact common timestamps only; no forward/future join",
        "index_volume_used":False,"index_oi_used":False,"historical_d30_used":False,"fabricated_modalities":False,
    }
    schema_path=output/"feature_schema.json";schema_path.write_text(json.dumps(schema,indent=2)+"\n",encoding="utf-8")
    schema_spec={"path":schema_path.name,"bytes":schema_path.stat().st_size,"sha256":sha256(schema_path)}
    mutation={"NIFTY":feature_mutation_audit(nifty,vix),"SENSEX":feature_mutation_audit(sensex,vix)}
    overall=max(mutation.values())

    train_rows=int(nt.sum()+st.sum()); val_rows=int(nv.sum()+sv.sum())
    manifest={
        "format":"VARDHANI_SPECIALIST_R3_FULL_MARKET_CORPUS_MANIFEST_V1",
        "raw_source_sha256":RAW_SHA,
        "sealed_2026":True,
        "execution_authority":False,
        "files":files,
        "train":{
            "path":"TRAIN_FILE_SET",
            "bytes":sum(files[k]["bytes"] for k in ["nifty_train","sensex_train","cross_train"]),
            "sha256":hashlib.sha256("".join(files[k]["sha256"] for k in ["nifty_train","sensex_train","cross_train"]).encode()).hexdigest(),
            "canonical_row_stream_sha256":hashlib.sha256("".join(files[k]["canonical_row_stream_sha256"] for k in ["nifty_train","sensex_train","cross_train"]).encode()).hexdigest(),
            "rows":train_rows,"min_timestamp":min(ntrain["DAY"][0],strain["DAY"][0]),"max_timestamp":max(ntrain["DAY"][-1],strain["DAY"][-1]),
        },
        "validation":{
            "path":"VALIDATION_FILE_SET",
            "bytes":sum(files[k]["bytes"] for k in ["nifty_validation","sensex_validation","cross_validation"]),
            "sha256":hashlib.sha256("".join(files[k]["sha256"] for k in ["nifty_validation","sensex_validation","cross_validation"]).encode()).hexdigest(),
            "canonical_row_stream_sha256":hashlib.sha256("".join(files[k]["canonical_row_stream_sha256"] for k in ["nifty_validation","sensex_validation","cross_validation"]).encode()).hexdigest(),
            "rows":val_rows,"min_timestamp":min(nval["DAY"][0],sval["DAY"][0]),"max_timestamp":max(nval["DAY"][-1],sval["DAY"][-1]),
        },
        "feature_schema":schema_spec,
        "measured_leakage":{"train_2025_rows":0,"train_2026_rows":0,"validation_pre_2025_rows":0,"validation_2026_rows":0},
        "sealed_2026_rows_rejected":int(ns.sum()+ss.sum()),
        "source_stats":{"NIFTY":nstats,"SENSEX":sstats,"VIX":vstats},
        "modality_coverage":{"NIFTY_rows":len(nifty),"SENSEX_rows":len(sensex),"VIX_rows":len(vix),"cross_train_rows":len(ctrain["COMMON_TS_EPOCH_SECOND"]),"cross_validation_rows":len(cval["COMMON_TS_EPOCH_SECOND"])},
        "future_mutation_audit":{"per_index":mutation,"pre_cut_max_abs_change":overall},
        "hard_rules":{"options_not_in_this_builder":True,"sensex_options_fabricated":False,"historical_d30_used":False,"real_orders":"DISABLED"},
    }
    (output/"R3_FULL_MARKET_CORPUS_MANIFEST.json").write_text(json.dumps(manifest,indent=2,default=str)+"\n",encoding="utf-8")
    if overall != 0.0:
        raise RuntimeError(f"future mutation gate failed: {overall}")
    return manifest


def main() -> int:
    p=argparse.ArgumentParser();p.add_argument("raw_zip",type=Path);p.add_argument("output",type=Path);a=p.parse_args()
    try: report=build(a.raw_zip,a.output)
    except Exception as exc:
        print(json.dumps({"status":"FAIL_CLOSED","error":str(exc),"real_orders":"DISABLED"},indent=2));return 2
    print(json.dumps({"status":"BUILT_WAITING_FOR_TEACHER_PREFLIGHT","manifest":str(a.output/"R3_FULL_MARKET_CORPUS_MANIFEST.json"),"future_mutation":report["future_mutation_audit"],"real_orders":"DISABLED"},indent=2,default=str));return 0


if __name__=="__main__":sys.exit(main())
