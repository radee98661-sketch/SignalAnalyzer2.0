import csv, io, json, shutil
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.request import Request, urlopen
from flask import Blueprint, current_app, jsonify, render_template, request, Response
from .database import SOURCE_COLUMNS, utcnow, notification_datetime
from .parser import (
    parse_signal,
    parse_signals,
    normalize_sl_category,
    forex_pip_size,
    apply_default_sl,
    apply_default_targets,
)
from .sync import sync_google_sheets
from .scheduler import process_signal, process_signals_batch, resolve_signal_provider, _dt
from .providers import BinanceFutures, AngelOneProvider, TwelveData, ProviderError

bp = Blueprint("main", __name__)


def db():
    return current_app.extensions["database"]


def rows(sql, params=()):
    with db().connection() as c:
        return [dict(r) for r in c.execute(sql, params)]


def one(sql, params=()):
    data = rows(sql, params)
    return data[0] if data else None


def json_field(x):
    try:
        return json.loads(x or "[]")
    except json.JSONDecodeError:
        return []


def _opt_pos_float(val):
    if val is None or val == "":
        return None
    try:
        num = float(val)
        return num if num > 0 else None
    except (TypeError, ValueError):
        return None


def _build_status_reason(row, initial_wait, repeat_mins, age_minutes):
    p_status = row.get("parsing_status")
    v_status = row.get("verification_status")
    s_status = row.get("scheduler_status")
    next_at = row.get("next_scheduled_at")
    last_res = row.get("last_result")
    trade_detail = row.get("trade_detail")

    if p_status == "needs_review":
        return "Needs manual review: parser could not extract all required fields (Symbol, Direction, or Entry)."
    if v_status == "historical_data_missing" and trade_detail:
        return trade_detail
    if s_status == "manual_required" or (age_minutes > initial_wait and not next_at and v_status in ("pending", "awaiting_market_data")):
        return f"Signal is older than {initial_wait} mins ({int(age_minutes)}m old) — manual processing required. Select and click 'Process selected' to fetch candles & verify."
    if v_status == "awaiting_market_data":
        if next_at:
            return f"Awaiting market data — fetches 1m candles after the {initial_wait}m window (every {repeat_mins}m). Next scheduled fetch: {next_at}."
        if last_res:
            return f"Awaiting market data ({last_res}). Select and click 'Process selected' to retry fetch now."
        return f"Awaiting market data — will fetch 1m candles after {initial_wait}m and retry every {repeat_mins}m."
    if v_status == "pending":
        if next_at:
            return f"Candles checked; entry price not touched yet. Will re-fetch every {repeat_mins}m (next: {next_at})."
        return trade_detail or "Candles checked; entry price was not touched."
    if v_status == "entry_hit":
        if next_at:
            return f"Entry hit! Monitoring every {repeat_mins}m for TP1/TP2/SL (next check: {next_at})."
        return trade_detail or "Entry hit; monitoring for targets or stop loss."
    if v_status == "target_hit":
        if next_at:
            return f"{trade_detail or 'Target hit!'} Continuing 15m checks for remaining targets (next: {next_at})."
        return trade_detail or "Target achieved."
    if v_status == "stop_loss_hit":
        return trade_detail or "Stop loss hit."
    if v_status == "ambiguous":
        return trade_detail or "Target and stop loss were touched within the same 1m candle."
    return trade_detail or last_res or "Ready."


def signal_row(row, initial_wait=15, repeat_mins=15):
    row["targets"] = json_field(row.get("targets"))
    row["additional_entries"] = json_field(row.get("additional_entries"))
    row["events"] = json_field(row.get("events_json"))
    row["legs"] = json_field(row.get("legs_json"))
    row.pop("events_json", None)
    row.pop("legs_json", None)

    row["pnl_amount"] = round(float(row.get("pnl_amount") or 0.0), 2)
    row["pnl_currency"] = row.get("pnl_currency") or "INR"
    row["trades_count"] = int(row.get("trades_count") or 0)
    row["entry_mode"] = row.get("entry_mode") or "limit"

    now_utc = datetime.now(timezone.utc)
    sig_dt = notification_datetime({"Notification Time": row.get("notification_time"), "Received At": row.get("received_at")})
    age_minutes = max(0.0, (now_utc - sig_dt).total_seconds() / 60.0)
    row["age_minutes"] = round(age_minutes, 1)

    s_status = row.get("scheduler_status")
    is_auto_scheduled = bool(s_status in ("scheduled", "processing") or row.get("next_scheduled_at"))
    is_recent = age_minutes <= 60.0
    is_manual_needed = bool(
        s_status == "manual_required"
        or (age_minutes > initial_wait and not row.get("next_scheduled_at") and row.get("verification_status") in ("pending", "awaiting_market_data", "historical_data_missing"))
    )

    expl = row.get("parsing_explanation") or ""
    row["is_auto_scheduled"] = is_auto_scheduled
    row["is_recent"] = is_recent
    row["is_manual_needed"] = is_manual_needed
    row["is_historical"] = not (is_recent or is_auto_scheduled)
    row["is_default_sl"] = "Default SL:" in expl
    row["is_default_tp"] = "Default TPs:" in expl
    row["status_reason"] = _build_status_reason(row, initial_wait, repeat_mins, age_minutes)
    return row


CATEGORY_ALIASES = {
    "Crypto": ("crypto",),
    "Forex": ("forex",),
    "Indian Options": ("indian options", "options"),
    "Stock Options": ("stock options",),
    "MCX": ("mcx",),
}


def canonical_category(value):
    lowered = (value or "").strip().lower()
    return next((name for name, aliases in CATEGORY_ALIASES.items() if lowered in aliases), value)


def category_condition(category, column="r.category"):
    aliases = CATEGORY_ALIASES.get(category, ((category or "").strip().lower(),))
    return f"LOWER({column}) IN ({','.join('?' for _ in aliases)})", list(aliases)


@bp.get("/")
def index():
    static = Path(current_app.static_folder)
    return render_template(
        "index.html",
        source_columns=SOURCE_COLUMNS,
        inline_css=(static / "style.css").read_text(encoding="utf-8"),
        inline_js=(static / "app.js").read_text(encoding="utf-8"),
    )


@bp.get("/api/overview")
def overview():
    total = one("SELECT COUNT(*) n FROM raw_notifications WHERE is_duplicate=0")["n"]
    parsed = one("SELECT COUNT(*) n FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND s.parsing_status='parsed'")["n"]
    verified = one("SELECT COUNT(*) n FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND s.verification_status IN ('target_hit','stop_loss_hit')")["n"]
    targets = one("SELECT COUNT(*) n FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND s.verification_status='target_hit'")["n"]
    today = one("SELECT COUNT(*) n FROM raw_notifications WHERE is_duplicate=0 AND substr(notification_time,1,10)=?", (datetime.now().date().isoformat(),))["n"]
    pnl_inr = one("SELECT ROUND(COALESCE(SUM(tr.pnl_amount),0),2) n FROM trade_results tr JOIN signals s ON s.id=tr.signal_id JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND tr.pnl_currency='INR'")["n"]
    pnl_usd = one("SELECT ROUND(COALESCE(SUM(tr.pnl_amount),0),2) n FROM trade_results tr JOIN signals s ON s.id=tr.signal_id JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND tr.pnl_currency='USD'")["n"]
    raw_categories = rows("SELECT category name,COUNT(*) value FROM raw_notifications WHERE is_duplicate=0 GROUP BY category")
    categories = {name: 0 for name in CATEGORY_ALIASES}
    for item in raw_categories:
        categories[canonical_category(item["name"])] = categories.get(canonical_category(item["name"]), 0) + item["value"]
    return jsonify({
        "metrics": {
            "notifications": total,
            "parsed": parsed,
            "today": today,
            "target_hits": targets,
            "stop_loss_hits": one("SELECT COUNT(*) n FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND s.verification_status='stop_loss_hit'")["n"],
            "pending": one("SELECT COUNT(*) n FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND s.verification_status='pending'")["n"],
            "entry_rate": round(100 * one("SELECT COUNT(*) n FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 AND s.verification_status IN ('entry_hit','target_hit','stop_loss_hit','ambiguous')")["n"] / max(parsed, 1), 1),
            "win_rate": round(100 * targets / max(verified, 1), 1),
            "pnl_inr": pnl_inr,
            "pnl_usd": pnl_usd,
        },
        "categories": [{"name": k, "value": v} for k, v in categories.items()],
        "channels": rows("SELECT channel name,COUNT(*) value FROM raw_notifications WHERE is_duplicate=0 GROUP BY channel ORDER BY value DESC LIMIT 8"),
        "recent_notifications": rows("SELECT * FROM raw_notifications ORDER BY id DESC LIMIT 8"),
        "recent_signals": [signal_row(r) for r in rows("SELECT s.*,r.channel,r.notification_time,r.message FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE r.is_duplicate=0 ORDER BY s.id DESC LIMIT 8")],
        "sync": one("SELECT * FROM synchronization_logs ORDER BY id DESC LIMIT 1"),
        "integrations": rows("SELECT provider,status,updated_at FROM integrations"),
    })


@bp.get("/api/signals")
def signals():
    settings = db().settings()
    initial_wait = int(settings.get("verification_initial_wait_minutes", "15"))
    repeat_mins = int(settings.get("verification_repeat_minutes", "15"))

    where = []
    params = []
    if request.args.get("category"):
        clause, values = category_condition(request.args["category"])
        where.append(clause)
        params.extend(values)
    for col, arg in (("r.channel", "channel"), ("s.symbol", "symbol")):
        if request.args.get(arg):
            where.append(col + "=?")
            params.append(request.args[arg])

    notice_filter = request.args.get("notification_filter", "all")
    if notice_filter == "primary":
        where.append("r.is_duplicate=0 AND (r.duplicate_reason IS NULL OR r.duplicate_reason NOT LIKE 'signal_update:%')")
    elif notice_filter == "duplicates":
        where.append("r.is_duplicate=1")
    elif notice_filter == "updates":
        where.append("r.is_duplicate=0 AND r.duplicate_reason LIKE 'signal_update:%'")

    status_filter = (request.args.get("status") or "").strip().lower()
    if status_filter == "needs_review":
        where.append("(s.parsing_status='needs_review' OR s.manual_review_status='required')")
    elif status_filter == "manual_needed":
        cutoff_iso = (datetime.now(timezone.utc) - timedelta(minutes=initial_wait)).isoformat()
        where.append(
            "(vs.status='manual_required' OR "
            "(COALESCE(r.notification_time, r.received_at) < ? AND vs.next_scheduled_at IS NULL "
            "AND s.verification_status IN ('pending','awaiting_market_data','historical_data_missing')))"
        )
        params.append(cutoff_iso)
    elif status_filter == "scheduled":
        where.append("(vs.status IN ('scheduled','processing') OR vs.next_scheduled_at IS NOT NULL)")
    elif status_filter in {"pending", "awaiting_market_data", "historical_data_missing", "entry_hit", "target_hit", "stop_loss_hit", "ambiguous"}:
        where.append("s.verification_status=?")
        params.append(status_filter)

    sql = (
        "SELECT s.*, r.received_at, r.notification_time, r.source_app, r.channel, r.category, r.title, r.message, "
        "r.notification_id, r.is_duplicate, r.duplicate_of, r.duplicate_reason, r.duplicate_group_id, "
        "vs.first_scheduled_at, vs.next_scheduled_at, vs.last_verified_at, vs.last_result, "
        "vs.status scheduler_status, vs.timeframe verification_timeframe, "
        "tr.entry_hit_at, tr.target_hit_at, tr.stop_loss_hit_at, tr.events_json, tr.detail trade_detail, "
        "tr.pnl_amount, tr.pnl_currency, tr.trades_count, tr.legs_json "
        "FROM signals s "
        "JOIN raw_notifications r ON r.id=s.raw_notification_id "
        "LEFT JOIN verification_schedules vs ON vs.signal_id=s.id "
        "LEFT JOIN trade_results tr ON tr.signal_id=s.id"
        + (" WHERE " + " AND ".join(where) if where else "")
        + " ORDER BY s.id DESC LIMIT 500"
    )
    return jsonify([signal_row(r, initial_wait, repeat_mins) for r in rows(sql, params)])


@bp.delete("/api/signals/<int:signal_id>")
def delete_signal(signal_id):
    with db().connection() as c:
        deleted = c.execute("DELETE FROM signals WHERE id=?", (signal_id,)).rowcount
        if not deleted:
            return jsonify({"error": "Signal not found"}), 404
        db().log(c, "WARNING", "signals", f"Deleted signal {signal_id}")
    return jsonify({"ok": True, "deleted": deleted})


@bp.post("/api/signals/delete")
def delete_selected_signals():
    ids = (request.get_json() or {}).get("signal_ids", [])
    if not isinstance(ids, list) or not ids:
        return jsonify({"error": "Select at least one signal."}), 400
    clean_ids = []
    for raw_id in ids[:500]:
        try:
            clean_ids.append(int(raw_id))
        except (TypeError, ValueError):
            continue
    if not clean_ids:
        return jsonify({"error": "No valid signal IDs provided."}), 400
    placeholders = ",".join("?" for _ in clean_ids)
    with db().connection() as c:
        deleted = c.execute(f"DELETE FROM signals WHERE id IN ({placeholders})", clean_ids).rowcount
        db().log(c, "WARNING", "signals", f"Deleted {deleted} selected signals")
    return jsonify({"ok": True, "deleted": deleted})


@bp.get("/api/signals/<int:signal_id>/chart")
def signal_chart(signal_id):
    record = one(
        "SELECT s.*, r.notification_time, r.received_at, r.channel, r.category, r.message, "
        "tr.entry_hit_at, tr.target_hit_at, tr.stop_loss_hit_at, tr.events_json, tr.detail trade_detail "
        "FROM signals s "
        "JOIN raw_notifications r ON r.id=s.raw_notification_id "
        "LEFT JOIN trade_results tr ON tr.signal_id=s.id "
        "WHERE s.id=?",
        (signal_id,)
    )
    if not record:
        return jsonify({"error": "Signal not found"}), 404

    item = dict(record)
    item["targets"] = json_field(item.get("targets"))
    item["additional_entries"] = json_field(item.get("additional_entries"))
    events = json_field(item.get("events_json"))

    with db().connection() as c:
        provider, resolved_sym = resolve_signal_provider(c, item)
        cache_sym = resolved_sym.split(":", 1)[1] if ":" in resolved_sym else resolved_sym
        inst_row = c.execute(
            "SELECT trading_symbol, exchange_segment FROM instruments WHERE instrument_token=?",
            (cache_sym,)
        ).fetchone() if provider == "angel_one" else None
        sig_dt = notification_datetime({"Notification Time": item.get("notification_time"), "Received At": item.get("received_at")})
        chart_start = sig_dt.replace(second=0, microsecond=0).isoformat()
        candles = [
            dict(x) for x in c.execute(
                "SELECT candle_timestamp, open, high, low, close, volume FROM market_candles "
                "WHERE provider=? AND symbol=? AND candle_timestamp>=? ORDER BY candle_timestamp ASC LIMIT 1500",
                (provider, cache_sym, chart_start)
            )
        ]

    return jsonify({
        "signal_id": signal_id,
        "symbol": item.get("symbol"),
        "resolved_symbol": cache_sym,
        "trading_symbol": inst_row["trading_symbol"] if inst_row else None,
        "provider": provider,
        "direction": item.get("direction"),
        "notification_time": sig_dt.isoformat(),
        "entry_price": item.get("entry_price"),
        "additional_entries": item["additional_entries"],
        "entry_mode": item.get("entry_mode") or "limit",
        "stop_loss": item.get("stop_loss"),
        "targets": item["targets"],
        "verification_status": item.get("verification_status"),
        "detail": item.get("trade_detail"),
        "entry_hit_at": item.get("entry_hit_at"),
        "target_hit_at": item.get("target_hit_at"),
        "stop_loss_hit_at": item.get("stop_loss_hit_at"),
        "events": events,
        "candles": candles,
    })


@bp.get("/api/notifications")
def notifications():
    limit = min(max(int(request.args.get("limit", 100)), 1), 500)
    after = request.args.get("after", type=int)
    category = request.args.get("category", "")
    channel = request.args.get("channel", "")
    date_from = request.args.get("date_from", "")
    date_to = request.args.get("date_to", "")
    parsing_status = (request.args.get("parsing_status") or "").strip().lower()
    where = []
    params = []
    if after:
        where.append("r.id>?")
        params.append(after)
    if category:
        clause, values = category_condition(category, "r.category")
        where.append(clause)
        params.extend(values)
    if channel:
        where.append("r.channel=?")
        params.append(channel)
    if date_from:
        where.append("substr(COALESCE(r.notification_time,r.received_at),1,10)>=?")
        params.append(date_from)
    if date_to:
        where.append("substr(COALESCE(r.notification_time,r.received_at),1,10)<=?")
        params.append(date_to)
    if parsing_status == "needs_review":
        where.append("(s.parsing_status='needs_review' OR s.manual_review_status='required')")
    elif parsing_status in ("parsed", "manually_corrected"):
        where.append("s.parsing_status=?")
        params.append(parsing_status)
    elif parsing_status == "unparsed":
        where.append("s.id IS NULL")
    notice_filter = request.args.get("notification_filter", "all")
    if notice_filter == "primary":
        where.append("r.is_duplicate=0 AND (r.duplicate_reason IS NULL OR r.duplicate_reason NOT LIKE 'signal_update:%')")
    elif notice_filter == "duplicates":
        where.append("r.is_duplicate=1")
    elif notice_filter == "updates":
        where.append("r.is_duplicate=0 AND r.duplicate_reason LIKE 'signal_update:%'")
    sql = (
        "SELECT r.*,s.id signal_id,s.symbol,s.direction,s.entry_price,s.additional_entries,s.stop_loss,s.targets,"
        "s.option_type,s.strike_price,s.expiry_date,s.entry_mode,s.parsing_status,s.manual_review_status,"
        "s.parsing_confidence,s.parsing_explanation,s.verification_status "
        "FROM raw_notifications r LEFT JOIN signals s ON s.raw_notification_id=r.id"
        + (" WHERE " + " AND ".join(where) if where else "")
        + " ORDER BY r.id DESC LIMIT ?"
    )
    params.append(limit)
    return jsonify(rows(sql, params))


@bp.get("/api/notification-filters")
def notification_filters():
    return jsonify({
        "channels": [r["channel"] for r in rows("SELECT DISTINCT channel FROM raw_notifications WHERE channel IS NOT NULL AND channel<>'' ORDER BY channel")],
        "categories": [r["name"] for r in rows("SELECT name FROM categories WHERE enabled=1 ORDER BY id")],
    })


@bp.get("/api/filters")
def filters():
    category = request.args.get("category", "")
    channel_sql = "SELECT DISTINCT channel FROM raw_notifications WHERE channel IS NOT NULL AND channel<>''"
    params = []
    if category:
        clause, values = category_condition(category, "category")
        channel_sql += " AND " + clause
        params.extend(values)
    return jsonify({
        "categories": [r["name"] for r in rows("SELECT name FROM categories WHERE enabled=1 ORDER BY id")],
        "channels": [r["channel"] for r in rows(channel_sql + " ORDER BY channel", params)],
    })


@bp.post("/api/signals/<int:signal_id>")
def update_signal(signal_id):
    data = request.get_json() or {}
    allowed = {"symbol", "direction", "entry_price", "stop_loss", "option_type", "strike_price", "expiry_date", "entry_mode", "manual_review_status"}
    updates = []
    params = []
    for k in allowed:
        if k in data:
            updates.append(k + "=?")
            params.append(data[k])
    for k in ("targets", "additional_entries"):
        if k in data:
            updates.append(k + "=?")
            params.append(json.dumps(data[k]))
    if not updates and "notification_time" not in data and "verification_timeframe" not in data:
        return jsonify({"error": "No editable fields supplied"}), 400
    updates.extend(["parsing_status='manually_corrected'", "updated_at=?"])
    params.extend([utcnow(), signal_id])
    with db().connection() as c:
        if updates:
            c.execute("UPDATE signals SET " + ",".join(updates) + " WHERE id=?", params)
        if "notification_time" in data:
            c.execute("UPDATE raw_notifications SET notification_time=? WHERE id=(SELECT raw_notification_id FROM signals WHERE id=?)", (data["notification_time"], signal_id))
        if "verification_timeframe" in data:
            c.execute("UPDATE verification_schedules SET timeframe=?,updated_at=? WHERE signal_id=?", (data["verification_timeframe"], utcnow(), signal_id))
        if data.get("reschedule"):
            signal_time = data.get("notification_time") or c.execute("SELECT r.notification_time FROM raw_notifications r JOIN signals s ON s.raw_notification_id=r.id WHERE s.id=?", (signal_id,)).fetchone()[0]
            start = datetime.fromisoformat(signal_time.replace("Z", "+00:00"))
            start = start.replace(tzinfo=timezone.utc) if start.tzinfo is None else start
            schedule = c.execute("SELECT initial_wait_minutes,expires_at FROM verification_schedules WHERE signal_id=?", (signal_id,)).fetchone()
            if schedule:
                c.execute(
                    "UPDATE verification_schedules SET signal_start_at=?,first_scheduled_at=?,next_scheduled_at=?,status='scheduled',updated_at=? WHERE signal_id=?",
                    (start.isoformat(), (start + timedelta(minutes=schedule[0])).isoformat(), (start + timedelta(minutes=schedule[0])).isoformat(), utcnow(), signal_id)
                )
        db().log(c, "INFO", "signals", f"Manual correction for signal {signal_id}")
    return jsonify({"ok": True})


@bp.get("/api/signals/<int:signal_id>/parse-detail")
def signal_parse_detail(signal_id):
    record = one(
        "SELECT s.*,r.received_at,r.notification_time,r.source_app,r.package,r.channel,r.category,r.title,r.message,r.notification_id,r.delivery_id "
        "FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE s.id=?",
        (signal_id,)
    )
    if not record:
        return jsonify({"error": "Signal not found"}), 404
    return jsonify({
        "signal": signal_row(record),
        "history": rows("SELECT * FROM parsing_runs WHERE signal_id=? ORDER BY id DESC LIMIT 50", (signal_id,)),
    })


def _ensure_schedule_for_signal(c, signal_id, sig_dt, item, is_duplicate):
    can_verify = (
        item.get("status") == "parsed"
        and not is_duplicate
        and item.get("symbol")
        and item.get("direction") in ("BUY", "SELL")
        and (item.get("entry_price") is not None or item.get("entry_mode") == "market_now")
    )
    if not can_verify:
        return
    existing = c.execute("SELECT id FROM verification_schedules WHERE signal_id=?", (signal_id,)).fetchone()
    if existing:
        return
    settings = {x["key"]: x["value"] for x in c.execute("SELECT key,value FROM application_settings")}
    initial = int(settings.get("verification_initial_wait_minutes", 15))
    interval = int(settings.get("verification_repeat_minutes", 15))
    expiry = int(settings.get("verification_expiry_hours", 168))
    first = sig_dt + timedelta(minutes=initial)
    expires = sig_dt + timedelta(hours=expiry)
    live_after = datetime.now(timezone.utc) - timedelta(minutes=initial)
    status = "scheduled" if sig_dt >= live_after else "manual_required"
    next_time = first.isoformat() if status == "scheduled" else None
    last_res = f"Older than {initial} mins: manual processing required" if status == "manual_required" else None
    c.execute(
        "INSERT INTO verification_schedules(signal_id,signal_start_at,initial_wait_minutes,interval_minutes,timeframe,first_scheduled_at,next_scheduled_at,expires_at,status,last_result,created_at,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        (signal_id, sig_dt.isoformat(), initial, interval, settings.get("verification_timeframe", "1m"), first.isoformat(), next_time, expires.isoformat(), status, last_res, utcnow(), utcnow())
    )


def apply_reparse(signal_id, run_type="manual_reparse", sl_rules=None):
    record = one(
        "SELECT r.id AS raw_id, r.message, r.category, r.channel, r.notification_time, r.received_at, r.is_duplicate "
        "FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE s.id=?",
        (signal_id,)
    )
    if not record:
        return None
    rules = sl_rules if sl_rules is not None else db().get_default_sl_rules()
    raw_cat = record["category"] or ""
    items = parse_signals(record["message"], raw_cat, sl_rules=rules)
    sig_dt = notification_datetime({"Notification Time": record["notification_time"], "Received At": record["received_at"]})
    is_dup = bool(record["is_duplicate"])

    with db().connection() as c:
        # 30-minute same-channel Forex symbol inheritance for short follow-ups (e.g. 'Buy now 3765')
        if not is_dup and raw_cat.strip().lower() in ("forex", "fx"):
            ctx_cutoff = (sig_dt - timedelta(minutes=30)).isoformat()
            recent_fx = c.execute(
                "SELECT s.symbol FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id "
                "WHERE r.channel=? AND LOWER(r.category) IN ('forex','fx') AND r.is_duplicate=0 "
                "AND s.symbol IS NOT NULL AND s.symbol<>'' AND COALESCE(r.notification_time,r.received_at)>=? "
                "AND COALESCE(r.notification_time,r.received_at)<=? AND r.id<>? "
                "ORDER BY s.id DESC LIMIT 1",
                (record["channel"] or "", ctx_cutoff, sig_dt.isoformat(), record["raw_id"]),
            ).fetchone()
            if recent_fx:
                for it in items:
                    if not it.get("symbol") and it.get("direction") and (it.get("entry_price") is not None or it.get("entry_mode") == "market_now"):
                        it["symbol"] = recent_fx["symbol"]
                        it["status"] = "parsed"
                        it["review"] = "not_required"
                        it["confidence"] = 0.85
                        it["explanation"] = (it.get("explanation") or "").rstrip(".") + f" | Inherited symbol {recent_fx['symbol']} from recent channel message."
                        apply_default_sl(it, raw_cat, rules)
                        apply_default_targets(it, raw_cat, rules)

        primary = items[0]
        c.execute(
            "UPDATE signals SET symbol=?,direction=?,entry_price=?,additional_entries=?,stop_loss=?,targets=?,"
            "option_type=?,strike_price=?,expiry_date=?,entry_mode=?,parsing_status=?,parsing_confidence=?,manual_review_status=?,"
            "parsing_explanation=?,updated_at=? WHERE id=?",
            (
                primary.get("symbol"), primary.get("direction"), primary.get("entry_price"),
                json.dumps(primary.get("additional_entries", [])), primary.get("stop_loss"),
                json.dumps(primary.get("targets", [])), primary.get("option_type"),
                primary.get("strike_price"), primary.get("expiry_date"), primary.get("entry_mode") or "limit",
                primary["status"], primary["confidence"], primary["review"], primary["explanation"], utcnow(), signal_id
            )
        )
        c.execute(
            "INSERT INTO parsing_runs(signal_id,run_type,parsed_at,status,confidence,explanation,snapshot_json) VALUES(?,?,?,?,?,?,?)",
            (signal_id, run_type, utcnow(), primary["status"], primary["confidence"], primary["explanation"], json.dumps(primary))
        )
        _ensure_schedule_for_signal(c, signal_id, sig_dt, primary, is_dup)

        # Update, insert, or prune sibling signal cards under the same raw_notification_id (same channel & category)
        siblings = c.execute(
            "SELECT id FROM signals WHERE raw_notification_id=? AND id<>? ORDER BY id ASC",
            (record["raw_id"], signal_id)
        ).fetchall()
        extra_items = items[1:]

        for idx, extra in enumerate(extra_items):
            if idx < len(siblings):
                extra_id = siblings[idx]["id"]
                c.execute(
                    "UPDATE signals SET symbol=?,direction=?,entry_price=?,additional_entries=?,stop_loss=?,targets=?,"
                    "option_type=?,strike_price=?,expiry_date=?,entry_mode=?,parsing_status=?,parsing_confidence=?,manual_review_status=?,"
                    "parsing_explanation=?,updated_at=? WHERE id=?",
                    (
                        extra.get("symbol"), extra.get("direction"), extra.get("entry_price"),
                        json.dumps(extra.get("additional_entries", [])), extra.get("stop_loss"),
                        json.dumps(extra.get("targets", [])), extra.get("option_type"),
                        extra.get("strike_price"), extra.get("expiry_date"), extra.get("entry_mode") or "limit",
                        extra["status"], extra["confidence"], extra["review"], extra["explanation"], utcnow(), extra_id
                    )
                )
            else:
                extra_id = c.execute(
                    "INSERT INTO signals(raw_notification_id,symbol,direction,entry_price,additional_entries,stop_loss,targets,option_type,strike_price,expiry_date,entry_mode,parsing_status,parsing_confidence,manual_review_status,parsing_explanation,updated_at) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        record["raw_id"], extra.get("symbol"), extra.get("direction"), extra.get("entry_price"),
                        json.dumps(extra.get("additional_entries", [])), extra.get("stop_loss"),
                        json.dumps(extra.get("targets", [])), extra.get("option_type"),
                        extra.get("strike_price"), extra.get("expiry_date"), extra.get("entry_mode") or "limit",
                        extra["status"], extra["confidence"], extra["review"], extra["explanation"], utcnow()
                    )
                ).lastrowid
            c.execute(
                "INSERT INTO parsing_runs(signal_id,run_type,parsed_at,status,confidence,explanation,snapshot_json) VALUES(?,?,?,?,?,?,?)",
                (extra_id, run_type, utcnow(), extra["status"], extra["confidence"], extra["explanation"], json.dumps(extra))
            )
            _ensure_schedule_for_signal(c, extra_id, sig_dt, extra, is_dup)

        if len(siblings) > len(extra_items):
            stale_ids = [s["id"] for s in siblings[len(extra_items):]]
            ph = ",".join("?" for _ in stale_ids)
            c.execute(f"DELETE FROM signals WHERE id IN ({ph})", stale_ids)

        db().log(c, "INFO", "parser", f"Re-parsed signal {signal_id} ({len(items)} trade(s))")
    return primary


@bp.post("/api/signals/<int:signal_id>/reparse")
def reparse_signal(signal_id):
    parsed = apply_reparse(signal_id)
    if not parsed:
        return jsonify({"error": "Signal not found"}), 404
    return jsonify({"signal_id": signal_id, "parsed": parsed})


@bp.post("/api/signals/reparse")
def reparse_selected_signals():
    signal_ids = (request.get_json() or {}).get("signal_ids", [])
    if not isinstance(signal_ids, list) or not signal_ids:
        return jsonify({"error": "Select at least one signal."}), 400
    rules = db().get_default_sl_rules()
    results = []
    for signal_id in signal_ids[:500]:
        parsed = apply_reparse(int(signal_id), "bulk_reparse", sl_rules=rules)
        results.append({"signal_id": int(signal_id), "status": parsed["status"] if parsed else "missing"})
    return jsonify({"results": results})


@bp.post("/api/import")
def import_rows():
    data = request.get_json() or {}
    rules = db().get_default_sl_rules()
    imported = dup = bad = 0
    for row in data.get("rows", []):
        if not isinstance(row, dict) or not all(k in row for k in SOURCE_COLUMNS):
            bad += 1
            continue
        _, status = db().ingest(row, parse_signals(row["Message"], row["Category"], sl_rules=rules))
        imported += status == "imported"
        dup += status == "duplicate"
    return jsonify({"imported": imported, "duplicates": dup, "invalid": bad})


@bp.post("/api/parse")
def parse_preview():
    data = request.get_json() or {}
    rules = db().get_default_sl_rules()
    items = parse_signals(data.get("message", ""), data.get("category", ""), sl_rules=rules)
    if len(items) > 1:
        return jsonify({**items[0], "split_signals": items})
    return jsonify(items[0])


@bp.get("/api/settings")
def get_settings():
    return jsonify(db().settings())


@bp.put("/api/settings")
def put_settings():
    db().set_settings(request.get_json() or {})
    return jsonify({"ok": True})


@bp.get("/api/default-sl")
def get_default_sl():
    rules = db().get_default_sl_rules()
    recorded = rows(
        "SELECT DISTINCT r.category, s.symbol, s.option_type "
        "FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id "
        "WHERE s.symbol IS NOT NULL AND s.symbol<>''"
    )
    for rec in recorded:
        sym = (rec.get("symbol") or "").strip().upper()
        if not sym:
            continue
        cat = normalize_sl_category(rec.get("category"), sym, rec.get("option_type"))
        if cat not in rules:
            rules[cat] = {
                "category_default": None,
                "tp1": None,
                "tp2": None,
                "tp3": None,
                "lots": 1,
                "lot_size": 1,
                "usd_amount": 100,
                "symbols": {},
            }
        sym_map = rules[cat].setdefault("symbols", {})
        key = f"{sym}:{'OPT' if rec.get('option_type') else 'FUT'}" if cat == "MCX" else sym
        if key not in sym_map:
            sym_map[key] = {
                "sl": None,
                "tp1": None,
                "tp2": None,
                "tp3": None,
                "lots": None,
                "lot_size": None,
                "usd_amount": None,
            }

    categories_meta = [
        {"key": "OPTIONS", "title": "Indian Options (Index CE / PE)", "unit": "points", "unit_label": "Points (₹)", "sizing_mode": "lots"},
        {"key": "STOCK_OPTIONS", "title": "Stock Options (Equity CE / PE)", "unit": "points", "unit_label": "Points (₹)", "sizing_mode": "lots"},
        {"key": "MCX", "title": "MCX Commodities (Options & Futures)", "unit": "points", "unit_label": "Points (₹)", "sizing_mode": "lots"},
        {"key": "FOREX", "title": "Forex & Metals", "unit": "pips", "unit_label": "Pips", "sizing_mode": "lots"},
        {"key": "CRYPTO", "title": "Crypto Futures / Spot", "unit": "pct", "unit_label": "% of Entry", "sizing_mode": "usd"},
    ]

    result = []
    for meta in categories_meta:
        cat_key = meta["key"]
        block = rules.get(cat_key) or {
            "category_default": None,
            "tp1": None,
            "tp2": None,
            "tp3": None,
            "lots": 1,
            "lot_size": 1,
            "usd_amount": 100,
            "symbols": {},
        }
        items = []
        for sym_key, raw_entry in sorted((block.get("symbols") or {}).items()):
            entry = (
                raw_entry
                if isinstance(raw_entry, dict)
                else {"sl": raw_entry, "tp1": None, "tp2": None, "tp3": None, "lots": None, "lot_size": None, "usd_amount": None}
            )
            display = sym_key
            hint = ""
            if cat_key == "MCX":
                if sym_key.endswith(":OPT"):
                    display = f"{sym_key[:-4]} (Options CE/PE)"
                elif sym_key.endswith(":FUT"):
                    display = f"{sym_key[:-4]} (Futures)"
            elif cat_key == "FOREX":
                hint = f"1 pip = {forex_pip_size(sym_key):g}"
            items.append({
                "key": sym_key,
                "label": display,
                "hint": hint,
                "sl": "" if entry.get("sl") is None else entry.get("sl"),
                "tp1": "" if entry.get("tp1") is None else entry.get("tp1"),
                "tp2": "" if entry.get("tp2") is None else entry.get("tp2"),
                "tp3": "" if entry.get("tp3") is None else entry.get("tp3"),
                "lots": "" if entry.get("lots") is None else entry.get("lots"),
                "lot_size": "" if entry.get("lot_size") is None else entry.get("lot_size"),
                "usd_amount": "" if entry.get("usd_amount") is None else entry.get("usd_amount"),
            })
        result.append({
            **meta,
            "category_default": "" if block.get("category_default") is None else block.get("category_default"),
            "tp1": "" if block.get("tp1") is None else block.get("tp1"),
            "tp2": "" if block.get("tp2") is None else block.get("tp2"),
            "tp3": "" if block.get("tp3") is None else block.get("tp3"),
            "lots": "" if block.get("lots") is None else block.get("lots"),
            "lot_size": "" if block.get("lot_size") is None else block.get("lot_size"),
            "usd_amount": "" if block.get("usd_amount") is None else block.get("usd_amount"),
            "symbols": items,
        })
    return jsonify({"categories": result})


@bp.put("/api/default-sl")
def save_default_sl():
    data = request.get_json() or {}
    incoming = data.get("rules") or {}
    clean = {}
    for cat_key in ("OPTIONS", "STOCK_OPTIONS", "MCX", "FOREX", "CRYPTO"):
        block = incoming.get(cat_key) or {}
        clean_syms = {}
        for k, v in (block.get("symbols") or {}).items():
            sk = str(k or "").strip().upper()
            if not sk:
                continue
            if isinstance(v, dict):
                clean_syms[sk] = {
                    "sl": _opt_pos_float(v.get("sl")),
                    "tp1": _opt_pos_float(v.get("tp1")),
                    "tp2": _opt_pos_float(v.get("tp2")),
                    "tp3": _opt_pos_float(v.get("tp3")),
                    "lots": _opt_pos_float(v.get("lots")),
                    "lot_size": _opt_pos_float(v.get("lot_size")),
                    "usd_amount": _opt_pos_float(v.get("usd_amount")),
                }
            else:
                clean_syms[sk] = {
                    "sl": _opt_pos_float(v),
                    "tp1": None,
                    "tp2": None,
                    "tp3": None,
                    "lots": None,
                    "lot_size": None,
                    "usd_amount": None,
                }
        clean[cat_key] = {
            "category_default": _opt_pos_float(block.get("category_default")),
            "tp1": _opt_pos_float(block.get("tp1")),
            "tp2": _opt_pos_float(block.get("tp2")),
            "tp3": _opt_pos_float(block.get("tp3")),
            "lots": _opt_pos_float(block.get("lots")) or 1,
            "lot_size": _opt_pos_float(block.get("lot_size")),
            "usd_amount": _opt_pos_float(block.get("usd_amount")) or 100,
            "symbols": clean_syms,
        }

    db().set_settings({"default_sl_rules": json.dumps(clean)})
    with db().connection() as c:
        db().log(c, "INFO", "settings", "Updated Default SL, TP1-TP3, and Lot/Dollar sizing rules")
    return jsonify({"ok": True})


@bp.post("/api/default-sl/apply-missing")
def apply_default_sl_missing():
    rules = db().get_default_sl_rules()
    candidates = rows(
        "SELECT s.id, s.symbol, s.direction, s.entry_price, s.additional_entries, s.stop_loss, "
        "s.targets, s.option_type, s.parsing_explanation, r.category "
        "FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id "
        "WHERE (s.stop_loss IS NULL OR s.targets IS NULL OR s.targets='' OR s.targets='[]') "
        "AND s.entry_price IS NOT NULL AND s.direction IN ('BUY','SELL') AND s.symbol IS NOT NULL"
    )
    updated = 0
    with db().connection() as c:
        for row in candidates:
            item = dict(row)
            orig_sl = item.get("stop_loss")
            orig_targets = json_field(item.get("targets"))
            item["targets"] = list(orig_targets)
            item["additional_entries"] = json_field(item.get("additional_entries"))
            item["explanation"] = item.get("parsing_explanation") or ""

            apply_default_sl(item, item.get("category") or "", rules)
            apply_default_targets(item, item.get("category") or "", rules)

            if item.get("stop_loss") != orig_sl or item.get("targets") != orig_targets:
                c.execute(
                    "UPDATE signals SET stop_loss=?, targets=?, parsing_explanation=?, updated_at=? WHERE id=?",
                    (item["stop_loss"], json.dumps(item["targets"]), item["explanation"], utcnow(), item["id"])
                )
                updated += 1
        db().log(c, "INFO", "settings", f"Applied Default SL/TPs to {updated} existing signals")
    return jsonify({"ok": True, "checked": len(candidates), "updated": updated})


@bp.get("/api/integrations")
def integrations():
    results = []
    for r in rows("SELECT * FROM integrations ORDER BY provider"):
        config = json.loads(r["config_json"])
        secrets = json.loads(r["secrets_json"])
        results.append({
            "provider": r["provider"],
            "config": config,
            "secret_fields": list(secrets.keys()),
            "status": r["status"],
            "updated_at": r["updated_at"],
        })
    return jsonify(results)


@bp.put("/api/integrations/<provider>")
def save_integration(provider):
    if provider not in {"google_sheets", "binance", "angel_one", "twelve_data"}:
        return jsonify({"error": "Unsupported provider"}), 400
    data = request.get_json() or {}
    config = data.get("config", {})
    new_secrets = data.get("secrets", {})
    with db().connection() as c:
        old = c.execute("SELECT secrets_json FROM integrations WHERE provider=?", (provider,)).fetchone()
        secrets = json.loads(old[0]) if old else {}
        secrets.update({k: v for k, v in new_secrets.items() if v})
        c.execute(
            "INSERT INTO integrations(provider,config_json,secrets_json,status,updated_at) VALUES(?,?,?,?,?) "
            "ON CONFLICT(provider) DO UPDATE SET config_json=excluded.config_json,secrets_json=excluded.secrets_json,status='configured',updated_at=excluded.updated_at",
            (provider, json.dumps(config), json.dumps(secrets), "configured", utcnow())
        )
        db().log(c, "INFO", "integrations", f"Updated {provider} configuration")
    return jsonify({"ok": True, "warning": "Secrets are masked in the UI and never returned by the API. They are stored locally; protect the device, database file, and backups."})


@bp.delete("/api/integrations/<provider>")
def delete_integration(provider):
    with db().connection() as c:
        c.execute("DELETE FROM integrations WHERE provider=?", (provider,))
        db().log(c, "INFO", "integrations", f"Deleted {provider} credentials")
    return jsonify({"ok": True})


@bp.post("/api/sync")
def sync():
    return jsonify(sync_google_sheets(db()))


@bp.get("/api/sync/logs")
def sync_logs():
    return jsonify(rows("SELECT * FROM synchronization_logs ORDER BY id DESC LIMIT 100"))


@bp.post("/api/candles/fetch")
def candles_fetch():
    data = request.get_json() or {}
    provider = data.get("provider", "binance")
    if provider != "binance":
        return jsonify({"error": "Use /api/angel-one/candles for Indian market data. Forex verification is intentionally disabled."}), 400
    try:
        start = datetime.fromisoformat(data["start"].replace("Z", "+00:00"))
        end = datetime.fromisoformat(data["end"].replace("Z", "+00:00"))
        if end <= start:
            raise ValueError("End time must be later than start time.")
        integration = one("SELECT config_json FROM integrations WHERE provider='binance'")
        config = json.loads(integration["config_json"]) if integration else {}
        candles = BinanceFutures().fetch(data["symbol"], data.get("timeframe", "5m"), start, end, config)
    except KeyError as e:
        return jsonify({"error": f"Missing field: {e}"}), 400
    except (ValueError, ProviderError) as e:
        return jsonify({"error": str(e)}), 400
    stored = store_candles(candles)
    return jsonify({"stored": stored, "downloaded": len(candles)})


@bp.post("/api/twelve-data/candles")
def twelve_candles():
    data = request.get_json() or {}
    record = one("SELECT * FROM integrations WHERE provider='twelve_data'")
    if not record:
        return jsonify({"error": "Configure Twelve Data first."}), 400
    try:
        config = json.loads(record["config_json"])
        secrets = json.loads(record["secrets_json"])
        start = datetime.fromisoformat(data["start"].replace("Z", "+00:00")) if data.get("start") else None
        end = datetime.fromisoformat(data["end"].replace("Z", "+00:00")) if data.get("end") else None
        candles, headers = TwelveData().fetch(data["symbol"], data.get("interval", "1min"), start, end, config, secrets.get("api_key", ""))
        stored = store_candles(candles)
        credits = float(headers.get("api-credits-used", "1") or 1)
        with db().connection() as c:
            c.execute(
                "INSERT INTO api_request_log(provider,endpoint,requested_at,completed_at,status,credits_used,candles_returned) VALUES(?,?,?,?,?,?,?)",
                ("twelve_data", "/time_series", utcnow(), utcnow(), "success", credits, len(candles))
            )
        return jsonify({"downloaded": len(candles), "stored": stored, "credits_used": credits})
    except (KeyError, ValueError, ProviderError) as e:
        return jsonify({"error": str(e)}), 400


@bp.get("/api/twelve-data/usage")
def twelve_usage():
    day = datetime.now(timezone.utc).date().isoformat()
    minute = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M")
    daily = float(db().settings().get("twelve_daily_credits", "800"))
    per_minute = float(db().settings().get("twelve_minute_credits", "8"))
    with db().connection() as c:
        used = c.execute("SELECT COALESCE(SUM(credits_used),0) n FROM api_request_log WHERE provider='twelve_data' AND substr(requested_at,1,10)=?", (day,)).fetchone()["n"]
        now = c.execute("SELECT COALESCE(SUM(credits_used),0) n FROM api_request_log WHERE provider='twelve_data' AND substr(requested_at,1,16)=?", (minute,)).fetchone()["n"]
    return jsonify({
        "daily_allowance": daily,
        "daily_used": used,
        "daily_remaining": max(0, daily - used),
        "minute_allowance": per_minute,
        "minute_used": now,
        "minute_remaining": max(0, per_minute - now),
    })


def store_candles(candles):
    stored = 0
    with db().connection() as c:
        for x in candles:
            cur = c.execute(
                "INSERT OR IGNORE INTO market_candles(provider,symbol,exchange,interval,candle_timestamp,timeframe,candle_time,open,high,low,close,volume,retrieved_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (x["provider"], x["symbol"], x.get("exchange", ""), x["interval"], x["candle_timestamp"], x["interval"], x["candle_timestamp"], x["open"], x["high"], x["low"], x["close"], x.get("volume"), utcnow())
            )
            stored += cur.rowcount
        if candles:
            db().log(c, "INFO", "market_data", f"Stored {stored}/{len(candles)} {candles[0]['provider']} candles")
    return stored


@bp.post("/api/binance/test")
def binance_test():
    try:
        end = datetime.now(timezone.utc)
        integration = one("SELECT config_json FROM integrations WHERE provider='binance'")
        config = json.loads(integration["config_json"]) if integration else {}
        candles = BinanceFutures().fetch("BTCUSDT", "1m", end.replace(second=0, microsecond=0), end, config)
        return jsonify({"ok": True, "candles": len(candles), "message": "Public USD-M Futures endpoint responded."})
    except ProviderError as e:
        return jsonify({"ok": False, "error": str(e)}), 502


def angel_integration():
    record = one("SELECT * FROM integrations WHERE provider='angel_one'")
    if not record:
        raise ProviderError("Angel One is not configured.")
    return json.loads(record["config_json"]), json.loads(record["secrets_json"])


@bp.post("/api/angel-one/test")
def angel_test():
    try:
        _, secrets = angel_integration()
        session = AngelOneProvider().authenticate(secrets)
        with db().connection() as c:
            c.execute("UPDATE integrations SET status='connected',updated_at=? WHERE provider='angel_one'", (utcnow(),))
            db().log(c, "INFO", "angel_one", "Authentication succeeded.")
        return jsonify({"ok": True, "session_status": "connected", "authenticated_at": session["authenticated_at"]})
    except ProviderError as e:
        return jsonify({"ok": False, "error": str(e)}), 400


@bp.post("/api/angel-one/instruments/refresh")
def angel_instruments_refresh():
    url = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json"
    try:
        with urlopen(Request(url, headers={"User-Agent": "SignalAnalyzer/1.0"}), timeout=60) as r:
            source = json.load(r)
        if not isinstance(source, list):
            raise ValueError("Instrument master was not a JSON array.")
        count = 0
        with db().connection() as c:
            for item in source:
                token = str(item.get("token") or "")
                if not token:
                    continue
                c.execute(
                    "INSERT INTO instruments(symbol,trading_symbol,instrument_token,exchange_segment,instrument_type,expiry,strike,lot_size,tick_size,raw_json,updated_at) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(instrument_token) DO UPDATE SET "
                    "symbol=excluded.symbol,trading_symbol=excluded.trading_symbol,exchange_segment=excluded.exchange_segment,"
                    "instrument_type=excluded.instrument_type,expiry=excluded.expiry,strike=excluded.strike,"
                    "lot_size=excluded.lot_size,tick_size=excluded.tick_size,raw_json=excluded.raw_json,updated_at=excluded.updated_at",
                    (item.get("symbol"), item.get("symbol") or "", token, item.get("exch_seg") or "", item.get("instrumenttype"), item.get("expiry"), float(item.get("strike") or 0), float(item.get("lotsize") or 0), float(item.get("tick_size") or 0), json.dumps(item), utcnow())
                )
                count += 1
            db().log(c, "INFO", "angel_one", f"Instrument master refreshed: {count} records.")
        return jsonify({"ok": True, "instruments": count})
    except Exception as e:
        return jsonify({"error": f"Instrument master refresh failed: {e}"}), 502


BUILTIN_SPOT_INDICES = [
    {"trading_symbol": "NIFTY 50 (Spot Index)", "instrument_token": "99926000", "exchange_segment": "NSE", "instrument_type": "AMXIDX", "expiry": "", "strike": 0, "lot_size": 1, "underlying": "NIFTY"},
    {"trading_symbol": "NIFTY BANK (Spot Index)", "instrument_token": "99926009", "exchange_segment": "NSE", "instrument_type": "AMXIDX", "expiry": "", "strike": 0, "lot_size": 1, "underlying": "BANKNIFTY"},
    {"trading_symbol": "FINNIFTY (Spot Index)", "instrument_token": "99926037", "exchange_segment": "NSE", "instrument_type": "AMXIDX", "expiry": "", "strike": 0, "lot_size": 1, "underlying": "FINNIFTY"},
    {"trading_symbol": "MIDCPNIFTY (Spot Index)", "instrument_token": "99926074", "exchange_segment": "NSE", "instrument_type": "AMXIDX", "expiry": "", "strike": 0, "lot_size": 1, "underlying": "MIDCPNIFTY"},
    {"trading_symbol": "INDIA VIX (Spot Index)", "instrument_token": "99926017", "exchange_segment": "NSE", "instrument_type": "AMXIDX", "expiry": "", "strike": 0, "lot_size": 1, "underlying": "INDIAVIX"},
    {"trading_symbol": "SENSEX (Spot Index)", "instrument_token": "99919000", "exchange_segment": "BSE", "instrument_type": "AMXIDX", "expiry": "", "strike": 0, "lot_size": 1, "underlying": "SENSEX"},
]


def _parse_expiry(exp):
    if not exp:
        return datetime.max
    for fmt in ("%d%b%Y", "%Y-%m-%d", "%d-%b-%Y"):
        try:
            return datetime.strptime(exp.strip().upper(), fmt)
        except ValueError:
            continue
    return datetime.max


@bp.get("/api/angel-one/catalog")
def angel_catalog():
    total = one("SELECT COUNT(*) n FROM instruments")["n"]
    exchange = request.args.get("exchange", "NFO").strip().upper()
    inst_type = request.args.get("instrument_type", "FUTIDX").strip().upper()
    underlying = request.args.get("underlying", "").strip().upper()
    expiry = request.args.get("expiry", "").strip().upper()
    q = request.args.get("q", "").strip().upper()
    if not total and inst_type == "AMXIDX":
        spot = [x for x in BUILTIN_SPOT_INDICES if x["exchange_segment"] == exchange] or BUILTIN_SPOT_INDICES
        u_list = [x["underlying"] for x in spot]
        sel_u = underlying if underlying in u_list else (u_list[0] if u_list else "")
        contracts = [x for x in spot if not sel_u or x["underlying"] == sel_u]
        return jsonify({
            "total_instruments": 0,
            "selected_underlying": sel_u,
            "selected_expiry": "",
            "underlyings": u_list,
            "expiries": [],
            "contracts": contracts,
        })
    if not total:
        return jsonify({"total_instruments": 0, "underlyings": [], "expiries": [], "contracts": []})
    clauses = ["exchange_segment=?"]
    params = [exchange]
    if inst_type and inst_type != "ALL":
        clauses.append("instrument_type=?")
        params.append(inst_type)
    fetched = rows(
        "SELECT symbol,trading_symbol,instrument_token,exchange_segment,instrument_type,expiry,strike,lot_size,raw_json "
        "FROM instruments WHERE " + " AND ".join(clauses),
        params,
    )
    items = []
    underlying_set = set()
    for r in fetched:
        try:
            raw = json.loads(r.get("raw_json") or "{}")
        except json.JSONDecodeError:
            raw = {}
        name = (raw.get("name") or r.get("symbol") or "").strip().upper()
        if not name:
            continue
        r["underlying"] = name
        underlying_set.add(name)
        items.append(r)
    priority = [
        "NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50",
        "SENSEX", "BANKEX",
        "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI",
        "GOLD", "GOLDM", "GOLDPETAL", "SILVER", "SILVERM", "SILVERMIC", "COPPER", "ZINC", "ALUMINIUM", "LEAD",
    ]
    prio_rank = {sym: idx for idx, sym in enumerate(priority)}
    underlyings = sorted(underlying_set, key=lambda x: (prio_rank.get(x, 999), x))
    active_underlying = underlying if (underlying and underlying in underlying_set) else (underlyings[0] if underlyings else "")
    by_underlying = [r for r in items if not active_underlying or r["underlying"] == active_underlying]
    expiry_set = {(r.get("expiry") or "").strip().upper() for r in by_underlying if (r.get("expiry") or "").strip()}
    expiries = sorted(expiry_set, key=_parse_expiry)
    active_expiry = expiry if (expiry and expiry in expiry_set) else (expiries[0] if expiries else "")
    filtered = [
        r for r in by_underlying
        if (not active_expiry or (r.get("expiry") or "").strip().upper() == active_expiry)
        and (not q or q in (r.get("trading_symbol") or "").upper() or q == str(r.get("instrument_token")))
    ]
    filtered.sort(key=lambda r: (_parse_expiry(r.get("expiry")), float(r.get("strike") or 0), r.get("trading_symbol") or ""))
    contracts = [
        {
            "trading_symbol": r["trading_symbol"],
            "instrument_token": r["instrument_token"],
            "exchange_segment": r["exchange_segment"],
            "instrument_type": r["instrument_type"] or "",
            "expiry": r["expiry"] or "",
            "strike": round(float(r["strike"]) / 100.0, 2) if float(r["strike"] or 0) > 0 else 0,
            "lot_size": int(float(r["lot_size"] or 0)),
            "underlying": r["underlying"],
        }
        for r in filtered[:300]
    ]
    return jsonify({
        "total_instruments": total,
        "selected_underlying": active_underlying,
        "selected_expiry": active_expiry,
        "underlyings": underlyings,
        "expiries": expiries,
        "contracts": contracts,
    })


@bp.get("/api/angel-one/instruments")
def angel_instruments():
    q = request.args.get("q", "").strip()
    exchange = request.args.get("exchange", "").strip().upper()
    clauses = []
    params = []
    if q:
        clauses.append("(trading_symbol LIKE ? OR symbol LIKE ? OR instrument_token=?)")
        params += ["%" + q + "%", "%" + q + "%", q]
    if exchange:
        clauses.append("exchange_segment=?")
        params.append(exchange)
    return jsonify(rows("SELECT symbol,trading_symbol,instrument_token,exchange_segment,instrument_type,expiry,strike,lot_size,tick_size FROM instruments" + (" WHERE " + " AND ".join(clauses) if clauses else "") + " ORDER BY trading_symbol LIMIT 100", params))


@bp.put("/api/angel-one/mappings")
def angel_mapping():
    data = request.get_json() or {}
    key = data.get("lookup_key")
    token = data.get("instrument_token")
    exchange = data.get("exchange_segment")
    if not key or not token or not exchange:
        return jsonify({"error": "lookup_key, instrument_token, and exchange_segment are required."}), 400
    with db().connection() as c:
        valid = c.execute("SELECT 1 FROM instruments WHERE instrument_token=? AND exchange_segment=?", (str(token), exchange)).fetchone()
        if not valid:
            return jsonify({"error": "Token is not present in the downloaded Angel One instrument master."}), 400
        c.execute(
            "INSERT INTO instrument_mappings(lookup_key,instrument_token,exchange_segment,note,updated_at) VALUES(?,?,?,?,?) "
            "ON CONFLICT(lookup_key) DO UPDATE SET instrument_token=excluded.instrument_token,exchange_segment=excluded.exchange_segment,note=excluded.note,updated_at=excluded.updated_at",
            (key, str(token), exchange, data.get("note", ""), utcnow())
        )
    return jsonify({"ok": True})


@bp.post("/api/angel-one/candles")
def angel_candles():
    data = request.get_json() or {}
    interval_map = {
        "1m": "ONE_MINUTE", "3m": "THREE_MINUTE", "5m": "FIVE_MINUTE",
        "10m": "TEN_MINUTE", "15m": "FIFTEEN_MINUTE", "30m": "THIRTY_MINUTE",
        "1h": "ONE_HOUR", "1d": "ONE_DAY",
    }
    ist = timezone(timedelta(hours=5, minutes=30))
    try:
        _, secrets = angel_integration()
        session = AngelOneProvider().authenticate(secrets)
        start = datetime.fromisoformat(data["start"].replace("Z", "+00:00"))
        end = datetime.fromisoformat(data["end"].replace("Z", "+00:00"))
        if start.tzinfo is not None:
            start = start.astimezone(ist)
        if end.tzinfo is not None:
            end = end.astimezone(ist)
        if end <= start:
            raise ValueError("End time must be later than start time.")
        exchange = (data.get("exchange") or "").strip().upper()
        token = str(data.get("instrument_token") or "").strip()
        if not exchange or not token:
            raise ValueError("Select a valid exchange and contract token first.")
        raw_interval = (data.get("interval") or "FIVE_MINUTE").strip()
        interval = interval_map.get(raw_interval, raw_interval)
        candles = AngelOneProvider().fetch(session["jwt_token"], secrets["api_key"], exchange, token, interval, start, end)
        stored = store_candles(candles)
        return jsonify({"downloaded": len(candles), "stored": stored})
    except (KeyError, ValueError, ProviderError) as e:
        return jsonify({"error": str(e)}), 400


@bp.get("/api/market/coverage")
def market_coverage():
    return jsonify(rows("""
        SELECT mc.provider,
               COALESCE(i.trading_symbol || ' (' || mc.symbol || ')', mc.symbol) AS symbol,
               mc.exchange,
               mc.interval,
               COUNT(*) candles,
               MIN(mc.candle_timestamp) earliest,
               MAX(mc.candle_timestamp) latest,
               MAX(mc.retrieved_at) last_retrieved
        FROM market_candles mc
        LEFT JOIN instruments i
          ON mc.provider='angel_one' AND i.instrument_token=mc.symbol
        GROUP BY mc.provider, mc.symbol, mc.exchange, mc.interval
        ORDER BY mc.provider, symbol
    """))


@bp.delete("/api/market/cache")
def market_cache_delete():
    data = request.get_json() or {}
    provider = data.get("provider")
    symbol = data.get("symbol")
    if not provider or not symbol:
        return jsonify({"error": "Provider and symbol are required for safe cache deletion."}), 400
    with db().connection() as c:
        count = c.execute("DELETE FROM market_candles WHERE provider=? AND symbol=?", (provider, symbol)).rowcount
        db().log(c, "WARNING", "market_data", f"Deleted {count} cached candles for {provider}:{symbol}.")
    return jsonify({"deleted": count})


@bp.post("/api/signals/<int:signal_id>/verify")
def verify_signal(signal_id):
    result = process_signal(db(), signal_id, "manual")
    return jsonify(result), (404 if result["status"] == "missing" else 409 if result["status"] == "duplicate" else 200)


@bp.post("/api/signals/process")
def process_selected_signals():
    ids = (request.get_json() or {}).get("signal_ids", [])
    if not isinstance(ids, list) or not ids:
        return jsonify({"error": "Select at least one signal."}), 400
    return jsonify({"results": process_signals_batch(db(), ids[:500], "manual")})


@bp.get("/api/signals/<int:signal_id>/schedule")
def signal_schedule(signal_id):
    return jsonify({
        "schedule": one("SELECT * FROM verification_schedules WHERE signal_id=?", (signal_id,)),
        "runs": rows("SELECT * FROM verification_runs WHERE signal_id=? ORDER BY id DESC LIMIT 100", (signal_id,)),
    })


@bp.get("/api/analytics/channels")
def channel_analytics():
    today_prefix = datetime.now().date().isoformat()
    return jsonify(rows("""
        SELECT r.channel,
               COUNT(*) total,
               SUM(substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ?) AS today_total,
               SUM(substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? AND s.verification_status = 'target_hit') AS today_tp,
               SUM(substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? AND s.verification_status = 'stop_loss_hit') AS today_sl,
               SUM(s.parsing_status IN ('parsed','manually_corrected')) parsed,
               COALESCE(SUM(tr.trades_count), 0) AS trades_triggered,
               SUM(s.verification_status='target_hit') targets,
               SUM(s.verification_status='stop_loss_hit') stops,
               SUM(s.verification_status='pending') pending,
               ROUND(COALESCE(SUM(CASE WHEN tr.pnl_currency='INR' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS pnl_inr,
               ROUND(COALESCE(SUM(CASE WHEN tr.pnl_currency='USD' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS pnl_usd,
               ROUND(COALESCE(SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? AND tr.pnl_currency='INR' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS today_pnl_inr,
               ROUND(COALESCE(SUM(CASE WHEN substr(COALESCE(r.notification_time, r.received_at), 1, 10) = ? AND tr.pnl_currency='USD' THEN tr.pnl_amount ELSE 0 END), 0), 2) AS today_pnl_usd
        FROM signals s
        JOIN raw_notifications r ON r.id=s.raw_notification_id
        LEFT JOIN trade_results tr ON tr.signal_id=s.id
        WHERE r.is_duplicate=0
        GROUP BY r.channel
        ORDER BY total DESC
    """, (today_prefix, today_prefix, today_prefix, today_prefix, today_prefix)))


@bp.get("/api/logs")
def logs():
    return jsonify(rows("SELECT * FROM system_logs ORDER BY id DESC LIMIT 300"))


@bp.get("/api/export/<kind>.csv")
def export(kind):
    lookup = {
        "raw": ("SELECT * FROM raw_notifications ORDER BY id", "raw_notifications"),
        "signals": ("SELECT s.*,r.channel,r.category,r.notification_time FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id ORDER BY s.id", "signals"),
        "channels": ("SELECT r.channel,r.category,COUNT(*) signal_count,SUM(s.verification_status='target_hit') target_hits,SUM(s.verification_status='stop_loss_hit') stop_loss_hits FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id GROUP BY r.channel,r.category", "channel_report"),
    }
    if kind not in lookup:
        return jsonify({"error": "Unknown export"}), 404
    data = rows(lookup[kind][0])
    out = io.StringIO()
    writer = csv.DictWriter(out, fieldnames=list(data[0].keys()) if data else ["no_data"])
    writer.writeheader()
    writer.writerows(data)
    return Response(out.getvalue(), mimetype="text/csv", headers={"Content-Disposition": f"attachment; filename={lookup[kind][1]}.csv"})


@bp.post("/api/backup")
def backup():
    backup_path = Path(current_app.instance_path) / f"signals-backup-{datetime.now().strftime('%Y%m%d-%H%M%S')}.sqlite3"
    shutil.copy2(db().path, backup_path)
    return jsonify({"file": backup_path.name, "notice": "Backups contain credentials in plaintext; store them securely."})


def register_routes(app):
    app.register_blueprint(bp)