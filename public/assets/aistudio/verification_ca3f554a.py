"""OHLC candle verification engine supporting multi-entry trade legs and market-now entries."""
from datetime import datetime


def _candle_ts(candle):
    return candle.get("candle_timestamp") or candle.get("candle_time") or ""


def _touched(candle, price, tolerance=0.0):
    low = float(candle["low"]) - tolerance
    high = float(candle["high"]) + tolerance
    return low <= float(price) <= high


def _evaluate_single_leg(leg_num, total_legs, entry_val, direction, stop_loss, targets, candles, tolerance, force_market_open=False):
    """Evaluate one entry price independently across the ordered 1m candles."""
    entry_idx = None
    entry_ts = None

    if force_market_open and candles:
        entry_idx = 0
        entry_ts = _candle_ts(candles[0])
    else:
        for idx, c in enumerate(candles):
            if _touched(c, entry_val, tolerance):
                entry_idx = idx
                entry_ts = _candle_ts(c)
                break

    if entry_idx is None:
        return {
            "leg": leg_num,
            "entry_price": entry_val,
            "entered": False,
            "entry_hit_at": None,
            "outcome": "pending",
            "exit_price": None,
            "exit_time": None,
            "points_pnl": 0.0,
            "targets_hit": [],
            "events": [],
        }

    leg_tag = f" (Entry {leg_num})" if total_legs > 1 else ""
    if force_market_open:
        entry_label = f"Market Entry {leg_num}" if total_legs > 1 else "Market Entry"
    else:
        entry_label = f"Entry {leg_num} Hit" if total_legs > 1 else "Entry Hit"
    events = [{
        "type": "entry_hit",
        "label": entry_label,
        "price": entry_val,
        "candle_time": entry_ts,
        "leg": leg_num,
    }]

    hit_tp_indices = set()
    highest_tp_price = None
    highest_tp_time = None
    sl_hit_time = None
    ambiguous_time = None

    for idx in range(entry_idx, len(candles)):
        c = candles[idx]
        ts = _candle_ts(c)

        sl_touched = stop_loss is not None and _touched(c, stop_loss, tolerance)
        newly_hit_tps = [
            (t_idx, float(t_val))
            for t_idx, t_val in enumerate(targets)
            if t_idx not in hit_tp_indices and _touched(c, t_val, tolerance)
        ]

        # If both SL and a new TP are touched inside the exact same 1m candle before any prior TP
        if sl_touched and newly_hit_tps and not hit_tp_indices:
            ambiguous_time = ts
            events.append({
                "type": "ambiguous",
                "label": f"Ambiguous TP & SL{leg_tag}",
                "price": float(stop_loss),
                "candle_time": ts,
                "leg": leg_num,
            })
            break

        for t_idx, t_val in newly_hit_tps:
            hit_tp_indices.add(t_idx)
            highest_tp_price = t_val
            highest_tp_time = ts
            events.append({
                "type": "tp_hit",
                "label": f"TP{t_idx + 1} Hit{leg_tag}",
                "price": t_val,
                "candle_time": ts,
                "leg": leg_num,
            })

        # Stop loss only triggers if no target was already achieved on this leg
        if sl_touched and not hit_tp_indices:
            sl_hit_time = ts
            events.append({
                "type": "sl_hit",
                "label": f"SL Hit{leg_tag}",
                "price": float(stop_loss),
                "candle_time": ts,
                "leg": leg_num,
            })
            break

        # Stop scanning once all targets on this leg have been hit
        if targets and len(hit_tp_indices) == len(targets):
            break

    if ambiguous_time:
        outcome = "ambiguous"
        exit_price = None
        exit_time = ambiguous_time
        points_pnl = 0.0
    elif hit_tp_indices:
        outcome = "target_hit"
        exit_price = highest_tp_price
        exit_time = highest_tp_time
        points_pnl = (
            round(exit_price - entry_val, 6)
            if direction == "BUY"
            else round(entry_val - exit_price, 6)
        )
    elif sl_hit_time:
        outcome = "stop_loss_hit"
        exit_price = float(stop_loss)
        exit_time = sl_hit_time
        points_pnl = (
            round(exit_price - entry_val, 6)
            if direction == "BUY"
            else round(entry_val - exit_price, 6)
        )
    else:
        outcome = "entry_hit"
        exit_price = None
        exit_time = None
        points_pnl = 0.0

    return {
        "leg": leg_num,
        "entry_price": entry_val,
        "entered": True,
        "entry_hit_at": entry_ts,
        "outcome": outcome,
        "exit_price": exit_price,
        "exit_time": exit_time,
        "points_pnl": points_pnl,
        "targets_hit": sorted(i + 1 for i in hit_tp_indices),
        "events": events,
    }


def verify_detailed(signal, candles, tolerance=0.0):
    """
    Evaluate a parsed signal against ordered 1m OHLC candles.
    Returns (status, detail, decision_candle_time, timeline).
    """
    direction = (signal.get("direction") or "").strip().upper()
    entry_mode = (signal.get("entry_mode") or "limit").strip().lower()
    stop_loss = float(signal["stop_loss"]) if signal.get("stop_loss") is not None else None
    targets = [float(t) for t in (signal.get("targets") or []) if t is not None]
    tol = float(tolerance or 0.0)

    raw_entries = [signal.get("entry_price")] + list(signal.get("additional_entries") or [])
    entries = []
    for e in raw_entries:
        if e is not None:
            val = float(e)
            if val not in entries:
                entries.append(val)

    empty_timeline = {
        "entry_hit_at": None,
        "first_target_hit_at": None,
        "stop_loss_hit_at": None,
        "all_targets_hit": False,
        "events": [],
        "legs": [],
    }

    if direction not in ("BUY", "SELL"):
        return "pending", "Missing valid direction.", None, empty_timeline

    if not entries and entry_mode != "market_now":
        return "pending", "Missing valid entry price or direction.", None, empty_timeline

    if not candles:
        return "awaiting_market_data", "Awaiting market data.", None, empty_timeline

    if not entries and entry_mode == "market_now":
        first_open = candles[0].get("open") if candles[0].get("open") is not None else candles[0].get("close")
        if first_open is None:
            return "awaiting_market_data", "Awaiting market data.", None, empty_timeline
        entries = [float(first_open)]

    legs = []
    all_events = []
    for idx, entry_val in enumerate(entries, start=1):
        leg_res = _evaluate_single_leg(
            leg_num=idx,
            total_legs=len(entries),
            entry_val=entry_val,
            direction=direction,
            stop_loss=stop_loss,
            targets=targets,
            candles=candles,
            tolerance=tol,
            force_market_open=(entry_mode == "market_now" and idx == 1),
        )
        all_events.extend(leg_res.pop("events"))
        legs.append(leg_res)

    all_events.sort(key=lambda ev: ev.get("candle_time") or "")

    entered_legs = [lg for lg in legs if lg["entered"]]
    if not entered_legs:
        return "pending", "Candles checked; entry price not touched yet.", None, {
            **empty_timeline,
            "legs": legs,
        }

    entry_hit_at = min(lg["entry_hit_at"] for lg in entered_legs if lg["entry_hit_at"])
    tp_times = [ev["candle_time"] for ev in all_events if ev["type"] == "tp_hit"]
    sl_times = [ev["candle_time"] for ev in all_events if ev["type"] == "sl_hit"]
    amb_times = [ev["candle_time"] for ev in all_events if ev["type"] == "ambiguous"]

    first_target_hit_at = min(tp_times) if tp_times else None
    stop_loss_hit_at = min(sl_times) if sl_times else None

    all_targets_hit = bool(
        targets
        and all(len(lg["targets_hit"]) == len(targets) for lg in entered_legs)
    )

    if first_target_hit_at:
        status = "target_hit"
        decision_time = first_target_hit_at
    elif any(lg["outcome"] == "ambiguous" for lg in entered_legs):
        status = "ambiguous"
        decision_time = amb_times[0] if amb_times else entry_hit_at
    elif all(lg["outcome"] == "stop_loss_hit" for lg in entered_legs):
        status = "stop_loss_hit"
        decision_time = stop_loss_hit_at
    else:
        status = "entry_hit"
        decision_time = entry_hit_at

    leg_summaries = []
    for lg in entered_legs:
        if lg["outcome"] == "target_hit":
            max_tp = max(lg["targets_hit"])
            leg_summaries.append(f"Entry {lg['leg']} ({lg['entry_price']:g}) -> TP{max_tp} ({lg['points_pnl']:+g} pts)")
        elif lg["outcome"] == "stop_loss_hit":
            leg_summaries.append(f"Entry {lg['leg']} ({lg['entry_price']:g}) -> SL ({lg['points_pnl']:+g} pts)")
        elif lg["outcome"] == "ambiguous":
            leg_summaries.append(f"Entry {lg['leg']} ({lg['entry_price']:g}) -> Ambiguous")
        else:
            leg_summaries.append(f"Entry {lg['leg']} ({lg['entry_price']:g}) -> Open")

    detail = f"{len(entered_legs)}/{len(entries)} entries triggered: " + "; ".join(leg_summaries) + "."

    timeline = {
        "entry_hit_at": entry_hit_at,
        "first_target_hit_at": first_target_hit_at,
        "stop_loss_hit_at": stop_loss_hit_at,
        "all_targets_hit": all_targets_hit,
        "events": all_events,
        "legs": legs,
    }
    return status, detail, decision_time, timeline