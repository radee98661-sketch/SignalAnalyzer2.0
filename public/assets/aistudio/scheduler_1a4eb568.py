"""Persistent, dependency-free verification scheduler for Flask/Termux."""
import difflib, json, re, threading, time
from datetime import datetime, timezone, timedelta
from urllib.request import Request, urlopen
from .database import utcnow
from .parser import resolve_instrument_profile, normalize_sl_category, apply_default_sl, apply_default_targets
from .verification import verify_detailed
from .providers import BinanceFutures, TwelveData, AngelOneProvider, ProviderError

TERMINAL = {"stop_loss_hit", "ambiguous"}

INDEX_BFO = {"SENSEX", "BANKEX"}
INDEX_NFO = {"NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50"}
MCX_SYMBOLS = {
    "CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGASMINI",
    "GOLD", "GOLDM", "GOLDPETAL", "GOLDGUINEA",
    "SILVER", "SILVERM", "SILVERMIC",
    "COPPER", "ZINC", "ALUMINIUM", "LEAD", "NICKEL",
}

SYMBOL_ALIASES = {
    "NAVINFLOUR": "NAVINFLUOR",
    "BAJAJFIN": "BAJAJFINSV",
    "TATAMOTOR": "TATAMOTORS",
    "HINDUNILV": "HINDUNILVR",
    "HUL": "HINDUNILVR",
    "INDUSIND": "INDUSINDBK",
    "MM": "M&M",
    "M_M": "M&M",
    "RIL": "RELIANCE",
    "AIRTEL": "BHARTIARTL",
    "SUN": "SUNPHARMA",
    "ADANI": "ADANIENT",
    "L&T": "LT",
}

_ANGEL_SESSION_CACHE = {"jwt_token": None, "client_id": None, "expires_at": 0.0}
_LAST_MASTER_ERROR = None
_LAST_SEARCH_SCRIP_SAMPLE = []


def _get_angel_jwt(secrets, force_refresh=False):
    """Reuse Angel One JWT session for 4 hours to avoid loginByPassword rate limits & TOTP collisions."""
    now = time.time()
    client_id = secrets.get("client_id")
    if (
        not force_refresh
        and _ANGEL_SESSION_CACHE["jwt_token"]
        and _ANGEL_SESSION_CACHE["client_id"] == client_id
        and now < _ANGEL_SESSION_CACHE["expires_at"]
    ):
        return _ANGEL_SESSION_CACHE["jwt_token"]

    session = AngelOneProvider().authenticate(secrets)
    _ANGEL_SESSION_CACHE["jwt_token"] = session["jwt_token"]
    _ANGEL_SESSION_CACHE["client_id"] = client_id
    _ANGEL_SESSION_CACHE["expires_at"] = now + (4 * 3600)
    return session["jwt_token"]


def _dt(value):
    value = (value or "").replace("Z", "+00:00")
    parsed = datetime.fromisoformat(value)
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)


def _store_candles(c, candles):
    for x in candles:
        c.execute(
            "INSERT OR IGNORE INTO market_candles(provider,symbol,exchange,interval,candle_timestamp,timeframe,candle_time,open,high,low,close,volume,retrieved_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                x["provider"], x["symbol"], x.get("exchange", ""), x["interval"],
                x["candle_timestamp"], x["interval"], x["candle_timestamp"],
                x["open"], x["high"], x["low"], x["close"], x.get("volume"), utcnow()
            )
        )


def _parse_master_expiry(exp):
    if not exp:
        return None
    clean = exp.strip().upper()
    for fmt in ("%d%b%Y", "%d%b%y", "%Y-%m-%d", "%d-%b-%Y"):
        try:
            return datetime.strptime(clean, fmt).date()
        except ValueError:
            continue
    return None


def _row_underlying(row):
    try:
        raw = json.loads(row["raw_json"] or "{}")
    except (json.JSONDecodeError, KeyError, TypeError):
        raw = {}
    name = (raw.get("name") or "").strip().upper()
    if name:
        return name
    tsym = (row["trading_symbol"] or row["symbol"] or "").strip().upper()
    m = re.match(r"^([A-Z&\-]+?)(?:\d|$)", tsym)
    return m.group(1) if m else tsym


def _maybe_refresh_angel_master(c):
    """Auto-refresh OpenAPIScripMaster at most once every 30 minutes when a strike/symbol is missing."""
    global _LAST_MASTER_ERROR
    row = c.execute("SELECT value FROM application_settings WHERE key='angel_master_auto_refreshed_at'").fetchone()
    if row and row[0]:
        try:
            if datetime.now(timezone.utc) - _dt(row[0]) < timedelta(minutes=30):
                return False
        except Exception:
            pass

    url = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json"
    try:
        with urlopen(Request(url, headers={"User-Agent": "SignalAnalyzer/1.0"}), timeout=90) as r:
            source = json.load(r)
        if not isinstance(source, list):
            _LAST_MASTER_ERROR = "Scrip Master response was not a JSON list."
            return False
        now_iso = utcnow()
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
                (
                    item.get("symbol"), item.get("symbol") or "", token,
                    item.get("exch_seg") or "", item.get("instrumenttype"),
                    item.get("expiry"), float(item.get("strike") or 0),
                    float(item.get("lotsize") or 0), float(item.get("tick_size") or 0),
                    json.dumps(item), now_iso
                )
            )
        c.execute(
            "INSERT INTO application_settings(key,value,updated_at) VALUES('angel_master_auto_refreshed_at',?,?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
            (now_iso, now_iso)
        )
        _LAST_MASTER_ERROR = None
        return True
    except Exception as e:
        _LAST_MASTER_ERROR = str(e)
        return False


def _extract_symbol_candidates(c, symbol, exchanges, inst_types):
    """Find canonical underlying names in instruments matching exact, alias, prefix/substring, or fuzzy match."""
    clean = SYMBOL_ALIASES.get(symbol, symbol)
    ex_ph = ",".join("?" for _ in exchanges)
    it_ph = ",".join("?" for _ in inst_types)
    rows_u = c.execute(
        f"SELECT DISTINCT symbol, trading_symbol, raw_json FROM instruments "
        f"WHERE UPPER(exchange_segment) IN ({ex_ph}) AND UPPER(instrument_type) IN ({it_ph})",
        [e.upper() for e in exchanges] + [t.upper() for t in inst_types],
    ).fetchall()
    underlyings = {_row_underlying(r) for r in rows_u}
    underlyings.discard("")

    if clean in underlyings:
        return [clean]

    matches = []
    prefix = clean[:6] if len(clean) >= 6 else clean
    for u in sorted(underlyings):
        if u.startswith(prefix) or clean in u or u in clean:
            matches.append(u)

    close = difflib.get_close_matches(clean, sorted(underlyings), n=3, cutoff=0.72)
    for u in close:
        if u not in matches:
            matches.append(u)

    return matches if matches else [clean]


def _query_angel_candidates(c, target_symbol, exchanges, inst_types, is_option, strike, opt_type):
    ex_ph = ",".join("?" for _ in exchanges)
    it_ph = ",".join("?" for _ in inst_types)
    base_params = [e.upper() for e in exchanges] + [t.upper() for t in inst_types]

    if is_option:
        raw_strike = float(strike)
        scaled_strike = raw_strike * 100.0
        sql = (
            f"SELECT symbol, trading_symbol, instrument_token, exchange_segment, expiry, strike, raw_json "
            f"FROM instruments WHERE UPPER(exchange_segment) IN ({ex_ph}) AND UPPER(instrument_type) IN ({it_ph}) "
            f"AND (ABS(strike - ?) < 1.0 OR ABS(strike - ?) < 0.05) AND UPPER(trading_symbol) LIKE ?"
        )
        params = base_params + [scaled_strike, raw_strike, f"%{opt_type}"]
    else:
        sql = (
            f"SELECT symbol, trading_symbol, instrument_token, exchange_segment, expiry, strike, raw_json "
            f"FROM instruments WHERE UPPER(exchange_segment) IN ({ex_ph}) AND UPPER(instrument_type) IN ({it_ph}) "
            f"AND UPPER(trading_symbol) LIKE ?"
        )
        params = base_params + [f"%{target_symbol[:5]}%"]

    matched = []
    prefix = target_symbol[:6] if len(target_symbol) >= 6 else target_symbol
    for r in c.execute(sql, params).fetchall():
        u = _row_underlying(r)
        tsym = re.sub(r"[\s\-_]+", "", (r["trading_symbol"] or r["symbol"] or "").upper())
        if u == target_symbol or u.startswith(prefix) or tsym.startswith(prefix):
            matched.append(r)
    return matched


def _live_search_angel_contract(c, item, exchanges, inst_types, target_symbol, is_option, strike, opt_type, ref_date):
    """Query Angel One's live searchScrip API when a contract isn't in the local instruments table."""
    global _LAST_SEARCH_SCRIP_SAMPLE
    _LAST_SEARCH_SCRIP_SAMPLE = []

    integration = c.execute("SELECT secrets_json FROM integrations WHERE provider='angel_one'").fetchone()
    if not integration:
        return None
    secrets = json.loads(integration["secrets_json"] or "{}")
    if any(not secrets.get(k) for k in ("api_key", "client_id", "password", "totp_secret")):
        return None

    strike_num = float(strike) if strike is not None else 0.0
    strike_clean = str(int(strike_num)) if strike_num.is_integer() else str(strike_num)
    queries = []
    if is_option:
        queries.append(f"{target_symbol} {strike_clean} {opt_type}")
    queries.append(target_symbol)
    if len(target_symbol) > 6:
        queries.append(target_symbol[:6])

    jwt_token = _get_angel_jwt(secrets)
    provider_api = AngelOneProvider()
    raw_hits = []

    for exch in exchanges:
        for q in queries:
            try:
                hits = provider_api.search_scrip(jwt_token, secrets["api_key"], exch, q)
            except ProviderError as e:
                if "Invalid Token" in str(e) or "401" in str(e) or "AG8001" in str(e):
                    jwt_token = _get_angel_jwt(secrets, force_refresh=True)
                    hits = provider_api.search_scrip(jwt_token, secrets["api_key"], exch, q)
                else:
                    hits = []
            if hits:
                raw_hits.extend(hits)
                break

    if not raw_hits:
        return None

    _LAST_SEARCH_SCRIP_SAMPLE = [
        f"{h.get('exchange', '')}:{h.get('tradingsymbol', '')}"
        for h in raw_hits[:5] if h.get("tradingsymbol")
    ]

    hint_expiry = re.sub(r"[^A-Z0-9]", "", (item.get("expiry_date") or "").upper())
    prefix = target_symbol[:6] if len(target_symbol) >= 6 else target_symbol
    scored = []

    for h in raw_hits:
        exch = (h.get("exchange") or exchanges[0]).strip().upper()
        raw_tsym = (h.get("tradingsymbol") or "").strip().upper()
        tsym = re.sub(r"[\s\-_]+", "", raw_tsym)
        tok = str(h.get("symboltoken") or "").strip()
        if not tsym or not tok:
            continue

        if is_option:
            m = re.match(
                rf"^({re.escape(prefix)}[A-Z&]*?)(\d{{1,2}}[A-Z]{{3}}(?:\d{{2,4}})?)(\d+(?:\.\d+)?){re.escape(opt_type)}$",
                tsym,
            )
            if not m:
                continue
            exp_token, found_strike = m.group(2), float(m.group(3))
            if abs(found_strike - strike_num) > 0.05:
                continue
        else:
            m = re.match(
                rf"^({re.escape(prefix)}[A-Z&]*?)(\d{{1,2}}[A-Z]{{3}}(?:\d{{2,4}})?)FUT$",
                tsym,
            )
            if not m:
                continue
            exp_token = m.group(2)

        exp_date = _parse_master_expiry(exp_token)
        if exp_date is None:
            try:
                dt_no_yr = datetime.strptime(exp_token, "%d%b")
                exp_date = dt_no_yr.replace(year=ref_date.year).date()
                if exp_date < ref_date - timedelta(days=180):
                    exp_date = exp_date.replace(year=ref_date.year + 1)
            except ValueError:
                exp_date = ref_date

        hint_match = bool(hint_expiry and hint_expiry in exp_token)
        is_active = exp_date >= ref_date
        scored.append((hint_match, is_active, exp_date, exch, tok, raw_tsym, exp_token))

    if not scored:
        return None

    active = [x for x in scored if x[1]]
    if active:
        hinted_active = [x for x in active if x[0]]
        pool = hinted_active if hinted_active else active
        pick = min(pool, key=lambda x: x[2])
    else:
        pick = max(scored, key=lambda x: x[2])

    _, _, exp_date, exch, tok, raw_tsym, _ = pick
    c.execute(
        "INSERT INTO instruments(symbol,trading_symbol,instrument_token,exchange_segment,instrument_type,expiry,strike,lot_size,tick_size,raw_json,updated_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(instrument_token) DO UPDATE SET "
        "trading_symbol=excluded.trading_symbol,exchange_segment=excluded.exchange_segment,updated_at=excluded.updated_at",
        (
            raw_tsym, raw_tsym, tok, exch, inst_types[0],
            exp_date.strftime("%d%b%Y").upper(),
            (strike_num * 100.0) if is_option else 0.0,
            0.0, 0.0,
            json.dumps({"name": target_symbol, "symbol": raw_tsym, "token": tok, "exch_seg": exch}),
            utcnow(),
        ),
    )
    return f"{exch}:{tok}"


def _describe_missing_contract(c, item, category, symbol):
    strike = item.get("strike_price")
    opt_type = (item.get("option_type") or "").strip().upper()
    is_option = bool(strike is not None and float(strike) > 0 and opt_type in ("CE", "PE"))

    if symbol in INDEX_BFO:
        exchanges, inst_types = ("BFO",), (("OPTIDX",) if is_option else ("FUTIDX",))
    elif category == "mcx" or symbol in MCX_SYMBOLS:
        exchanges, inst_types = ("MCX",), (("OPTFUT",) if is_option else ("FUTCOM",))
    elif symbol in INDEX_NFO:
        exchanges, inst_types = ("NFO",), (("OPTIDX",) if is_option else ("FUTIDX",))
    else:
        exchanges, inst_types = ("NFO",), (("OPTSTK",) if is_option else ("FUTSTK",))

    target = _extract_symbol_candidates(c, symbol, exchanges, inst_types)[0]
    ex_ph = ",".join("?" for _ in exchanges)
    it_ph = ",".join("?" for _ in inst_types)
    prefix = target[:5] if len(target) >= 5 else target
    rows_all = [
        r for r in c.execute(
            f"SELECT symbol, trading_symbol, strike, raw_json FROM instruments "
            f"WHERE UPPER(exchange_segment) IN ({ex_ph}) AND UPPER(instrument_type) IN ({it_ph}) AND UPPER(trading_symbol) LIKE ?",
            [e.upper() for e in exchanges] + [t.upper() for t in inst_types] + [f"%{prefix}%"],
        ).fetchall()
        if _row_underlying(r) == target or re.match(rf"^{re.escape(prefix)}", (r["trading_symbol"] or "").upper())
    ]
    if not rows_all:
        cross_hits = [
            f"{r['exchange_segment']}:{r['trading_symbol']}"
            for r in c.execute(
                "SELECT exchange_segment, trading_symbol FROM instruments WHERE UPPER(trading_symbol) LIKE ? LIMIT 5",
                (f"%{prefix}%",),
            ).fetchall()
        ]
        notes = []
        if cross_hits:
            notes.append(f"Other segments have: {', '.join(cross_hits)}")
        else:
            notes.append(f"No '{prefix}*' contracts exist in Angel One's master (likely not in F&O)")
        if _LAST_SEARCH_SCRIP_SAMPLE:
            notes.append(f"searchScrip returned: {', '.join(_LAST_SEARCH_SCRIP_SAMPLE)}")
        if _LAST_MASTER_ERROR:
            notes.append(f"Master refresh error: {_LAST_MASTER_ERROR}")
        return f"Contract '{target} {int(strike) if strike and float(strike).is_integer() else strike} {opt_type}' unavailable in {exchanges[0]} ({inst_types[0]}). {'; '.join(notes)}."

    if is_option:
        strikes = sorted({
            round(float(r["strike"]) / 100.0, 2) if float(r["strike"]) >= 10000 else round(float(r["strike"]), 2)
            for r in rows_all if float(r["strike"] or 0) > 0
        })
        if strikes:
            nearest = sorted(strikes, key=lambda x: abs(x - float(strike)))[:6]
            return f"Strike {strike:g} {opt_type} not listed for {target}. Nearest strikes in master: {', '.join(str(int(x) if x.is_integer() else x) for x in sorted(nearest))}."
    return f"No matching {target} contract found in Angel One {exchanges[0]}."


def _auto_resolve_angel_instrument(c, item, category, symbol):
    """Find the exact Angel One exchange_segment:instrument_token from local instruments or live searchScrip."""
    if not symbol:
        return None

    strike = item.get("strike_price")
    opt_type = (item.get("option_type") or "").strip().upper()
    is_option = bool(strike is not None and float(strike) > 0 and opt_type in ("CE", "PE"))

    if symbol in INDEX_BFO:
        exchanges, inst_types = ("BFO",), (("OPTIDX",) if is_option else ("FUTIDX",))
    elif category == "mcx" or symbol in MCX_SYMBOLS:
        exchanges, inst_types = ("MCX",), (("OPTFUT",) if is_option else ("FUTCOM",))
    elif symbol in INDEX_NFO:
        exchanges, inst_types = ("NFO",), (("OPTIDX",) if is_option else ("FUTIDX",))
    else:
        exchanges, inst_types = ("NFO",), (("OPTSTK",) if is_option else ("FUTSTK",))

    ist = timezone(timedelta(hours=5, minutes=30))
    raw_time = item.get("notification_time") or item.get("received_at")
    try:
        ref_date = _dt(raw_time).astimezone(ist).date() if raw_time else datetime.now(ist).date()
    except Exception:
        ref_date = datetime.now(ist).date()

    target_symbols = _extract_symbol_candidates(c, symbol, exchanges, inst_types)
    candidates = []
    for ts in target_symbols:
        candidates = _query_angel_candidates(c, ts, exchanges, inst_types, is_option, strike, opt_type)
        if candidates:
            break

    # Fallback 1: Live Angel One searchScrip API
    if not candidates:
        try:
            live_resolved = _live_search_angel_contract(
                c, item, exchanges, inst_types, target_symbols[0], is_option, strike, opt_type, ref_date
            )
            if live_resolved:
                return live_resolved
        except Exception:
            pass

    # Fallback 2: Full OpenAPIScripMaster refresh if needed
    if not candidates and _maybe_refresh_angel_master(c):
        target_symbols = _extract_symbol_candidates(c, symbol, exchanges, inst_types)
        for ts in target_symbols:
            candidates = _query_angel_candidates(c, ts, exchanges, inst_types, is_option, strike, opt_type)
            if candidates:
                break

    if not candidates:
        return None

    hint_expiry = re.sub(r"[^A-Z0-9]", "", (item.get("expiry_date") or "").upper())

    scored = []
    for r in candidates:
        exp_str = (r["expiry"] or "").strip().upper()
        exp_date = _parse_master_expiry(exp_str)
        if exp_date is None:
            continue
        hint_match = bool(hint_expiry and hint_expiry in exp_str)
        is_active = exp_date >= ref_date
        scored.append((hint_match, is_active, exp_date, r))

    if not scored:
        pick = candidates[0]
        return f"{pick['exchange_segment']}:{pick['instrument_token']}"

    active = [x for x in scored if x[1]]
    if active:
        hinted_active = [x for x in active if x[0]]
        pool = hinted_active if hinted_active else active
        best = min(pool, key=lambda x: x[2])[3]
    else:
        best = max(scored, key=lambda x: x[2])[3]

    return f"{best['exchange_segment']}:{best['instrument_token']}"


def resolve_signal_provider(c, item):
    category = (item.get("category") or "").strip().lower()
    symbol = (item.get("symbol") or "").strip().upper()
    if category == "crypto" or symbol.endswith(("USDT", "USDC", "BUSD")):
        return "binance", symbol
    if category == "forex" or symbol in {"XAUUSD", "XAGUSD", "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD", "USOIL", "UKOIL"}:
        return "twelve_data", symbol

    # 1) Check explicit manual mappings in instrument_mappings first
    lookup_candidates = []
    if item.get("strike_price") and item.get("option_type"):
        strike_str = str(int(item["strike_price"])) if float(item["strike_price"]).is_integer() else str(item["strike_price"])
        if item.get("expiry_date"):
            lookup_candidates.append(f"{symbol}|{item['expiry_date']}|{strike_str}|{item['option_type']}")
        lookup_candidates.append(f"{symbol}|{strike_str}|{item['option_type']}")
    lookup_candidates.append(symbol)
    for key in lookup_candidates:
        row = c.execute("SELECT instrument_token, exchange_segment FROM instrument_mappings WHERE lookup_key=?", (key,)).fetchone()
        if row:
            return "angel_one", f"{row['exchange_segment']}:{row['instrument_token']}"

    # 2) Auto-resolve nearest active contract from local instruments or live searchScrip
    auto_resolved = _auto_resolve_angel_instrument(c, item, category, symbol)
    if auto_resolved:
        return "angel_one", auto_resolved

    return "angel_one", symbol


def _fetch_provider_range(c, provider, resolved_sym, cache_sym, fetch_start, fetch_end, item=None):
    """Fetch a single [fetch_start, fetch_end] 1m candle slice from the routed provider and store it."""
    if fetch_end <= fetch_start + timedelta(seconds=30):
        return 0

    integration = c.execute("SELECT config_json,secrets_json FROM integrations WHERE provider=?", (provider,)).fetchone()
    if provider == "binance":
        config = json.loads(integration["config_json"]) if integration else {}
        candles = BinanceFutures().fetch(cache_sym, "1m", fetch_start, fetch_end, config)
    elif provider == "twelve_data":
        if not integration:
            raise ProviderError("Twelve Data is not configured.")
        config = json.loads(integration["config_json"])
        secrets = json.loads(integration["secrets_json"])
        api_key = secrets.get("api_key", "")
        if not api_key:
            raise ProviderError("Twelve Data API key is not configured.")
        td_symbol = (
            f"{cache_sym[:3]}/{cache_sym[3:]}"
            if re.fullmatch(r"[A-Z]{6}", cache_sym)
            else cache_sym
        )
        candles, headers = TwelveData().fetch(td_symbol, "1min", fetch_start, fetch_end, config, api_key)
        for candle in candles:
            candle["symbol"] = cache_sym
        credits = float(headers.get("api-credits-used", "1") or 1)
        c.execute(
            "INSERT INTO api_request_log(provider,endpoint,requested_at,completed_at,status,credits_used,candles_returned) VALUES(?,?,?,?,?,?,?)",
            ("twelve_data", "/time_series", utcnow(), utcnow(), "success", credits, len(candles))
        )
    else:
        if ":" not in resolved_sym:
            if item:
                cat = (item.get("category") or "").strip().lower()
                raise ProviderError(_describe_missing_contract(c, item, cat, resolved_sym))
            raise ProviderError(f"No matching contract found in Angel One for {resolved_sym}.")
        if not integration:
            raise ProviderError("Angel One is not configured.")
        secrets = json.loads(integration["secrets_json"])
        exchange, token = resolved_sym.split(":", 1)
        ist = timezone(timedelta(hours=5, minutes=30))
        jwt_token = _get_angel_jwt(secrets)
        try:
            candles = AngelOneProvider().fetch(
                jwt_token, secrets["api_key"], exchange, token,
                "ONE_MINUTE", fetch_start.astimezone(ist), fetch_end.astimezone(ist)
            )
        except ProviderError as e:
            if "Invalid Token" in str(e) or "401" in str(e) or "AG8001" in str(e):
                jwt_token = _get_angel_jwt(secrets, force_refresh=True)
                candles = AngelOneProvider().fetch(
                    jwt_token, secrets["api_key"], exchange, token,
                    "ONE_MINUTE", fetch_start.astimezone(ist), fetch_end.astimezone(ist)
                )
            else:
                raise

    _store_candles(c, candles)
    return len(candles)


def fetch_candles_for_signal(c, item, start, end):
    """Fetch missing 1m candles across both MIN (left-backfill) and MAX (right-forward) cache boundaries."""
    provider, resolved_sym = resolve_signal_provider(c, item)
    if not resolved_sym:
        raise ProviderError("Signal has no resolved symbol.")

    desired_start = start.replace(second=0, microsecond=0)
    if end <= desired_start + timedelta(seconds=30):
        return 0

    cache_sym = resolved_sym.split(":", 1)[1] if ":" in resolved_sym else resolved_sym
    bounds_row = c.execute(
        "SELECT MIN(candle_timestamp), MAX(candle_timestamp) FROM market_candles WHERE provider=? AND symbol=?",
        (provider, cache_sym)
    ).fetchone()

    min_ts = bounds_row[0] if bounds_row else None
    max_ts = bounds_row[1] if bounds_row else None

    if not min_ts or not max_ts:
        return _fetch_provider_range(c, provider, resolved_sym, cache_sym, desired_start, end, item=item)

    min_dt = _dt(min_ts)
    max_dt = _dt(max_ts)
    fetched_total = 0

    if desired_start < min_dt:
        fetched_total += _fetch_provider_range(c, provider, resolved_sym, cache_sym, desired_start, min_dt, item=item)

    if end > max_dt + timedelta(seconds=30):
        forward_start = max(desired_start, max_dt)
        fetched_total += _fetch_provider_range(c, provider, resolved_sym, cache_sym, forward_start, end, item=item)

    return fetched_total


def process_signal(db, signal_id, run_type="manual", schedule_id=None, return_meta=False):
    """Fetch any new candles, run the multi-entry OHLC engine, and calculate money P&L."""
    rules = db.get_default_sl_rules()

    with db.connection() as c:
        record = c.execute(
            "SELECT s.*,r.notification_time,r.received_at,r.category,r.is_duplicate "
            "FROM signals s JOIN raw_notifications r ON r.id=s.raw_notification_id WHERE s.id=?",
            (signal_id,)
        ).fetchone()
        if not record:
            out = {"status": "missing", "detail": "Signal not found.", "candle_time": None}
            return (out, {"all_targets_hit": False}) if return_meta else out
        if record["is_duplicate"]:
            out = {"status": "duplicate", "detail": "Duplicates are not verified.", "candle_time": None}
            return (out, {"all_targets_hit": False}) if return_meta else out

        run_id = c.execute(
            "INSERT INTO verification_runs(signal_id,schedule_id,run_type,started_at,status) VALUES(?,?,?,?,?)",
            (signal_id, schedule_id, run_type, utcnow(), "running")
        ).lastrowid

        item = dict(record)
        item["targets"] = json.loads(item.get("targets") or "[]")
        item["additional_entries"] = json.loads(item.get("additional_entries") or "[]")
        entry_mode = (item.get("entry_mode") or "limit").strip().lower()
        item["entry_mode"] = entry_mode

        provider, resolved_sym = resolve_signal_provider(c, item)
        cache_sym = resolved_sym.split(":", 1)[1] if ":" in resolved_sym else resolved_sym
        raw_sig_time = item.get("notification_time") or item.get("received_at")
        sig_start = _dt(raw_sig_time).replace(second=0, microsecond=0)

        resolved_label = resolved_sym
        inst_lot_from_master = 0.0
        if provider == "angel_one" and ":" in resolved_sym:
            exch_part, tok_part = resolved_sym.split(":", 1)
            inst_row = c.execute(
                "SELECT trading_symbol, lot_size FROM instruments WHERE instrument_token=?",
                (tok_part,),
            ).fetchone()
            if inst_row:
                if inst_row["trading_symbol"]:
                    resolved_label = f"{exch_part}:{inst_row['trading_symbol']} (token {tok_part})"
                inst_lot_from_master = float(inst_row["lot_size"] or 0)

        fetch_error = None
        if item.get("symbol") and item.get("direction") and (item.get("entry_price") is not None or entry_mode == "market_now"):
            try:
                fetch_candles_for_signal(c, item, sig_start, datetime.now(timezone.utc))
            except ProviderError as e:
                fetch_error = str(e)
            except Exception as e:
                fetch_error = str(e)

        candles = [
            dict(x) for x in c.execute(
                "SELECT * FROM market_candles WHERE provider=? AND symbol=? AND candle_timestamp>=? ORDER BY candle_timestamp",
                (provider, cache_sym, sig_start.isoformat())
            )
        ]

        # For 'BUY NOW' / 'ENTRY - NOW' signals, lock in the signal-time candle's open price as entry_price
        if entry_mode == "market_now" and item.get("entry_price") is None and candles:
            first_c = candles[0]
            open_px = first_c.get("open") if first_c.get("open") is not None else first_c.get("close")
            if open_px is not None:
                market_entry = float(open_px)
                item["entry_price"] = market_entry
                item["explanation"] = item.get("parsing_explanation") or ""
                note = f"Market entry @ {market_entry:g} ({first_c.get('candle_timestamp') or sig_start.isoformat()})"
                if note not in item["explanation"]:
                    item["explanation"] = (item["explanation"].rstrip(".") + f" | {note}.").strip()
                apply_default_sl(item, item.get("category") or "", rules)
                apply_default_targets(item, item.get("category") or "", rules)
                item["parsing_explanation"] = item["explanation"]
                c.execute(
                    "UPDATE signals SET entry_price=?, stop_loss=?, targets=?, parsing_explanation=?, updated_at=? WHERE id=?",
                    (
                        item["entry_price"],
                        item.get("stop_loss"),
                        json.dumps(item.get("targets") or []),
                        item["parsing_explanation"],
                        utcnow(),
                        signal_id,
                    ),
                )

        tolerance = float(c.execute("SELECT value FROM application_settings WHERE key='entry_tolerance'").fetchone()[0])
        status, detail, candle_time, timeline = verify_detailed(item, candles, tolerance)

        # Resolve lot/dollar sizing profile & compute realized money P&L per triggered leg
        cat_key = normalize_sl_category(item.get("category"), item.get("symbol"), item.get("option_type"))
        prof = resolve_instrument_profile(rules, item.get("category"), item.get("symbol"), item.get("option_type"))

        sym_upper = (item.get("symbol") or "").strip().upper()
        sym_lookup_key = f"{sym_upper}:{'OPT' if item.get('option_type') else 'FUT'}" if cat_key == "MCX" else sym_upper
        sym_override = (rules.get(cat_key, {}).get("symbols") or {}).get(sym_lookup_key)
        has_explicit_sym_lot = isinstance(sym_override, dict) and sym_override.get("lot_size") not in (None, "")

        effective_lot_size = prof["lot_size"]
        if not has_explicit_sym_lot and inst_lot_from_master > 0:
            effective_lot_size = inst_lot_from_master
        effective_lot_size = float(effective_lot_size or 1.0)

        legs = timeline.get("legs") or []
        entered_legs = [lg for lg in legs if lg.get("entered")]
        pnl_currency = "USD" if cat_key in ("FOREX", "CRYPTO") else "INR"
        total_pnl = 0.0

        for lg in entered_legs:
            pts = float(lg.get("points_pnl") or 0.0)
            ep = float(lg.get("entry_price") or item.get("entry_price") or 0.0)
            if cat_key == "CRYPTO" and ep > 0:
                usd_qty_capital = float(prof["usd_amount"])
                qty = usd_qty_capital / ep
                leg_pnl = round(pts * qty, 2)
                lg["position_label"] = f"${usd_qty_capital:g} ({qty:.4g} qty)"
            else:
                lots_count = float(prof["lots"])
                qty = lots_count * effective_lot_size
                leg_pnl = round(pts * qty, 2)
                lg["position_label"] = f"{lots_count:g} lot × {effective_lot_size:g}"
            lg["money_pnl"] = leg_pnl
            lg["currency"] = pnl_currency
            total_pnl = round(total_pnl + leg_pnl, 2)

        initial_wait = int(c.execute("SELECT value FROM application_settings WHERE key='verification_initial_wait_minutes'").fetchone()[0])
        if status == "awaiting_market_data":
            if fetch_error:
                detail = f"Awaiting market data ({fetch_error})"
            if run_type == "manual" and sig_start < datetime.now(timezone.utc) - timedelta(minutes=initial_wait):
                status = "historical_data_missing"
                detail = (
                    f"Historical provider fetch could not supply candles: {fetch_error}"
                    if fetch_error else f"Provider returned 0 candles for {resolved_label}."
                )

        c.execute("UPDATE signals SET verification_status=?,final_result=?,updated_at=? WHERE id=?", (status, status, utcnow(), signal_id))
        c.execute("INSERT INTO signal_analysis(signal_id,analyzed_at,decision,detail,candle_time) VALUES(?,?,?,?,?)", (signal_id, utcnow(), status, detail, candle_time))
        c.execute(
            "INSERT INTO trade_results(signal_id,entry_hit_at,target_hit_at,stop_loss_hit_at,events_json,result,detail,pnl_amount,pnl_currency,trades_count,legs_json,updated_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(signal_id) DO UPDATE SET "
            "entry_hit_at=excluded.entry_hit_at,target_hit_at=excluded.target_hit_at,"
            "stop_loss_hit_at=excluded.stop_loss_hit_at,events_json=excluded.events_json,"
            "result=excluded.result,detail=excluded.detail,pnl_amount=excluded.pnl_amount,"
            "pnl_currency=excluded.pnl_currency,trades_count=excluded.trades_count,legs_json=excluded.legs_json,updated_at=excluded.updated_at",
            (
                signal_id,
                timeline.get("entry_hit_at"),
                timeline.get("first_target_hit_at"),
                timeline.get("stop_loss_hit_at"),
                json.dumps(timeline.get("events", [])),
                status,
                detail,
                total_pnl,
                pnl_currency,
                len(entered_legs),
                json.dumps(legs),
                utcnow(),
            )
        )
        c.execute(
            "UPDATE verification_runs SET finished_at=?,status='complete',result=?,detail=?,candle_time=? WHERE id=?",
            (utcnow(), status, detail, candle_time, run_id)
        )

    out = {
        "status": status,
        "detail": detail,
        "candle_time": candle_time,
        "pnl_amount": total_pnl,
        "pnl_currency": pnl_currency,
        "trades_count": len(entered_legs),
        "timeline": timeline,
    }
    return (out, timeline) if return_meta else out


def process_signals_batch(db, signal_ids, run_type="manual"):
    """Sort selected signal IDs oldest-first so earliest signals populate the candle cache once."""
    clean_ids = []
    seen = set()
    for raw_id in signal_ids:
        try:
            sid = int(raw_id)
        except (TypeError, ValueError):
            continue
        if sid not in seen:
            seen.add(sid)
            clean_ids.append(sid)
    if not clean_ids:
        return []

    placeholders = ",".join("?" for _ in clean_ids)
    with db.connection() as c:
        ordered_rows = c.execute(
            f"SELECT s.id FROM signals s "
            f"JOIN raw_notifications r ON r.id=s.raw_notification_id "
            f"WHERE s.id IN ({placeholders}) "
            f"ORDER BY COALESCE(r.notification_time, r.received_at) ASC, s.id ASC",
            clean_ids,
        ).fetchall()

    ordered_ids = [row["id"] for row in ordered_rows]
    found_set = set(ordered_ids)
    for sid in clean_ids:
        if sid not in found_set:
            ordered_ids.append(sid)

    return [{"signal_id": sid, **process_signal(db, sid, run_type)} for sid in ordered_ids]


class VerificationScheduler:
    def __init__(self, db):
        self.db = db
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._loop, daemon=True, name="verification-scheduler")

    def start(self):
        self.thread.start()

    def _loop(self):
        while not self.stop_event.wait(10):
            try:
                self.run_due()
            except Exception:
                pass

    def run_due(self):
        settings = self.db.settings()
        if settings.get("automatic_verification_enabled", "true").lower() != "true":
            return
        now = datetime.now(timezone.utc)
        boot = settings.get("verification_scheduler_started_at")
        initial_wait = int(settings.get("verification_initial_wait_minutes", "15"))

        with self.db.connection() as c:
            if boot:
                cutoff = (_dt(boot) - timedelta(minutes=initial_wait)).isoformat()
                c.execute(
                    "UPDATE verification_schedules SET status='manual_required',next_scheduled_at=NULL,"
                    "last_result=?,updated_at=? WHERE status='scheduled' AND signal_start_at<?",
                    (f"Older than {initial_wait} mins: manual processing required", utcnow(), cutoff)
                )
            due = [
                dict(x) for x in c.execute(
                    "SELECT * FROM verification_schedules WHERE status='scheduled' AND next_scheduled_at<=? "
                    "AND (expires_at IS NULL OR expires_at>?) ORDER BY next_scheduled_at LIMIT 25",
                    (now.isoformat(), now.isoformat())
                )
            ]

        for job in due:
            with self.db.connection() as c:
                claimed = c.execute(
                    "UPDATE verification_schedules SET status='processing',locked_at=?,updated_at=? WHERE id=? AND status='scheduled'",
                    (utcnow(), utcnow(), job["id"])
                ).rowcount
            if not claimed:
                continue

            result, timeline = process_signal(self.db, job["signal_id"], "automatic", job["id"], return_meta=True)
            with self.db.connection() as c:
                current = datetime.now(timezone.utc)
                expired = job["expires_at"] and _dt(job["expires_at"]) <= current
                is_done = (
                    result["status"] in TERMINAL
                    or (result["status"] == "target_hit" and timeline.get("all_targets_hit"))
                    or expired
                )
                if is_done:
                    status = "completed" if not expired else "expired"
                    next_time = None
                else:
                    minutes = (
                        int(self.db.settings().get("verification_retry_minutes", "15"))
                        if result["status"] == "awaiting_market_data"
                        else int(job["interval_minutes"])
                    )
                    status = "scheduled"
                    next_time = (current + timedelta(minutes=minutes)).isoformat()
                c.execute(
                    "UPDATE verification_schedules SET status=?,last_verified_at=?,next_scheduled_at=?,last_result=?,locked_at=NULL,updated_at=? WHERE id=?",
                    (status, utcnow(), next_time, result["status"], utcnow(), job["id"])
                )