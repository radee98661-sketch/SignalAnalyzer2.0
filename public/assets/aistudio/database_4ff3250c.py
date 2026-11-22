import copy, json, re, uuid
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from .parser import DEFAULT_SL_SEED, apply_default_sl, apply_default_targets, parse_signals

DEFAULT_CATEGORIES = ["Crypto", "Forex", "Indian Options", "Stock Options", "MCX"]
SOURCE_COLUMNS = [
    "Received At", "Notification Time", "Source App", "Package",
    "Channel", "Category", "Title", "Message", "Notification ID", "Delivery ID"
]


def utcnow():
    return datetime.now(timezone.utc).isoformat()


DETAIL_MARKERS = re.compile(
    r"\b(?:ENTRY|ENTRIES|TARGETS?|TP\d*|SL|STOP\s*LOSS|LEVERAGE)\b|\b\d+(?:\.\d+)?X\b",
    re.I
)


def normalized_message(value):
    return re.sub(r"\s+", " ", re.sub(r"[^A-Z0-9]+", " ", (value or "").upper())).strip()


def message_subject(value):
    text = (value or "").upper()
    symbol = re.search(r"\b[A-Z0-9]+_?USDT\b|\b(?:NIFTY|BANKNIFTY|SENSEX|GOLD|SILVER|CRUDEOIL|XAUUSD)\b", text)
    direction = re.search(r"\b(?:BUY|SELL|LONG|SHORT)\b", text)
    return (symbol.group(0).replace("_", "") if symbol else None, direction.group(0) if direction else None)


def notification_datetime(row):
    for value in (row.get("Notification Time"), row.get("Received At")):
        if not value:
            continue
        try:
            parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
            return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
        except ValueError:
            pass
        for fmt in ("%m/%d/%Y %H:%M:%S", "%Y-%m-%d %H:%M:%S"):
            try:
                return datetime.strptime(str(value), fmt).replace(tzinfo=timezone.utc)
            except ValueError:
                pass
    return datetime.now(timezone.utc)


class Database:
    def __init__(self, path):
        self.path = path

    @contextmanager
    def connection(self):
        con = sqlite3.connect(self.path)
        con.row_factory = sqlite3.Row
        con.execute("PRAGMA foreign_keys=ON")
        try:
            yield con
            con.commit()
        except Exception:
            con.rollback()
            raise
        finally:
            con.close()

    def _migrate_signals_multi_row(self, c):
        """Allow multiple signal rows per raw_notification_id (for multi-trade messages) and ensure entry_mode exists."""
        has_signals = c.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='signals'").fetchone()
        if not has_signals:
            return
        sig_cols = {r["name"] for r in c.execute("PRAGMA table_info(signals)")}
        has_unique_raw = False
        for idx in c.execute("PRAGMA index_list(signals)").fetchall():
            if idx["unique"]:
                cols = [r["name"] for r in c.execute(f"PRAGMA index_info('{idx['name']}')").fetchall()]
                if cols == ["raw_notification_id"]:
                    has_unique_raw = True
                    break

        if not has_unique_raw:
            if "entry_mode" not in sig_cols:
                c.execute("ALTER TABLE signals ADD COLUMN entry_mode TEXT NOT NULL DEFAULT 'limit'")
            c.execute("CREATE INDEX IF NOT EXISTS idx_signals_raw_id ON signals(raw_notification_id)")
            return

        entry_mode_expr = "COALESCE(entry_mode, 'limit')" if "entry_mode" in sig_cols else "'limit'"
        c.execute("PRAGMA foreign_keys=OFF")
        c.executescript(f"""
            CREATE TABLE signals_new (
              id INTEGER PRIMARY KEY,
              raw_notification_id INTEGER NOT NULL REFERENCES raw_notifications(id) ON DELETE CASCADE,
              symbol TEXT,
              direction TEXT,
              entry_price REAL,
              additional_entries TEXT,
              stop_loss REAL,
              targets TEXT,
              option_type TEXT,
              strike_price REAL,
              expiry_date TEXT,
              entry_mode TEXT NOT NULL DEFAULT 'limit',
              parsing_status TEXT NOT NULL,
              parsing_confidence REAL NOT NULL DEFAULT 0,
              manual_review_status TEXT NOT NULL DEFAULT 'not_required',
              verification_status TEXT NOT NULL DEFAULT 'pending',
              final_result TEXT,
              parsing_explanation TEXT,
              updated_at TEXT NOT NULL
            );
            INSERT INTO signals_new(
              id, raw_notification_id, symbol, direction, entry_price, additional_entries,
              stop_loss, targets, option_type, strike_price, expiry_date, entry_mode,
              parsing_status, parsing_confidence, manual_review_status, verification_status,
              final_result, parsing_explanation, updated_at
            )
            SELECT
              id, raw_notification_id, symbol, direction, entry_price, additional_entries,
              stop_loss, targets, option_type, strike_price, expiry_date, {entry_mode_expr},
              parsing_status, parsing_confidence, manual_review_status, verification_status,
              final_result, parsing_explanation, updated_at
            FROM signals;
            DROP TABLE signals;
            ALTER TABLE signals_new RENAME TO signals;
            CREATE INDEX IF NOT EXISTS idx_signals_status ON signals(verification_status);
            CREATE INDEX IF NOT EXISTS idx_signals_raw_id ON signals(raw_notification_id);
        """)
        c.execute("PRAGMA foreign_keys=ON")

    def initialize(self):
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as c:
            self._migrate_signals_multi_row(c)
            c.executescript('''
            CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE IF NOT EXISTS channels (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, category TEXT, enabled INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE IF NOT EXISTS raw_notifications (
              id INTEGER PRIMARY KEY, received_at TEXT, notification_time TEXT, source_app TEXT, package TEXT, channel TEXT, category TEXT, title TEXT, message TEXT, notification_id TEXT, delivery_id TEXT, imported_at TEXT NOT NULL,
              UNIQUE(notification_id));
            CREATE TABLE IF NOT EXISTS signals (
              id INTEGER PRIMARY KEY, raw_notification_id INTEGER NOT NULL REFERENCES raw_notifications(id) ON DELETE CASCADE, symbol TEXT, direction TEXT, entry_price REAL, additional_entries TEXT, stop_loss REAL, targets TEXT, option_type TEXT, strike_price REAL, expiry_date TEXT, entry_mode TEXT NOT NULL DEFAULT 'limit', parsing_status TEXT NOT NULL, parsing_confidence REAL NOT NULL DEFAULT 0, manual_review_status TEXT NOT NULL DEFAULT 'not_required', verification_status TEXT NOT NULL DEFAULT 'pending', final_result TEXT, parsing_explanation TEXT, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS integrations (id INTEGER PRIMARY KEY, provider TEXT NOT NULL UNIQUE, config_json TEXT NOT NULL DEFAULT '{}', secrets_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'not_configured', updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS application_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS synchronization_logs (id INTEGER PRIMARY KEY, started_at TEXT NOT NULL, finished_at TEXT, provider TEXT NOT NULL, status TEXT NOT NULL, imported_rows INTEGER NOT NULL DEFAULT 0, duplicate_rows INTEGER NOT NULL DEFAULT 0, skipped_rows INTEGER NOT NULL DEFAULT 0, failed_rows INTEGER NOT NULL DEFAULT 0, detail TEXT);
            CREATE TABLE IF NOT EXISTS market_candles (id INTEGER PRIMARY KEY, provider TEXT NOT NULL, symbol TEXT NOT NULL, exchange TEXT NOT NULL DEFAULT '', interval TEXT NOT NULL DEFAULT '', candle_timestamp TEXT NOT NULL DEFAULT '', timeframe TEXT NOT NULL, candle_time TEXT NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL, retrieved_at TEXT, UNIQUE(provider,symbol,timeframe,candle_time));
            CREATE TABLE IF NOT EXISTS instruments (id INTEGER PRIMARY KEY, symbol TEXT, trading_symbol TEXT NOT NULL, instrument_token TEXT NOT NULL UNIQUE, exchange_segment TEXT NOT NULL, instrument_type TEXT, expiry TEXT, strike REAL, lot_size REAL, tick_size REAL, raw_json TEXT, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS instrument_mappings (id INTEGER PRIMARY KEY, lookup_key TEXT NOT NULL UNIQUE, instrument_token TEXT NOT NULL, exchange_segment TEXT NOT NULL, note TEXT, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS api_request_log (id INTEGER PRIMARY KEY, provider TEXT NOT NULL, endpoint TEXT NOT NULL, requested_at TEXT NOT NULL, completed_at TEXT, status TEXT NOT NULL, credits_used REAL NOT NULL DEFAULT 0, candles_returned INTEGER NOT NULL DEFAULT 0, error TEXT, request_key TEXT);
            CREATE TABLE IF NOT EXISTS signal_analysis (id INTEGER PRIMARY KEY, signal_id INTEGER NOT NULL REFERENCES signals(id) ON DELETE CASCADE, analyzed_at TEXT NOT NULL, decision TEXT NOT NULL, detail TEXT, candle_time TEXT);
            CREATE TABLE IF NOT EXISTS trade_results (id INTEGER PRIMARY KEY, signal_id INTEGER NOT NULL UNIQUE REFERENCES signals(id) ON DELETE CASCADE, entry_hit_at TEXT, target_hit_at TEXT, stop_loss_hit_at TEXT, events_json TEXT NOT NULL DEFAULT '[]', result TEXT NOT NULL, detail TEXT, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS verification_schedules (id INTEGER PRIMARY KEY, signal_id INTEGER NOT NULL UNIQUE REFERENCES signals(id) ON DELETE CASCADE, signal_start_at TEXT NOT NULL, initial_wait_minutes INTEGER NOT NULL, interval_minutes INTEGER NOT NULL, timeframe TEXT NOT NULL, first_scheduled_at TEXT NOT NULL, next_scheduled_at TEXT, last_verified_at TEXT, expires_at TEXT, status TEXT NOT NULL DEFAULT 'scheduled', last_result TEXT, locked_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS verification_runs (id INTEGER PRIMARY KEY, signal_id INTEGER NOT NULL REFERENCES signals(id) ON DELETE CASCADE, schedule_id INTEGER REFERENCES verification_schedules(id) ON DELETE SET NULL, run_type TEXT NOT NULL, started_at TEXT NOT NULL, finished_at TEXT, status TEXT NOT NULL, result TEXT, detail TEXT, candle_time TEXT);
            CREATE TABLE IF NOT EXISTS parsing_runs (id INTEGER PRIMARY KEY, signal_id INTEGER NOT NULL REFERENCES signals(id) ON DELETE CASCADE, run_type TEXT NOT NULL, parsed_at TEXT NOT NULL, status TEXT NOT NULL, confidence REAL NOT NULL, explanation TEXT, snapshot_json TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS system_logs (id INTEGER PRIMARY KEY, created_at TEXT NOT NULL, severity TEXT NOT NULL, source TEXT NOT NULL, message TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_raw_time ON raw_notifications(notification_time);
            CREATE INDEX IF NOT EXISTS idx_raw_channel ON raw_notifications(channel);
            CREATE INDEX IF NOT EXISTS idx_signals_status ON signals(verification_status);
            CREATE INDEX IF NOT EXISTS idx_signals_raw_id ON signals(raw_notification_id);
            CREATE INDEX IF NOT EXISTS idx_candles_lookup ON market_candles(provider,symbol,timeframe,candle_time);
            CREATE INDEX IF NOT EXISTS idx_api_usage ON api_request_log(provider,requested_at);
            CREATE INDEX IF NOT EXISTS idx_verification_due ON verification_schedules(status,next_scheduled_at);
            CREATE INDEX IF NOT EXISTS idx_verification_runs ON verification_runs(signal_id,started_at);
            CREATE INDEX IF NOT EXISTS idx_parsing_runs ON parsing_runs(signal_id,parsed_at);
            ''')
            columns = {r["name"] for r in c.execute("PRAGMA table_info(market_candles)")}
            for name, definition in (
                ("retrieved_at", "TEXT"),
                ("exchange", "TEXT NOT NULL DEFAULT ''"),
                ("interval", "TEXT NOT NULL DEFAULT ''"),
                ("candle_timestamp", "TEXT NOT NULL DEFAULT ''"),
            ):
                if name not in columns:
                    c.execute(f"ALTER TABLE market_candles ADD COLUMN {name} {definition}")
            c.execute("UPDATE market_candles SET interval=COALESCE(NULLIF(interval,''),timeframe), candle_timestamp=COALESCE(NULLIF(candle_timestamp,''),candle_time), exchange=COALESCE(exchange,'')")
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_candles_normalized ON market_candles(provider,symbol,exchange,interval,candle_timestamp)")
            c.execute("CREATE INDEX IF NOT EXISTS idx_instruments_search ON instruments(trading_symbol,exchange_segment)")

            raw_columns = {r["name"] for r in c.execute("PRAGMA table_info(raw_notifications)")}
            for name, definition in (
                ("is_duplicate", "INTEGER NOT NULL DEFAULT 0"),
                ("duplicate_of", "INTEGER"),
                ("duplicate_reason", "TEXT"),
                ("duplicate_group_id", "TEXT"),
            ):
                if name not in raw_columns:
                    c.execute(f"ALTER TABLE raw_notifications ADD COLUMN {name} {definition}")
            c.execute("CREATE INDEX IF NOT EXISTS idx_raw_duplicate ON raw_notifications(is_duplicate,duplicate_group_id)")

            tr_columns = {r["name"] for r in c.execute("PRAGMA table_info(trade_results)")}
            if "events_json" not in tr_columns:
                c.execute("ALTER TABLE trade_results ADD COLUMN events_json TEXT NOT NULL DEFAULT '[]'")

            for col_name, col_def in (
                ("pnl_amount", "REAL NOT NULL DEFAULT 0"),
                ("pnl_currency", "TEXT NOT NULL DEFAULT 'INR'"),
                ("trades_count", "INTEGER NOT NULL DEFAULT 0"),
                ("legs_json", "TEXT NOT NULL DEFAULT '[]'"),
            ):
                if col_name not in tr_columns:
                    c.execute(f"ALTER TABLE trade_results ADD COLUMN {col_name} {col_def}")

            ver_row = c.execute("SELECT version FROM schema_version").fetchone()
            if not ver_row:
                c.execute("INSERT INTO schema_version VALUES(2)")
            elif ver_row[0] < 2:
                c.execute("UPDATE application_settings SET value='15', updated_at=? WHERE key='verification_initial_wait_minutes' AND value='60'", (utcnow(),))
                c.execute("UPDATE application_settings SET value='15', updated_at=? WHERE key='verification_repeat_minutes' AND value='30'", (utcnow(),))
                c.execute("UPDATE schema_version SET version=2")

            for name in DEFAULT_CATEGORIES:
                c.execute("INSERT OR IGNORE INTO categories(name) VALUES(?)", (name,))

            defaults = {
                "timezone": "Asia/Kolkata",
                "sync_interval_seconds": "30",
                "default_timeframe": "5m",
                "entry_tolerance": "0",
                "duplicate_window_minutes": "10",
                "verification_initial_wait_minutes": "15",
                "verification_repeat_minutes": "15",
                "verification_timeframe": "1m",
                "verification_expiry_hours": "168",
                "automatic_verification_enabled": "true",
                "verification_retry_minutes": "15",
                "market_provider": "provider_routing",
                "demo_mode": "false",
                "default_sl_rules": json.dumps(DEFAULT_SL_SEED),
            }
            for key, value in defaults.items():
                c.execute("INSERT OR IGNORE INTO application_settings VALUES(?,?,?)", (key, value, utcnow()))

            self.backfill_duplicate_classifications(c)
            self.log(c, "INFO", "system", "Application database initialized")

    def log(self, connection, severity, source, message):
        connection.execute(
            "INSERT INTO system_logs(created_at,severity,source,message) VALUES(?,?,?,?)",
            (utcnow(), severity, source, message[:1000])
        )

    def settings(self):
        with self.connection() as c:
            return {r["key"]: r["value"] for r in c.execute("SELECT key,value FROM application_settings")}

    def set_settings(self, values):
        with self.connection() as c:
            for k, v in values.items():
                c.execute(
                    "INSERT INTO application_settings(key,value,updated_at) VALUES(?,?,?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
                    (k, str(v), utcnow())
                )

    def get_default_sl_rules(self):
        raw = self.settings().get("default_sl_rules")
        rules = copy.deepcopy(DEFAULT_SL_SEED)
        if not raw:
            return rules
        try:
            saved = json.loads(raw)
        except json.JSONDecodeError:
            return rules
        if isinstance(saved, dict):
            for cat, block in saved.items():
                if cat not in rules:
                    rules[cat] = {"category_default": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 1, "usd_amount": 100, "symbols": {}}
                if isinstance(block, dict):
                    for k in ("category_default", "tp1", "tp2", "tp3", "lots", "lot_size", "usd_amount"):
                        if k in block:
                            rules[cat][k] = block[k]
                    if isinstance(block.get("symbols"), dict):
                        for sym, val in block["symbols"].items():
                            if isinstance(val, dict):
                                rules[cat]["symbols"].setdefault(sym, {})
                                if isinstance(rules[cat]["symbols"][sym], dict):
                                    rules[cat]["symbols"][sym].update(val)
                                else:
                                    rules[cat]["symbols"][sym] = val
                            else:
                                existing = rules[cat]["symbols"].get(sym)
                                if isinstance(existing, dict):
                                    existing["sl"] = val
                                else:
                                    rules[cat]["symbols"][sym] = {"sl": val, "tp1": None, "tp2": None, "tp3": None, "lots": None, "lot_size": None, "usd_amount": None}
        return rules

    def backfill_duplicate_classifications(self, c):
        """Non-destructively classify legacy rows that predate duplicate metadata."""
        window = float(c.execute("SELECT value FROM application_settings WHERE key='duplicate_window_minutes'").fetchone()[0])
        legacy = c.execute("SELECT id,received_at,notification_time,channel,message,duplicate_group_id FROM raw_notifications WHERE duplicate_group_id IS NULL ORDER BY id").fetchall()
        for row in legacy:
            current_time = notification_datetime({"Notification Time": row["notification_time"], "Received At": row["received_at"]})
            candidates = c.execute("SELECT id,received_at,notification_time,message,duplicate_group_id FROM raw_notifications WHERE id<>? AND channel=?", (row["id"], row["channel"] or "")).fetchall()
            exact = []
            related = []
            subject = message_subject(row["message"])
            for candidate in candidates:
                candidate_time = notification_datetime({"Notification Time": candidate["notification_time"], "Received At": candidate["received_at"]})
                if abs((current_time - candidate_time).total_seconds()) > window * 60:
                    continue
                if normalized_message(candidate["message"]) == normalized_message(row["message"]):
                    exact.append(candidate)
                elif DETAIL_MARKERS.search(row["message"] or "") and subject[0] and subject == message_subject(candidate["message"]):
                    related.append(candidate)
            if exact:
                primary = min(exact + [row], key=lambda x: notification_datetime({"Notification Time": x["notification_time"], "Received At": x["received_at"]}))
                group = primary["duplicate_group_id"] or f"dup-{primary['id']}-{uuid.uuid4().hex[:8]}"
                c.execute("UPDATE raw_notifications SET duplicate_group_id=?,is_duplicate=0,duplicate_of=NULL,duplicate_reason=NULL WHERE id=?", (group, primary["id"]))
                for item in exact + [row]:
                    if item["id"] != primary["id"]:
                        c.execute("UPDATE raw_notifications SET is_duplicate=1,duplicate_of=?,duplicate_group_id=?,duplicate_reason=? WHERE id=?", (primary["id"], group, "same normalized message from same channel within configured window", item["id"]))
            elif related:
                anchor = min(related, key=lambda x: notification_datetime({"Notification Time": x["notification_time"], "Received At": x["received_at"]}))
                group = anchor["duplicate_group_id"] or f"update-{anchor['id']}-{uuid.uuid4().hex[:8]}"
                c.execute("UPDATE raw_notifications SET duplicate_group_id=? WHERE id IN (?,?)", (group, anchor["id"], row["id"]))
                c.execute("UPDATE raw_notifications SET duplicate_reason=? WHERE id=?", ("signal_update: richer same-channel message with entries, targets, stop loss, or leverage", row["id"]))

    def ingest(self, row, parsed):
        values = [row.get(k, "") for k in SOURCE_COLUMNS]
        notification_id = row.get("Notification ID") or None
        raw_cat = (row.get("Category") or "").strip()
        channel = (row.get("Channel") or "").strip()
        sl_rules = self.get_default_sl_rules()

        if isinstance(parsed, list) and parsed:
            parsed_items = [dict(p) for p in parsed]
        else:
            parsed_items = parse_signals(row.get("Message", ""), raw_cat, sl_rules=sl_rules)

        for item in parsed_items:
            item.setdefault("entry_mode", "limit")
            apply_default_sl(item, raw_cat, sl_rules)
            apply_default_targets(item, raw_cat, sl_rules)

        with self.connection() as c:
            if notification_id and c.execute("SELECT id FROM raw_notifications WHERE notification_id=?", (notification_id,)).fetchone():
                return None, "duplicate"
            cursor = c.execute(
                "INSERT INTO raw_notifications(received_at,notification_time,source_app,package,channel,category,title,message,notification_id,delivery_id,imported_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                (*values, utcnow())
            )
            raw_id = cursor.lastrowid
            incoming_time = notification_datetime(row)
            window = float(c.execute("SELECT value FROM application_settings WHERE key='duplicate_window_minutes'").fetchone()[0])
            candidates = c.execute(
                "SELECT id,received_at,notification_time,message,is_duplicate,duplicate_of,duplicate_group_id "
                "FROM raw_notifications WHERE id<>? AND channel=? ORDER BY id",
                (raw_id, channel)
            ).fetchall()
            exact = []
            related = []
            incoming_subject = message_subject(row.get("Message"))
            for candidate in candidates:
                candidate_row = {"Notification Time": candidate["notification_time"], "Received At": candidate["received_at"]}
                if abs((incoming_time - notification_datetime(candidate_row)).total_seconds()) > window * 60:
                    continue
                if normalized_message(candidate["message"]) == normalized_message(row.get("Message")):
                    exact.append(candidate)
                elif DETAIL_MARKERS.search(row.get("Message") or "") and incoming_subject[0] and incoming_subject == message_subject(candidate["message"]):
                    related.append(candidate)
            if exact:
                primary = min(
                    exact + [{"id": raw_id, "notification_time": row.get("Notification Time"), "received_at": row.get("Received At"), "duplicate_group_id": None}],
                    key=lambda x: notification_datetime({"Notification Time": x["notification_time"], "Received At": x["received_at"]})
                )
                group = primary["duplicate_group_id"] or f"dup-{primary['id']}-{uuid.uuid4().hex[:8]}"
                c.execute("UPDATE raw_notifications SET duplicate_group_id=?,is_duplicate=0,duplicate_of=NULL,duplicate_reason=NULL WHERE id=?", (group, primary["id"]))
                for item in exact + [{"id": raw_id}]:
                    if item["id"] != primary["id"]:
                        c.execute("UPDATE raw_notifications SET is_duplicate=1,duplicate_of=?,duplicate_group_id=?,duplicate_reason=? WHERE id=?", (primary["id"], group, "same normalized message from same channel within configured window", item["id"]))
            elif related:
                anchor = min(related, key=lambda x: notification_datetime({"Notification Time": x["notification_time"], "Received At": x["received_at"]}))
                group = anchor["duplicate_group_id"] or f"update-{anchor['id']}-{uuid.uuid4().hex[:8]}"
                c.execute("UPDATE raw_notifications SET duplicate_group_id=? WHERE id IN (?,?)", (group, anchor["id"], raw_id))
                c.execute("UPDATE raw_notifications SET duplicate_reason=? WHERE id=?", ("signal_update: richer same-channel message with entries, targets, stop loss, or leverage", raw_id))

            c.execute("INSERT OR IGNORE INTO channels(name,category) VALUES(?,?)", (channel or "Unknown", raw_cat or "Uncategorized"))
            is_dup = bool(c.execute("SELECT is_duplicate FROM raw_notifications WHERE id=?", (raw_id,)).fetchone()["is_duplicate"])

            # Inherit active Forex symbol from same channel within 30 mins for short follow-ups (e.g. 'Buy now 3765')
            if not is_dup and raw_cat.lower() in ("forex", "fx"):
                ctx_cutoff = (incoming_time - timedelta(minutes=30)).isoformat()
                recent_fx = c.execute(
                    "SELECT s.symbol FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id "
                    "WHERE r.channel=? AND LOWER(r.category) IN ('forex','fx') AND r.is_duplicate=0 "
                    "AND s.symbol IS NOT NULL AND s.symbol<>'' AND COALESCE(r.notification_time,r.received_at)>=? "
                    "ORDER BY s.id DESC LIMIT 1",
                    (channel, ctx_cutoff),
                ).fetchone()
                if recent_fx:
                    for item in parsed_items:
                        if not item.get("symbol") and item.get("direction") and (item.get("entry_price") is not None or item.get("entry_mode") == "market_now"):
                            item["symbol"] = recent_fx["symbol"]
                            item["status"] = "parsed"
                            item["review"] = "not_required"
                            item["confidence"] = 0.85
                            item["explanation"] = (item.get("explanation") or "").rstrip(".") + f" | Inherited symbol {recent_fx['symbol']} from recent channel message."
                            apply_default_sl(item, raw_cat, sl_rules)
                            apply_default_targets(item, raw_cat, sl_rules)

            settings = {x["key"]: x["value"] for x in c.execute("SELECT key,value FROM application_settings")}
            start = incoming_time
            initial = int(settings.get("verification_initial_wait_minutes", 15))
            interval = int(settings.get("verification_repeat_minutes", 15))
            expiry = int(settings.get("verification_expiry_hours", 168))
            first = start + timedelta(minutes=initial)
            expires = start + timedelta(hours=expiry)
            live_after = datetime.now(timezone.utc) - timedelta(minutes=initial)

            for item in parsed_items:
                signal_id = c.execute(
                    "INSERT INTO signals(raw_notification_id,symbol,direction,entry_price,additional_entries,stop_loss,targets,option_type,strike_price,expiry_date,entry_mode,parsing_status,parsing_confidence,manual_review_status,parsing_explanation,updated_at) "
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        raw_id, item.get("symbol"), item.get("direction"), item.get("entry_price"),
                        json.dumps(item.get("additional_entries", [])), item.get("stop_loss"),
                        json.dumps(item.get("targets", [])), item.get("option_type"),
                        item.get("strike_price"), item.get("expiry_date"), item.get("entry_mode") or "limit",
                        item["status"], item["confidence"], item["review"], item["explanation"], utcnow()
                    )
                ).lastrowid

                can_verify = (
                    item["status"] == "parsed"
                    and not is_dup
                    and item.get("symbol")
                    and item.get("direction") in ("BUY", "SELL")
                    and (item.get("entry_price") is not None or item.get("entry_mode") == "market_now")
                )
                if can_verify:
                    status = "scheduled" if start >= live_after else "manual_required"
                    next_time = first.isoformat() if status == "scheduled" else None
                    last_res = f"Older than {initial} mins: manual processing required" if status == "manual_required" else None
                    c.execute(
                        "INSERT INTO verification_schedules(signal_id,signal_start_at,initial_wait_minutes,interval_minutes,timeframe,first_scheduled_at,next_scheduled_at,expires_at,status,last_result,created_at,updated_at) "
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        (signal_id, start.isoformat(), initial, interval, settings["verification_timeframe"], first.isoformat(), next_time, expires.isoformat(), status, last_res, utcnow(), utcnow())
                    )

            self.log(c, "INFO", "ingest", f"Imported notification {raw_id} ({len(parsed_items)} signal(s))")
            return raw_id, "imported"