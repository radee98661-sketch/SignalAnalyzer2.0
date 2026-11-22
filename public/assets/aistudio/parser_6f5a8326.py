import re
import unicodedata

SUP_MAP = str.maketrans({
    '⁰': '0', '¹': '1', '²': '2', '³': '3', '⁴': '4',
    '⁵': '5', '⁶': '6', '⁷': '7', '⁸': '8', '⁹': '9',
    '①': ' ', '②': ' ', '③': ' ', '④': ' ', '⑤': ' ',
    '⑥': ' ', '⑦': ' ', '⑧': ' ', '⑨': ' ', '⑩': ' ',
    '❶': ' ', '❷': ' ', '❸': ' ', '❹': ' ', '❺': ' ',
    '❻': ' ', '❼': ' ', '❽': ' ', '❾': ' ', '❿': ' ',
    '➀': ' ', '➁': ' ', '➂': ' ', '➃': ' ', '➄': ' ',
    '➅': ' ', '➆': ' ', '➇': ' ', '➈': ' ', '➉': ' ',
    '➊': ' ', '➋': ' ', '➌': ' ', '➍': ' ', '➎': ' ',
    '➏': ' ', '➐': ' ', '➑': ' ', '➒': ' ', '➓': ' ',
})

NOISE_WORDS = re.compile(
    r"^(BUY|SELL|LONG|SHORT|ENTRY|ENTRYS|ABOVE|ABV|BELOW|BLW|NOW|CMP|AT|NEAR|SL|TP|TGT|TARGET|TARGETS|STOPLOSS|STPLOSS|LEVEL|AGAIN|HERO|ZERO|COIN|PAIR|TYPE|SCALP|CROSS|MARGIN|RISK|USDT|USD|PERP|OPTION|OPTIONS|CALL|CALLS|PUT|PUTS|STOCK|STOCKS|ALERT|INTRADAY|POSITIONAL|BTST|STBT|HIGHLY|BREAKOUT|TRADE|WEEK|WAIT|FOR|PINNED|KING|LOOKING|GOOD|ONLY|MOVE|FAST|JOINING|FREE|CAPITAL|REQUIRED|PROFIT|DEMO|PREMIUM|RECOVERY|FUTURE|COMMODITY|MCX|EXPIRY|EX)$",
    re.I
)

FALSE_FOREX_WORDS = re.compile(
    r"^(TARGET|STOPLO|STPLOS|SIGNAL|OPTION|CENTER|MASTER|TRADER|EXPERT|CRYPTO|ANDREW|FREE|TRAIL|SCALP|CROSS|MARGIN|COINS|ENTRY|ENTRYS|SHORT|LONG|ALERT|PINNED|LUPIN|PNB|ABB|SENSEX|COPPER|SILVER|GOLD)$",
    re.I
)

MONTHS_PATTERN = r"(?:JAN(?:UARY)?|FEB(?:RUARY)?|MAR(?:CH)?|APR(?:IL)?|MAY|JUN(?:E)?|JUL(?:Y)?|AUG(?:UST)?|SEP(?:TEMBER)?|OCT(?:OBER)?|NOV(?:EMBER)?|DEC(?:EMBER)?)"

MCX_COMMODITY_ALIASES = {
    "CRUDEOILM": "CRUDEOILM",
    "CRUDEOIL": "CRUDEOIL",
    "CRUDE": "CRUDEOIL",
    "NATURALGASM": "NATURALGASM",
    "NATURALGAS": "NATURALGAS",
    "NATGASMINI": "NATGASMINI",
    "NATGAS": "NATURALGAS",
    "GOLDM": "GOLDM",
    "GOLDPETAL": "GOLDPETAL",
    "GOLDGUINEA": "GOLDGUINEA",
    "GOLD": "GOLD",
    "SILVERMIC": "SILVERMIC",
    "SILVERM": "SILVERM",
    "SILVER": "SILVER",
    "COPPER": "COPPER",
    "ZINC": "ZINC",
    "ALUMINIUM": "ALUMINIUM",
    "ALUMINUM": "ALUMINIUM",
    "LEAD": "LEAD",
    "NICKEL": "NICKEL",
}

FOREX_SYMBOL_ALIASES = {
    "GOLD": "XAUUSD",
    "XAU": "XAUUSD",
    "XAUUSD": "XAUUSD",
    "SILVER": "XAGUSD",
    "XAG": "XAGUSD",
    "XAGUSD": "XAGUSD",
    "USOIL": "USOIL",
    "WTI": "USOIL",
    "WTIUSD": "USOIL",
    "XTIUSD": "USOIL",
    "UKOIL": "UKOIL",
    "BRENT": "UKOIL",
    "XBRUSD": "UKOIL",
}

FOREX_PAIRS = {
    "EURUSD", "GBPUSD", "USDJPY", "GBPJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD",
    "EURJPY", "EURGBP", "EURAUD", "EURCAD", "EURCHF", "GBPAUD", "GBPCAD", "GBPCHF",
    "AUDJPY", "AUDCAD", "AUDNZD", "CADJPY", "CHFJPY", "NZDJPY",
    "XAUUSD", "XAGUSD", "USOIL", "UKOIL", "US30", "NAS100", "US100", "SPX500", "US500", "GER40", "UK100",
}

DEFAULT_SL_SEED = {
    "OPTIONS": {
        "category_default": 20, "tp1": 20, "tp2": 40, "tp3": 60, "lots": 1, "lot_size": 65,
        "symbols": {
            "NIFTY": {"sl": 20, "tp1": 20, "tp2": 40, "tp3": 60, "lots": 1, "lot_size": 65},
            "BANKNIFTY": {"sl": 40, "tp1": 40, "tp2": 80, "tp3": 120, "lots": 1, "lot_size": 30},
            "FINNIFTY": {"sl": 20, "tp1": 20, "tp2": 40, "tp3": 60, "lots": 1, "lot_size": 60},
            "MIDCPNIFTY": {"sl": 20, "tp1": 20, "tp2": 40, "tp3": 60, "lots": 1, "lot_size": 120},
            "SENSEX": {"sl": 40, "tp1": 40, "tp2": 80, "tp3": 120, "lots": 1, "lot_size": 20},
        },
    },
    "STOCK_OPTIONS": {
        "category_default": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 500,
        "symbols": {},
    },
    "MCX": {
        "category_default": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100,
        "symbols": {
            "CRUDEOIL:OPT": {"sl": 20, "tp1": 20, "tp2": 40, "tp3": 60, "lots": 1, "lot_size": 100},
            "CRUDEOIL:FUT": {"sl": 50, "tp1": 50, "tp2": 100, "tp3": 150, "lots": 1, "lot_size": 100},
            "NATURALGAS:FUT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 1250},
            "NATURALGAS:OPT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 1250},
            "GOLD:FUT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100},
            "GOLDM:OPT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 10},
            "SILVER:FUT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 30},
            "SILVERM:OPT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 5},
            "COPPER:FUT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 2500},
        },
    },
    "FOREX": {
        "category_default": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000,
        "symbols": {
            "XAUUSD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100},
            "XAGUSD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 5000},
            "EURUSD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "GBPUSD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "USDJPY": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "GBPJPY": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "AUDUSD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "USDCAD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "USDCHF": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "NZDUSD": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100000},
            "USOIL": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "lots": 1, "lot_size": 100},
        },
    },
    "CRYPTO": {
        "category_default": None, "tp1": None, "tp2": None, "tp3": None, "usd_amount": 100,
        "symbols": {
            "BTCUSDT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "usd_amount": 100},
            "ETHUSDT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "usd_amount": 100},
            "SOLUSDT": {"sl": None, "tp1": None, "tp2": None, "tp3": None, "usd_amount": 100},
        },
    },
}


def normalize_sl_category(category_hint, symbol="", option_type=None):
    c = str(category_hint or "").strip().upper()
    sym = str(symbol or "").strip().upper()
    if "STOCK OPTIONS" in c or "STOCK_OPTIONS" in c:
        return "STOCK_OPTIONS"
    if "INDIAN OPTIONS" in c or c == "OPTIONS" or "INDEX OPTIONS" in c:
        return "OPTIONS"
    if "MCX" in c or sym in {"CRUDEOIL", "CRUDEOILM", "NATURALGAS", "NATGAS", "GOLD", "GOLDM", "SILVER", "SILVERM", "COPPER", "ZINC", "ALUMINIUM", "LEAD"}:
        return "MCX"
    if "FOREX" in c or c == "FX" or sym in {"XAUUSD", "XAGUSD", "EURUSD", "GBPUSD", "USDJPY", "GBPJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD", "EURGBP", "EURJPY", "USOIL", "UKOIL", "US30", "NAS100"}:
        return "FOREX"
    if "CRYPTO" in c or sym.endswith("USDT") or sym.endswith("USDC") or sym.endswith("BUSD"):
        return "CRYPTO"
    if sym in {"NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "BANKEX"}:
        return "OPTIONS"
    if option_type:
        return "STOCK_OPTIONS"
    return "OPTIONS"


def forex_pip_size(symbol):
    sym = str(symbol or "").strip().upper()
    if sym in ("XAUUSD", "GOLD"):
        return 0.1
    if "JPY" in sym or sym in ("XAGUSD", "SILVER", "USOIL", "UKOIL", "XTIUSD", "XBRUSD"):
        return 0.01
    if sym in ("US30", "NAS100", "US100", "SPX500", "US500", "GER40", "UK100"):
        return 1.0
    return 0.0001


def _clean_sl_offset(val):
    if val is None or val == "":
        return None
    try:
        num = float(val)
        return num if num > 0 else None
    except (TypeError, ValueError):
        return None


def resolve_instrument_profile(rules, category_hint, symbol, option_type=None):
    """Return merged dict {'sl', 'tp1', 'tp2', 'tp3', 'lots', 'lot_size', 'usd_amount', 'unit_type', 'source_label'}."""
    cat = normalize_sl_category(category_hint, symbol, option_type)
    unit_type = "pips" if cat == "FOREX" else ("pct" if cat == "CRYPTO" else "points")
    cat_block = (rules or {}).get(cat) or {}
    sym_map = cat_block.get("symbols") or {}
    sym = str(symbol or "").strip().upper()
    is_opt = bool(option_type)

    candidates = []
    if sym:
        if cat == "MCX":
            candidates.append((f"{sym}:{'OPT' if is_opt else 'FUT'}", f"{sym} ({'Options' if is_opt else 'Futures'})"))
        candidates.append((sym, sym))

    sym_entry = {}
    source_label = f"{cat} default"
    for key, label in candidates:
        raw_entry = sym_map.get(key)
        if isinstance(raw_entry, dict):
            sym_entry = raw_entry
            source_label = label
            break
        elif raw_entry is not None:
            sym_entry = {"sl": raw_entry}
            source_label = label
            break

    def pick(field, fallback_key=None):
        v = _clean_sl_offset(sym_entry.get(field))
        if v is not None:
            return v
        return _clean_sl_offset(cat_block.get(fallback_key or field))

    return {
        "category": cat,
        "unit_type": unit_type,
        "source_label": source_label,
        "sl": pick("sl", "category_default"),
        "tp1": pick("tp1"),
        "tp2": pick("tp2"),
        "tp3": pick("tp3"),
        "lots": pick("lots") or 1.0,
        "lot_size": pick("lot_size"),
        "usd_amount": pick("usd_amount") or 100.0,
    }


def resolve_default_sl(rules, category_hint, symbol, option_type=None):
    prof = resolve_instrument_profile(rules, category_hint, symbol, option_type)
    if prof["sl"] is None:
        return None, None, None
    return prof["sl"], prof["unit_type"], prof["source_label"]


def apply_default_targets(parsed, category_hint="", sl_rules=None):
    """Populate TP1/TP2/TP3 from default rules when the parsed signal has no numeric targets."""
    if not isinstance(parsed, dict) or not sl_rules:
        return parsed
    if parsed.get("targets"):
        return parsed

    entry = parsed.get("entry_price")
    direction = (parsed.get("direction") or "").upper()
    symbol = parsed.get("symbol") or ""
    if entry is None or direction not in ("BUY", "SELL") or not symbol:
        return parsed

    prof = resolve_instrument_profile(
        sl_rules,
        category_hint or parsed.get("category") or "",
        symbol,
        parsed.get("option_type"),
    )
    offsets = [prof["tp1"], prof["tp2"], prof["tp3"]]
    if not any(x is not None for x in offsets):
        return parsed

    all_entries = [float(entry)] + [float(x) for x in (parsed.get("additional_entries") or []) if x is not None]
    ref_entry = sum(all_entries) / len(all_entries)
    unit_type = prof["unit_type"]

    generated = []
    for off in offsets:
        if off is None:
            continue
        if unit_type == "pips":
            pip = forex_pip_size(symbol)
            delta = off * pip
            decimals = 5 if pip <= 0.0001 else (3 if pip <= 0.01 else 2)
        elif unit_type == "pct":
            delta = ref_entry * (off / 100.0)
            decimals = 8 if ref_entry < 1 else (4 if ref_entry < 100 else 2)
        else:
            delta = off
            decimals = 4 if ref_entry < 1 else 2

        if direction == "BUY":
            tp_val = round(ref_entry + delta, decimals)
        else:
            raw_tp = ref_entry - delta
            tp_val = round(raw_tp if raw_tp > 0 else max(0.05, ref_entry * 0.5), decimals)

        if float(tp_val).is_integer() and decimals <= 2:
            tp_val = float(int(tp_val))
        generated.append(tp_val)

    if generated:
        parsed["targets"] = generated
        parsed["default_tp_applied"] = True
        tp_str = " / ".join(str(x) for x in generated)
        note_tag = f"Default TPs: {tp_str} ({prof['source_label']})"
        expl = parsed.get("explanation") or ""
        if note_tag not in expl:
            parsed["explanation"] = (expl.rstrip(".") + f" | {note_tag}.").strip()
    return parsed


def apply_default_sl(parsed, category_hint="", sl_rules=None):
    """Populate stop_loss from default SL rules when the parsed signal has no explicit SL."""
    if not isinstance(parsed, dict) or not sl_rules:
        return parsed
    if parsed.get("stop_loss") is not None:
        return parsed

    entry = parsed.get("entry_price")
    direction = (parsed.get("direction") or "").upper()
    symbol = parsed.get("symbol") or ""
    if entry is None or direction not in ("BUY", "SELL") or not symbol:
        return parsed

    cat = category_hint or parsed.get("category") or ""
    offset_val, unit_type, source_label = resolve_default_sl(sl_rules, cat, symbol, parsed.get("option_type"))
    if offset_val is None:
        return parsed

    all_entries = [float(entry)] + [float(x) for x in (parsed.get("additional_entries") or []) if x is not None]
    ref_low = min(all_entries)
    ref_high = max(all_entries)

    if unit_type == "pips":
        pip = forex_pip_size(symbol)
        delta = offset_val * pip
        decimals = 5 if pip <= 0.0001 else (3 if pip <= 0.01 else 2)
        unit_text = f"{offset_val:g} pips"
    elif unit_type == "pct":
        base_ref = ref_low if direction == "BUY" else ref_high
        delta = base_ref * (offset_val / 100.0)
        decimals = 8 if base_ref < 1 else (4 if base_ref < 100 else 2)
        unit_text = f"{offset_val:g}%"
    else:
        delta = offset_val
        decimals = 4 if ref_low < 1 else 2
        unit_text = f"{offset_val:g} pts"

    if direction == "BUY":
        raw_sl = ref_low - delta
        if raw_sl <= 0:
            raw_sl = max(0.05, ref_low * 0.5)
        sl_price = round(raw_sl, decimals)
    else:
        sl_price = round(ref_high + delta, decimals)

    if float(sl_price).is_integer() and decimals <= 2:
        sl_price = float(int(sl_price))

    parsed["stop_loss"] = sl_price
    parsed["default_sl_applied"] = True
    parsed["default_sl_note"] = f"Default SL {sl_price} ({unit_text} via {source_label})"
    expl = parsed.get("explanation") or ""
    note_tag = f"Default SL: {sl_price} ({unit_text} - {source_label})"
    if note_tag not in expl:
        parsed["explanation"] = (expl.rstrip(".") + f" | {note_tag}.").strip()
    return parsed


def _normalize_text(text):
    s = str(text or "")
    s = re.sub("[0-9#*]\ufe0f?\u20e3", " ", s)
    s = unicodedata.normalize("NFKC", s).translate(SUP_MAP)

    pinned_m = re.search(r'\bpinned\s+"([\s\S]+)"\s*$', s, re.I)
    if pinned_m:
        s = pinned_m.group(1)

    s = re.sub(r"[🥇🥈🥉🏅🏅🎯🛑⛔️🚫✅✔️🚀📈📉💎🔥✨🌟⚡️💢✳️🟢🔴🔺🔻🔸🔹•●▪▫►▶📊📙📌💛⭕❗👆💚🐎🏮👀🔼👇❤️🖼🛒🏆⚠️👨‍🎓🟡📲☎️🌁🌉☑♻️❌]+", " ", s)
    s = re.sub(r"[╭╮╯╰━─═│┃✦★☆©®™◾]+", " ", s)
    s = re.sub(r"[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]", "-", s)
    s = re.sub(r"(\d)\.\.(\d)", r"\1.\2", s)
    s = re.sub(r"(\d)\++", r"\1", s)

    s = re.sub(r"\bCRUDE\s*-?\s*OIL\b", "CRUDEOIL", s, flags=re.I)
    s = re.sub(r"\bNATURAL\s*-?\s*G[AE]S\b", "NATURALGAS", s, flags=re.I)

    s = re.sub(
        r"\b([A-Z]{2,15})(" + MONTHS_PATTERN + r")(\d{2,6}(?:\.\d+)?)\s*(CE|PE|CALL|PUT)\b",
        r"\1 \2 \3 \4",
        s,
        flags=re.I
    )

    s = re.sub(r"\b([A-Z0-9]{2,15})\s*/\s*(USDT|USDC|BUSD|USD|BTC|ETH)\b", r"\1/\2", s, flags=re.I)
    s = re.sub(r"\b(XAU|XAG|EUR|GBP|USD|AUD|NZD|CAD|CHF)/(USD|JPY|GBP|EUR|CAD|AUD|CHF|NZD)\b", r"\1\2", s, flags=re.I)
    s = re.sub(r"&#x20;|&nbsp;", " ", s, flags=re.I)
    s = s.replace("**", "").replace("*", "").replace("₹", " ").replace("\r", "")
    s = re.sub(r"[ \t]+", " ", s)
    return s.strip()


def _parse_number(val):
    if val is None or val == "":
        return None
    s = str(val).replace(",", "").strip()
    if not s:
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _extract_numbers(text):
    matches = re.findall(r"[0-9,]+(?:\.[0-9]+)?", str(text or ""))
    out = []
    for m in matches:
        n = _parse_number(m)
        if n is not None:
            out.append(n)
    return out


def _mask_span(text, start, end):
    return text[:start] + (" " * (end - start)) + text[end:]


def _mask_noise_tokens(text):
    """Mask dates, times, 'k' amounts, leverage, lot sizes, phone numbers, and CMP clauses."""
    s = re.sub(
        r"\b(\d{1,2})\s*" + MONTHS_PATTERN + r"(?:\s*(?:20\d{2}|EX(?:PIRY)?))?\b",
        lambda m: " " * len(m.group(0)),
        text,
        flags=re.I
    )
    s = re.sub(r"\(?\b" + MONTHS_PATTERN + r"(?:\s*20\d{2})?\b\)?", lambda m: " " * len(m.group(0)), s, flags=re.I)
    s = re.sub(r"\b\d{1,2}(?:[:.]\d{2})?\s*(?:AM|PM)\b", lambda m: " " * len(m.group(0)), s, flags=re.I)
    s = re.sub(r"\b\d+(?:\.\d+)?(?:\s*-\s*\d+(?:\.\d+)?)?\s*K\b", lambda m: " " * len(m.group(0)), s, flags=re.I)
    s = re.sub(r"\b\d+(?:\.\d+)?\s*/-", lambda m: " " * len(m.group(0)), s)
    s = re.sub(r"\b\d+\s*(?:WEEKS?|LOTS?)\b", lambda m: " " * len(m.group(0)), s, flags=re.I)
    s = re.sub(r"\bLOTS?\s*SIZE\s*[:=\-]?\s*\d+\b", lambda m: " " * len(m.group(0)), s, flags=re.I)
    s = re.sub(r"\b\d{10,12}\b", lambda m: " " * len(m.group(0)), s)
    s = re.sub(r"@[A-Za-z0-9_]+", lambda m: " " * len(m.group(0)), s)
    s = re.sub(
        r"\b(?:LEV(?:ERAGE)?\s*[:=\-/]?\s*)?(?:CROSS\s*|ISOLATED\s*)?\d{1,3}(?:\.\d+)?\s*[X×](?:\s*(?:/|-|TO)\s*\d{1,3}(?:\.\d+)?\s*[X×])?\b",
        lambda m: " " * len(m.group(0)),
        s,
        flags=re.I
    )
    s = re.sub(r"\b\d+(?:\.\d+)?(?:\s*-\s*\d+(?:\.\d+)?)?\s*%", lambda m: " " * len(m.group(0)), s)
    s = re.sub(r"\b\d{1,4}\s*(?:DMA|EMA|SMA|MA|RSI)\b", lambda m: " " * len(m.group(0)), s, flags=re.I)
    s = re.sub(r"\bCMP\s*[:=\-\s(]*[0-9,]+(?:\.[0-9]+)?\)?", lambda m: " " * len(m.group(0)), s, flags=re.I)
    return s


def _expand_range_pair(raw_a, raw_b):
    a = _parse_number(raw_a)
    b = _parse_number(raw_b)
    if a is None or b is None:
        return None

    clean_a = str(raw_a).replace(",", "").strip()
    clean_b = str(raw_b).replace(",", "").strip()

    if a >= 100 and "." not in clean_a and "." not in clean_b and len(clean_a) > len(clean_b):
        scale = 10 ** len(clean_b)
        base = (int(a) // scale) * scale
        candidates = [base + b, base + scale + b, base - scale + b]
        best = min(candidates, key=lambda c: abs(c - a))
        if abs(best - a) <= a * 0.1:
            b = best

    return (min(a, b), max(a, b))


def _clean_targets(arr):
    seen = set()
    res = []
    for v in arr:
        if v is not None and v not in seen:
            seen.add(v)
            res.append(v)
    return res[:10]


def _detect_category(category_hint, text):
    c = str(category_hint or "").upper()
    if "STOCK OPTIONS" in c or "STOCK_OPTIONS" in c:
        return "STOCK_OPTIONS"
    if "OPTIONS" in c:
        return "OPTIONS"
    if "MCX" in c:
        return "MCX"
    if "FOREX" in c or "FX" in c:
        return "FOREX"
    if "CRYPTO" in c:
        return "CRYPTO"

    if re.search(r"\b(MCX|CRUDEOIL|NATURALGAS|NATGAS|COPPER|ZINC|GOLDM|SILVERM)\b", text, re.I):
        return "MCX"

    if re.search(r"(?:#|\$|\b)[A-Z0-9]{2,15}\s*/\s*(?:USDT|USDC|BUSD|USD)\b", text, re.I):
        return "CRYPTO"
    if re.search(r"\b[A-Z0-9]{2,15}USDT\b", text, re.I):
        return "CRYPTO"
    if re.search(r"\b[A-Z0-9]{2,15}\s*/\s*(?:LONG|SHORT|BUY|SELL)\s*-\s*USDT\b", text, re.I):
        return "CRYPTO"
    if re.search(r"(?:\$|\b)(?:BTC|ETH|SOL|BNB|XRP|DOGE|ADA|AVAX|LINK|DOT|1000LUNC|1000PEPE|1000SHIB|1000FLOKI|1000BONK)(?:USDT|USD)?\b", text, re.I):
        return "CRYPTO"

    if re.search(r"\b(BANKNIFTY|NIFTY|FINNIFTY|MIDCPNIFTY|SENSEX)\b", text, re.I):
        return "OPTIONS"
    if re.search(r"\b(XAUUSD|XAGUSD|US30|NAS100|US100|SPX500|US500|GER40|UK100|USOIL|UKOIL|XTIUSD|XBRUSD|COMEX)\b", text, re.I):
        return "FOREX"
    if re.search(r"\b(EURUSD|GBPUSD|USDJPY|USDCHF|USDCAD|AUDUSD|NZDUSD|EURGBP|EURJPY|GBPJPY|NZDCAD|AUDJPY|CADJPY|CHFJPY|EURAUD|GBPAUD)\b", text, re.I):
        return "FOREX"

    if re.search(r"\b(SILVER|GOLD)\b", text, re.I):
        nums = _extract_numbers(text)
        if nums and nums[0] < 15000:
            return "FOREX"
        return "MCX"
    if re.search(r"(?:^|[\s_/\-])\d{2,6}\s*(?:CE|PE|CALL|PUT)\b", text, re.I):
        return "STOCK_OPTIONS"
    return ""


def _detect_symbol(category, text):
    m_pair = re.search(r"(?:#|\$|\b)([A-Z0-9]{2,15})\s*/\s*(USDT|USDC|BUSD|USD)\b", text, re.I)
    if m_pair and not NOISE_WORDS.match(m_pair.group(1)):
        base = m_pair.group(1).upper()
        quote = m_pair.group(2).upper()
        return base + ("USDT" if quote in ("USDT", "USD") else quote)

    m_attached = re.search(r"(?:#|\$|\b)([A-Z0-9]{2,15}USDT)\b", text, re.I)
    if m_attached:
        return m_attached.group(1).upper()

    m_weird = re.search(r"\b([A-Z0-9]{2,15})\s*/\s*(?:LONG|SHORT|BUY|SELL)\s*-\s*USDT\b", text, re.I)
    if m_weird and not NOISE_WORDS.match(m_weird.group(1)):
        return m_weird.group(1).upper() + "USDT"

    if category == "MCX":
        for pat, sym in [
            (r"\bCRUDEOIL\b", "CRUDEOIL"),
            (r"\bNATURALGAS\b|\bNATGAS\b", "NATURALGAS"),
            (r"\bSILVERM\b", "SILVERM"),
            (r"\bSILVER\b", "SILVER"),
            (r"\bGOLDM\b", "GOLDM"),
            (r"\bGOLD\b", "GOLD"),
            (r"\bCOPPER\b", "COPPER"),
            (r"\bZINC\b", "ZINC"),
            (r"\bALUMINI?UM\b", "ALUMINIUM"),
            (r"\bLEAD\b", "LEAD"),
        ]:
            if re.search(pat, text, re.I):
                return sym
        return None

    if category == "FOREX":
        if re.search(r"\b(XAUUSD|GOLD)\b", text, re.I):
            return "XAUUSD"
        if re.search(r"\b(XAGUSD|SILVER)\b", text, re.I):
            return "XAGUSD"
        m = re.search(r"\b(US30|NAS100|US100|SPX500|US500|GER40|UK100|USOIL|UKOIL|XTIUSD|XBRUSD|BTCUSD|ETHUSD|[A-Z]{6})\b", text, re.I)
        if m and not FALSE_FOREX_WORDS.match(m.group(1)):
            return m.group(1).upper()
        return None

    if category in ("OPTIONS", "STOCK_OPTIONS"):
        m = re.search(r"\b(BANKNIFTY|NIFTY|FINNIFTY|MIDCPNIFTY|SENSEX)\b", text, re.I)
        if m:
            return m.group(1).upper()
        return None

    if category == "CRYPTO":
        m_coin = re.search(
            r"(?:(?:#|\$|\b)(1000[A-Z]{2,10}|[A-Z]{2,12})(?:USDT|USD)?\s+(?:LONG|SHORT|BUY|SELL)\b|\b(?:LONG|SHORT|BUY|SELL)\s+(?:#|\$)?(1000[A-Z]{2,10}|[A-Z]{2,12})(?:USDT|USD)?\b)",
            text, re.I
        )
        if m_coin:
            raw_coin = (m_coin.group(1) or m_coin.group(2)).upper()
            if not NOISE_WORDS.match(raw_coin):
                return raw_coin if raw_coin.endswith("USDT") else raw_coin + "USDT"
        return None

    return None


def _mask_symbol_tokens(working_text, symbol):
    if not symbol:
        return working_text
    base = re.sub(r"(USDT|USDC|BUSD|USD)$", "", symbol, flags=re.I)
    if base and len(base) >= 2:
        working_text = re.sub(
            r"(?:#|\$|\b)" + re.escape(base) + r"(?:\s*/\s*(?:USDT|USDC|BUSD|USD)|USDT|USD)?\b",
            lambda m: " " * len(m.group(0)),
            working_text,
            flags=re.I
        )
    return working_text


def _detect_option_contract(symbol, working_text):
    strike = None
    option_type = None
    anchor_end = -1
    masked = working_text

    m_multi = re.search(r"\b(MAX\s+HEALTH|TATA\s+MOTORS|TATA\s+STEEL|TATA\s+POWER|BAJAJ\s+FINANCE|BAJAJ\s+AUTO|TECH\s+MAHINDRA|SUN\s+PHARMA|INDUSIND\s+BANK|AXIS\s+BANK|ICICI\s+BANK|HDFC\s+BANK|KOTAK\s+BANK)\s+(\d{2,6}(?:\.\d+)?)\s*(CE|PE|CALL|PUT)\b", masked, re.I)
    if m_multi:
        if not symbol:
            symbol = re.sub(r"\s+", "", m_multi.group(1).upper())
        strike = _parse_number(m_multi.group(2))
        option_type = "CE" if m_multi.group(3).upper().startswith("C") else "PE"
        anchor_end = m_multi.end()
        masked = _mask_span(masked, m_multi.start(), anchor_end)
        return symbol, strike, option_type, masked, anchor_end

    m1 = re.search(
        r"(?:^|[^A-Z0-9])([A-Z][A-Z0-9&\-]{1,15})\s*(?:(?:\d{1,2}\s*)?" + MONTHS_PATTERN + r"\s*)?[/\-_]*\s*(\d{2,6}(?:\.\d+)?)\s*(CE|PE|CALL|PUT)\b",
        masked, re.I
    )
    if m1 and not NOISE_WORDS.match(m1.group(1)):
        raw_u = m1.group(1).upper()
        if not symbol:
            symbol = raw_u
        strike = _parse_number(m1.group(2))
        option_type = "CE" if m1.group(3).upper().startswith("C") else "PE"
        anchor_end = m1.end()
        masked = _mask_span(masked, m1.start(), anchor_end)
        return symbol, strike, option_type, masked, anchor_end

    m2 = re.search(r"(?:^|[\s_/\-])(\d{2,6}(?:\.\d+)?)\s*[/\-]?\s*(CE|PE|CALL|PUT)\b", masked, re.I)
    if m2:
        strike = _parse_number(m2.group(1))
        option_type = "CE" if m2.group(2).upper().startswith("C") else "PE"
        anchor_end = m2.end()
        masked = _mask_span(masked, m2.start(), anchor_end)
        if not symbol:
            prefix = working_text[:m2.start()]
            tokens = re.findall(r"\b[A-Z][A-Z0-9&]{1,15}\b", prefix, re.I)
            for t in reversed(tokens):
                if not NOISE_WORDS.match(t) and not re.match(r"^" + MONTHS_PATTERN + r"$", t, re.I):
                    symbol = t.upper()
                    break
        return symbol, strike, option_type, masked, anchor_end

    m3 = re.search(r"\b(CE|PE)\b", masked, re.I)
    if m3:
        option_type = m3.group(1).upper()
        anchor_end = m3.end()
        masked = _mask_span(masked, m3.start(), anchor_end)

    return symbol, strike, option_type, masked, anchor_end


def _extract_explicit_sl(working_text):
    masked = working_text
    stop_loss = None
    sl_status = None
    target_status = None

    prem = re.search(
        r"\b(?:TARGETS?|TGT|TP)\s*(?:&|AND)\s*(?:SL|STOPLOSS|STPLOSS|STOP\s*LOSS)\s*(?:ONLY\s*FOR\s*)?(PREMIUM(?:\s*MEMBERS)?|VIP|PAID)\b",
        masked, re.I
    )
    if prem:
        return _mask_span(masked, prem.start(), prem.end()), None, "VIP", "VIP"

    sl_hash = re.search(r"\b(?:STOP\s*LOSS|STOP-LOSS|STOPLOSS|STPLOSS|S/L|SL)\s*[:=\-\s]{0,4}#{2,}", masked, re.I)
    if sl_hash:
        sl_status = "HIDDEN"
        masked = _mask_span(masked, sl_hash.start(), sl_hash.end())

    sl_status_m = re.search(
        r"\b(?:STOP\s*LOSS|STOP-LOSS|STOPLOSS|STPLOSS|S/L|SL)\s*[:=\-\s]{0,6}(PAID(?:\s*CLIENTS)?|VIP(?:\s*GROUP)?|PREMIUM|FOLLOW|TRAIL(?:ING)?|COST|CTC|KEEP\s*SM(?:A)?LL|SMALL)\b",
        masked, re.I
    )
    if sl_status_m:
        val = sl_status_m.group(1).upper()
        if "SM" in val:
            sl_status = "SMALL"
        elif "VIP" in val or "PREMIUM" in val:
            sl_status = "VIP"
        elif "PAID" in val:
            sl_status = "PAID"
        else:
            sl_status = val
        masked = _mask_span(masked, sl_status_m.start(), sl_status_m.end())

    sl_m = re.search(
        r"\b(?:STOP\s*LOSS|STOP-LOSS|STOPLOSS|STPLOSS|S/L|SL)(?:[ \t]*(?:POINT|LEVEL|PRICE|ZONE|AT|NEAR|BELOW|ABOVE|IS))?[^\w\d\n]{0,6}\$?[ \t]*([0-9,]+(?:\.[0-9]+)?)\b",
        masked, re.I
    )
    if sl_m:
        stop_loss = _parse_number(sl_m.group(1))
        masked = _mask_span(masked, sl_m.start(), sl_m.end())

    return masked, stop_loss, sl_status, target_status


def _extract_explicit_targets(working_text, existing_target_status=None, pip_targets_out=None):
    masked = working_text
    targets_map = {}
    targets_list = []
    target_status = existing_target_status

    for m in list(re.finditer(r"\b(?:TARGETS?|TGT|TG|TP)\s*[0-9]{0,2}\s*[:=.\-_]{0,4}\s*(\d+(?:\.\d+)?)\s*PIPS?\b", masked, re.I)):
        if pip_targets_out is not None:
            pip_val = _parse_number(m.group(1))
            if pip_val is not None:
                pip_targets_out.append(pip_val)
        masked = _mask_span(masked, m.start(), m.end())

    for m in list(re.finditer(r"\b(?:TARGETS?|TGT|TG|TP)\s*[0-9]{0,2}\s*[:=.\-_]{0,4}\s*(?:ONLY\s*FOR\s*)?(VIP(?:\s*GROUP)?|PAID(?:\s*CLIENTS)?|OPEN|YOUR|PREMIUM(?:\s*MEMBERS)?)\b", masked, re.I)):
        if not target_status:
            st = m.group(1).upper()
            if "VIP" in st or "PREMIUM" in st:
                target_status = "VIP"
            elif "PAID" in st:
                target_status = "PAID"
            else:
                target_status = "OPEN"
        masked = _mask_span(masked, m.start(), m.end())

    for m in list(re.finditer(r"\b([1-9]|10)\s*[_\-.]?\s*(?:TP|TGT|TARGET)\s*[:=.\-_]{0,4}\s*[$@\s]*([0-9,]+(?:\.[0-9]+)?)", masked, re.I)):
        idx = int(m.group(1)) - 1
        num = _parse_number(m.group(2))
        if num is not None:
            targets_map[idx] = num
        masked = _mask_span(masked, m.start(), m.end())

    for m in list(re.finditer(
        r"\b(?:PREMIUM\s+)?(?:TARGETS?|TGT|TG|TP)(?:([1-9]|10)(?=\s*[:=.\-_@$]|\s+\d))?\s*[:=.\-_]{0,4}\s*[$@\s]*((?:(?:[1-9]|10)\s*[:=.\-]\s*)?[$]?\s*[0-9,]+(?:\.[0-9]+)?(?:\s*(?:[-/,\n]|AND|TO)\s*(?:(?:[1-9]|10)\s*[:=.\-]\s*)?[$]?\s*[0-9,]+(?:\.[0-9]+)?)*)\+*",
        masked, re.I
    )):
        idx = int(m.group(1)) - 1 if m.group(1) else -1
        raw_block = m.group(2)
        cleaned_block = re.sub(r"(?:^|\n)\s*(?:[1-9]|10)\s*[:=.\-]\s*", " ", raw_block)
        nums = _extract_numbers(cleaned_block)
        if idx >= 0 and len(nums) == 1:
            targets_map[idx] = nums[0]
        else:
            targets_list.extend(nums)
        masked = _mask_span(masked, m.start(), m.end())

    ordered = [targets_map[k] for k in sorted(targets_map.keys())] + targets_list
    return masked, _clean_targets(ordered), target_status


def _extract_entry(working_text, option_anchor_end):
    search_offset = option_anchor_end if option_anchor_end > 0 else 0
    search_slice = working_text[search_offset:]

    range_ctx = re.search(
        r"\b(?:ENTRYS?|ENTRY(?:\s*(?:BUY|SELL|PRICE|RANGE|ZONE|LEVEL))?|BUY\s*RANGE|SELL\s*RANGE|BUY\s*LEVEL|SELL\s*LEVEL|LEVEL|ZONE|RANGE|BUY|SELL|LONG|SHORT|ABOVE|ABV|BELOW|AT|NEAR(?:\s*LEVEL)?|CMP|NOW|@)(?:\s+(?:NOW|CMP|STOP|LIMIT|AT|RANGE|ZONE|LEVEL|TO|ONLY))?(?:\s+[A-Z][A-Z0-9]{1,11})?\s*[:=.\-_@$\s]{0,6}([0-9,]+(?:\.[0-9]+)?)\s*(?:[-/_]|TO)\s*\$?\s*([0-9,]+(?:\.[0-9]+)?)",
        search_slice, re.I
    )
    if not range_ctx and option_anchor_end > 0:
        range_ctx = re.search(
            r"^\s*(?:BUY|SELL|ABOVE|ABV|BELOW|AT|NEAR|CMP|NOW|LEVEL|RANGE|TO|ONLY|@|:|-|=)*\s*([0-9,]+(?:\.[0-9]+)?)\s*(?:[-/_]|TO)\s*([0-9,]+(?:\.[0-9]+)?)",
            search_slice, re.I
        )
    if range_ctx:
        pair = _expand_range_pair(range_ctx.group(1), range_ctx.group(2))
        if pair:
            abs_start = search_offset + range_ctx.start()
            return _mask_span(working_text, abs_start, abs_start + len(range_ctx.group(0))), [pair[0], pair[1]], "HIGH"

    single_ctx = re.search(
        r"\b(?:ENTRYS?|ENTRY(?:\s*(?:ABOVE|BELOW|PRICE|LEVEL|ZONE))?|BUY\s*LEVEL|SELL\s*LEVEL|LEVEL|ZONE|BUY(?:\s*ABOVE|\s*BELOW)?|SELL(?:\s*ABOVE|\s*BELOW)?|LONG|SHORT|ABOVE|ABV|BELOW|NEAR(?:\s*LEVEL)?|CMP|NOW|@)(?:\s+(?:NOW|CMP|STOP|LIMIT|AT|ONLY|TO|LEVEL))?(?:\s+[A-Z][A-Z0-9]{1,11})?\s*[:=.\-_@$\s]{0,6}([0-9,]+(?:\.[0-9]+)?)\b",
        search_slice, re.I
    )
    if single_ctx:
        val = _parse_number(single_ctx.group(1))
        if val is not None:
            abs_start = search_offset + single_ctx.start()
            entries = [val]
            masked_after = _mask_span(working_text, abs_start, abs_start + len(single_ctx.group(0)))
            add_m = re.search(r"\bADD\s*MORE\s*[:=\-]?\s*([0-9,]+(?:\.[0-9]+)?)\b", masked_after, re.I)
            if add_m:
                add_val = _parse_number(add_m.group(1))
                if add_val is not None:
                    entries.append(add_val)
                    masked_after = _mask_span(masked_after, add_m.start(), add_m.end())
            return masked_after, entries, "HIGH"

    fb_range = re.search(r"([0-9,]+(?:\.[0-9]+)?)\s*[-/_]\s*([0-9,]+(?:\.[0-9]+)?)", search_slice)
    if fb_range:
        pair = _expand_range_pair(fb_range.group(1), fb_range.group(2))
        if pair:
            abs_start = search_offset + fb_range.start()
            return _mask_span(working_text, abs_start, abs_start + len(fb_range.group(0))), [pair[0], pair[1]], "INFERRED"

    fb_num = re.search(r"[0-9,]+(?:\.[0-9]+)?", search_slice)
    if fb_num:
        val = _parse_number(fb_num.group(0))
        if val is not None:
            abs_start = search_offset + fb_num.start()
            return _mask_span(working_text, abs_start, abs_start + len(fb_num.group(0))), [val], "INFERRED"

    return working_text, [], "NONE"


def _parse_v34_hybrid(message, category="", sl_rules=None):
    """Original V3.4+ hybrid parser — kept intact for Indian Options & Stock Options."""
    raw = str(message or "")
    text = _normalize_text(raw)
    detected_cat = _detect_category(category, text)

    direction = None
    if re.search(r"\b(LONG|BUY)\b", text, re.I):
        direction = "BUY"
    elif re.search(r"\b(SHORT|SELL)\b", text, re.I):
        direction = "SELL"

    symbol = _detect_symbol(detected_cat, text)
    working_text = _mask_symbol_tokens(text, symbol)
    strike_price = None
    option_type = None
    option_anchor_end = -1

    if detected_cat in ("OPTIONS", "STOCK_OPTIONS") or re.search(r"(?:^|[\s_/\-])\d{2,6}\s*(?:CE|PE|CALL|PUT)\b", working_text, re.I):
        symbol, strike_price, option_type, working_text, option_anchor_end = _detect_option_contract(symbol, working_text)
        if not direction and option_type:
            direction = "BUY"

    expiry_match = re.search(r"\b(\d{1,2}\s*" + MONTHS_PATTERN + r"[A-Z]*(?:\s*20\d{2})?)\b", working_text, re.I)
    expiry_date = expiry_match.group(1).upper() if expiry_match else None
    working_text = _mask_noise_tokens(working_text)

    # Stage 2: Explicit SL & Targets
    pip_targets = []
    working_text, stop_loss, sl_status, target_status = _extract_explicit_sl(working_text)
    working_text, targets, target_status = _extract_explicit_targets(working_text, target_status, pip_targets)

    # Stage 3: Entry Extraction
    working_text, entries, entry_conf = _extract_entry(working_text, option_anchor_end)

    if pip_targets and entries and direction:
        base_entry = sum(entries) / len(entries)
        pip_size = forex_pip_size(symbol)
        converted = []
        for p in pip_targets:
            delta = p * pip_size
            t_val = round(base_entry + delta, 5) if direction == "BUY" else round(base_entry - delta, 5)
            converted.append(t_val)
        targets = _clean_targets(targets + converted)

    # Stage 4: Direction-aware inference for remaining unlabeled numbers
    remaining = _extract_numbers(working_text)
    if remaining and entries and direction:
        ref_low = min(entries)
        ref_high = max(entries)
        ref_entry = sum(entries) / len(entries)
        need_targets = (len(targets) == 0 and not target_status)
        need_sl = (stop_loss is None and not sl_status)

        if need_targets or need_sl:
            inf_targets = []
            inf_sl = []
            for n in remaining:
                if ref_entry > 20 and n <= 10:
                    continue
                if direction == "BUY":
                    if n > ref_high:
                        inf_targets.append(n)
                    elif n < ref_low:
                        inf_sl.append(n)
                elif direction == "SELL":
                    if n < ref_low:
                        inf_targets.append(n)
                    elif n > ref_high:
                        inf_sl.append(n)

            if need_targets and inf_targets:
                inf_targets.sort(reverse=(direction == "SELL"))
                targets = _clean_targets(inf_targets)
            if need_sl and len(inf_sl) >= 1:
                stop_loss = inf_sl[-1]

    # Stage 5: Validation & Confidence
    errors = []
    if not symbol:
        errors.append("missing instrument")
    if not direction:
        errors.append("missing direction")
    if not entries:
        errors.append("missing entry")

    ambiguous = False
    if entries and direction:
        ref_low = min(entries)
        ref_high = max(entries)
        if direction == "BUY":
            if stop_loss is not None and stop_loss >= ref_high:
                ambiguous = True
                errors.append("BUY SL >= entry")
            if any(t <= ref_low for t in targets):
                ambiguous = True
                errors.append("BUY target <= entry")
        elif direction == "SELL":
            if stop_loss is not None and stop_loss <= ref_low:
                ambiguous = True
                errors.append("SELL SL <= entry")
            if any(t >= ref_high for t in targets):
                ambiguous = True
                errors.append("SELL target >= entry")

    has_risk_or_target = bool(stop_loss is not None or sl_status or targets or target_status)
    confidence = 0.15 + 0.25 * bool(direction) + 0.25 * bool(symbol) + 0.20 * bool(entries) + 0.15 * has_risk_or_target
    if ambiguous:
        confidence = min(confidence, 0.55)

    if direction and symbol and entries and not ambiguous:
        status = "parsed"
    else:
        status = "needs_review"

    notes = []
    if sl_status:
        notes.append(f"SL={sl_status}")
    if target_status:
        notes.append(f"Targets={target_status}")
    if errors:
        notes.append("Issues: " + ", ".join(errors))
    explanation = (
        f"V3.4+ hybrid parser ({detected_cat or 'AUTO'}): extracted direction, symbol/contract, entry"
        + (f" ({'; '.join(notes)})" if notes else ".")
    )

    result = {
        "category": detected_cat,
        "symbol": symbol,
        "direction": direction,
        "entry_price": entries[0] if entries else None,
        "additional_entries": entries[1:],
        "stop_loss": stop_loss,
        "targets": targets,
        "option_type": option_type,
        "strike_price": strike_price,
        "expiry_date": expiry_date,
        "entry_mode": "limit",
        "status": status,
        "confidence": round(confidence, 2),
        "review": "required" if status != "parsed" else "not_required",
        "explanation": explanation,
    }

    if sl_rules and result["stop_loss"] is None and not ambiguous:
        apply_default_sl(result, category or detected_cat, sl_rules)

    if sl_rules and not result["targets"] and not ambiguous:
        apply_default_targets(result, category or detected_cat, sl_rules)

    return result


# ---------------------------------------------------------------------------
# Dedicated Crypto, Forex & Multi-Trade Block Parsers
# ---------------------------------------------------------------------------

def _strip_leverage_noise(text):
    """Remove ASCII/Unicode leverage tags like 'Cross 20×', 'LEVERAGE : 20x', '20x 50x'."""
    cleaned = re.sub(
        r"(?i)(?:leverage|lev)?\s*[:\-]?\s*(?:cross|isolated)?\s*\d+(?:\.\d+)?\s*[x×X](?:\s*[\-\/,to]+\s*\d+(?:\.\d+)?\s*[x×X])*",
        " ",
        text,
    )
    cleaned = re.sub(r"(?i)\b(?:cross|isolated)\s*\d+(?:\.\d+)?\s*[x×X]?\b", " ", cleaned)
    cleaned = re.sub(r"(?i)\b\d+(?:\.\d+)?\s*[x×X]\b", " ", cleaned)
    return cleaned


def _extract_clean_numbers(text):
    """Extract positive floats from a slice, expanding abbreviated ranges like '1236 - 33'."""
    if not text:
        return []
    range_m = re.search(r"([0-9,]+(?:\.[0-9]+)?)\s*(?:-|/|TO)\s*([0-9,]+(?:\.[0-9]+)?)", text, re.I)
    if range_m:
        pair = _expand_range_pair(range_m.group(1), range_m.group(2))
        if pair:
            rest = _mask_span(text, range_m.start(), range_m.end())
            extras = [n for n in _extract_numbers(rest) if n > 0 and n not in pair]
            return [pair[0], pair[1]] + extras

    out = []
    for n in _extract_numbers(text):
        if n > 0 and n not in out:
            out.append(n)
    return out


def _extract_targets_from_block(block_text):
    """Parse TP1..TPn cleanly without mistaking '1)', '2. ', or 'tp 1st:' for prices, preserving decimals like '2.72'."""
    if not block_text:
        return []
    targets = []
    for line in block_text.splitlines():
        clean_line = line.strip()
        if not clean_line:
            continue
        clean_line = re.sub(
            r"(?i)^(?:take\s+profit\s+targets?|take\s+profit|targets?|tps?|t)\s*(?:\d+(?:st|nd|rd|th)?|1st|2nd|3rd|4th|5th)?\s*[:\-]?\s*",
            "",
            clean_line,
        )
        clean_line = re.sub(r"(?:^|\s)\d{1,2}\s*\)\s*", " ", clean_line)
        clean_line = re.sub(r"^\s*\d{1,2}\s*[\.\:\-]\s+", "", clean_line)
        clean_line = re.sub(r"(?i)\b(?:tp|target)\s*(?:\d+(?:st|nd|rd|th)?|1st|2nd|3rd|4th|5th)\s*[:\-]?\s*", " ", clean_line)
        for num in _extract_clean_numbers(clean_line):
            if num not in targets:
                targets.append(num)
    return _clean_targets(targets)


def _parse_single_crypto_block(raw_block, category="Crypto", sl_rules=None):
    """Dedicated parser for a single Crypto Futures/Spot signal block (untouched)."""
    raw = _normalize_text(raw_block)
    cleaned = _strip_leverage_noise(raw)

    symbol = None
    m_pair = re.search(r"#?([A-Z0-9]{2,15})\s*/\s*(USDT|USDC|BUSD)\b", cleaned, re.I)
    if m_pair:
        symbol = f"{m_pair.group(1).upper()}{m_pair.group(2).upper()}"
    if not symbol:
        m_hash = re.search(r"#([A-Z0-9]{2,15})(?=[.\s,:;\-]|$)", cleaned, re.I)
        if m_hash:
            base = m_hash.group(1).upper()
            symbol = base if base.endswith(("USDT", "USDC", "BUSD")) else f"{base}USDT"
    if not symbol:
        m_usdt = re.search(r"\b([A-Z0-9]{2,15}(?:USDT|USDC|BUSD))\b", cleaned, re.I)
        if m_usdt:
            symbol = m_usdt.group(1).upper()

    direction = None
    if re.search(r"\b(?:SELL|SHORT)\b", cleaned, re.I):
        direction = "SELL"
    elif re.search(r"\b(?:BUY|LONG)\b", cleaned, re.I):
        direction = "BUY"

    entry_price = None
    additional_entries = []
    entry_mode = "limit"

    m_entry_line = re.search(
        r"(?i)(?:take\s+entry\s+now|take\s+entry|entries|entry\s*zone|entry\s*price|entry|buy\s+at|sell\s+at|cmp)[ \t]*[:\-]?[ \t]*([^\n]+)",
        cleaned,
    )
    if m_entry_line:
        entry_seg = m_entry_line.group(1).strip()
        nums = _extract_clean_numbers(entry_seg)
        if nums:
            entry_price = nums[0]
            additional_entries = nums[1:]
        elif re.search(r"(?i)\b(?:now|market|cmp)\b", entry_seg):
            entry_mode = "market_now"

    if entry_price is None and entry_mode != "market_now":
        m_dir_price = re.search(r"(?i)\b(?:buy|sell|long|short)[ \t]*[:\-][ \t]*([^\n]+)", cleaned)
        if m_dir_price:
            nums = _extract_clean_numbers(m_dir_price.group(1))
            if nums:
                entry_price = nums[0]
                additional_entries = nums[1:]

    if entry_price is None and entry_mode != "market_now":
        if re.search(r"(?i)\b(?:buy|sell|long|short|entry)\s*(?:[\-:]\s*)?now\b", cleaned):
            entry_mode = "market_now"
        elif symbol and direction and (
            re.search(r"(?i)\b(?:tp|target|sl|stoploss|stop\s*loss)\b", cleaned)
        ):
            entry_mode = "market_now"

    stop_loss = None
    m_sl = re.search(
        r"(?i)\b(?:stoploss|stop\s*loss|sl)[ \t]*[:\-]?[ \t]*(?:\r?\n[ \t]*)?([0-9,]+(?:\.[0-9]+)?)",
        cleaned,
    )
    if m_sl:
        stop_loss = _parse_number(m_sl.group(1))

    targets = []
    m_tp_sec = re.search(
        r"(?is)\b(?:take\s+profit\s+targets?|take\s+profit|targets?|tps?)\b(.*?)(?=\b(?:stoploss|stop\s*loss|sl|leverage|published|manage\s+your\s+risk)\b|$)",
        cleaned,
    )
    if m_tp_sec:
        targets = _extract_targets_from_block(m_tp_sec.group(1))
    if not targets:
        tp_inline = []
        for m_tp in re.finditer(r"(?im)^\s*(?:tp\s*\d*|target\s*\d*)\s*[:\-]?\s*([^\n]+)$", cleaned):
            tp_inline.extend(_extract_targets_from_block(m_tp.group(1)))
        targets = _clean_targets(tp_inline)

    issues = []
    if not symbol:
        issues.append("missing crypto symbol")
    if not direction:
        issues.append("missing direction")
    if entry_price is None and entry_mode != "market_now":
        issues.append("missing entry")

    status = "parsed" if not issues else "needs_review"
    review = "not_required" if status == "parsed" else "required"
    confidence = 0.94 if (status == "parsed" and entry_price is not None) else (0.86 if status == "parsed" else 0.4)

    expl_parts = [f"Crypto parser: symbol={symbol or 'none'}, dir={direction or 'none'}, entry={entry_price if entry_price is not None else entry_mode}"]
    if stop_loss is not None:
        expl_parts.append(f"SL={stop_loss:g}")
    if targets:
        expl_parts.append(f"TPs={'/'.join(f'{t:g}' for t in targets)}")
    if issues:
        expl_parts.append(f"Issues: {', '.join(issues)}")

    res = {
        "category": "CRYPTO",
        "symbol": symbol,
        "direction": direction,
        "entry_price": entry_price,
        "additional_entries": additional_entries,
        "stop_loss": stop_loss,
        "targets": targets,
        "option_type": None,
        "strike_price": None,
        "expiry_date": None,
        "entry_mode": entry_mode,
        "status": status,
        "confidence": confidence,
        "review": review,
        "explanation": " | ".join(expl_parts) + ".",
    }
    if sl_rules and status == "parsed":
        apply_default_sl(res, category or "Crypto", sl_rules)
        apply_default_targets(res, category or "Crypto", sl_rules)
    return res


def _forex_expand_entry_slice(slice_text):
    """Extract 1 or 2 entry prices from a Forex entry slice (handles '3742 - 3745', '3724/20', '3724 OR 3720', '3754 3756')."""
    if not slice_text:
        return []
    m_rng = re.search(r"([0-9,]+(?:\.[0-9]+)?)\s*(?:-|/|TO|OR)\s*([0-9,]+(?:\.[0-9]+)?)", slice_text, re.I)
    if m_rng:
        raw_a, raw_b = m_rng.group(1), m_rng.group(2)
        a = _parse_number(raw_a)
        b = _parse_number(raw_b)
        if a is not None and b is not None:
            clean_a = raw_a.replace(",", "").strip()
            clean_b = raw_b.replace(",", "").strip()
            if a >= 100 and "." not in clean_a and "." not in clean_b and len(clean_a) > len(clean_b):
                scale = 10 ** len(clean_b)
                base = (int(a) // scale) * scale
                candidates = [base + b, base + scale + b, base - scale + b]
                best = min(candidates, key=lambda c: abs(c - a))
                if abs(best - a) <= a * 0.1:
                    b = best
            return [a] if abs(a - b) < 1e-9 else [a, b]

    nums = [n for n in _extract_numbers(slice_text) if n > 0]
    return list(dict.fromkeys(nums))[:2]


def _forex_parse_target_line(line):
    """Extract target numbers from a Forex target line without consuming bare prices like 'TP 3736'."""
    s = line.strip()
    if not s:
        return []
    # 1. Strip leading index before TP: '1 TP 3739', '1TP 3736', '2. TP 3736'
    s = re.sub(r"^\d{1,2}\s*[\)\.\:\-]?\s*(?=(?:take\s+profit|targets?|tps?)\b)", "", s, flags=re.I)
    # 2. Strip TP / TARGET label + ONLY attached index ('TP1', 'TARGET2'), ordinal ('1st'), or delimited index ('TP 1:')
    s = re.sub(
        r"(?i)^(?:take\s+profit\s+targets?|take\s+profit|targets?|tps?)(?:\d{1,2}|\s+(?:1st|2nd|3rd|\d{1,2}(?:st|nd|rd|th)|\d{1,2}(?=\s*[\)\:\-])))?\s*[\)\:\-]?\s*",
        "",
        s,
    )
    # 3. Strip plain list bullet number at start of line: '1) 3739' or '1. 3739'
    s = re.sub(r"^\d{1,2}\s*(?:\)|[\.\:\-]\s+)", "", s)
    return [n for n in _extract_numbers(s) if n > 0]


def _parse_single_forex_block(raw_block, category="Forex", sl_rules=None):
    """Dedicated parser for Forex & Spot Gold/Silver/Oil signals (isolated from Options, MCX & Crypto)."""
    raw = _normalize_text(raw_block)
    raw = re.sub(r"[→➔➜➜➤➡⇒]+|->", ":", raw)
    raw = re.sub(r"(?i)\b(sl|stoploss|stop\s*loss|tp|target)\s*\.(?=\s*\d)", r"\1:", raw)
    cleaned = _strip_leverage_noise(raw)

    # 1. Symbol extraction (GOLD -> XAUUSD, XAUUSD/GOLD -> XAUUSD, EUR/USD -> EURUSD)
    symbol = None
    m_slash = re.search(r"\b([A-Z]{3})\s*/\s*([A-Z]{3})\b", cleaned, re.I)
    if m_slash:
        pair = f"{m_slash.group(1).upper()}{m_slash.group(2).upper()}"
        symbol = FOREX_SYMBOL_ALIASES.get(pair, pair)

    if not symbol:
        for tok in re.findall(r"\b[A-Z0-9]{3,6}\b", cleaned.upper()):
            if tok in FOREX_SYMBOL_ALIASES:
                symbol = FOREX_SYMBOL_ALIASES[tok]
                break
            if tok in FOREX_PAIRS and not FALSE_FOREX_WORDS.match(tok):
                symbol = tok
                break

    # 2. Direction
    direction = None
    if re.search(r"\b(?:SELL|SHORT)\b", cleaned, re.I):
        direction = "SELL"
    elif re.search(r"\b(?:BUY|LONG)\b", cleaned, re.I):
        direction = "BUY"

    # 3. Extract & mask Stop Loss FIRST so SL prices never leak into targets or entry
    stop_loss = None
    working = cleaned
    m_sl = re.search(
        r"(?i)\b(?:stop\s*sl|stoploss|stop\s*loss|sl)[ \t]*[:\-]?[ \t]*(?:\r?\n[ \t]*)?([0-9,]+(?:\.[0-9]+)?)",
        working,
    )
    if m_sl:
        stop_loss = _parse_number(m_sl.group(1))
        working = _mask_span(working, m_sl.start(), m_sl.end())

    # 4. Entry & entry_mode ('limit' vs 'market_now')
    entry_price = None
    additional_entries = []
    entry_mode = "limit"

    m_entry_line = re.search(
        r"(?i)\b(?:entry\s*zone|entry\s*price|entries|entry|at|@)[ \t]*[:\-]?[ \t]*([^\n]+)",
        working,
    )
    if m_entry_line:
        seg = m_entry_line.group(1).strip()
        nums = _forex_expand_entry_slice(seg)
        if nums:
            entry_price = nums[0]
            additional_entries = nums[1:]
            working = _mask_span(working, m_entry_line.start(), m_entry_line.end())
        elif re.search(r"(?i)\b(?:now|market|cmp)\b", seg):
            entry_mode = "market_now"
            working = _mask_span(working, m_entry_line.start(), m_entry_line.end())

    # Pattern B: 'BUY NOW 3733', 'Sell 3742 - 3745', 'buy 3724/20', 'BUY 3724 OR 3720', 'SELL 3754 3756'
    if entry_price is None:
        m_dir_num = re.search(
            r"(?i)\b(?:buy|sell|long|short)(?:\s+(?:now|limit|stop))?[ \t]*[:\-]?[ \t]*([0-9,]+(?:\.[0-9]+)?(?:[ \t]*(?:-|/|to|or|[ \t])[ \t]*[0-9,]+(?:\.[0-9]+)?)?)",
            working,
        )
        if m_dir_num:
            nums = _forex_expand_entry_slice(m_dir_num.group(1))
            if nums:
                entry_price = nums[0]
                additional_entries = nums[1:]
                working = _mask_span(working, m_dir_num.start(), m_dir_num.end())

    # Pattern C: Price BEFORE direction ('3705 Sell Now')
    if entry_price is None:
        m_num_dir = re.search(
            r"(?i)\b([0-9,]+(?:\.[0-9]+)?(?:[ \t]*(?:-|/|to|or)[ \t]*[0-9,]+(?:\.[0-9]+)?)?)[ \t]+(?:buy|sell|long|short)(?:\s+(?:now|limit|stop))?\b",
            working,
        )
        if m_num_dir:
            nums = _forex_expand_entry_slice(m_num_dir.group(1))
            if nums:
                entry_price = nums[0]
                additional_entries = nums[1:]
                working = _mask_span(working, m_num_dir.start(), m_num_dir.end())

    if entry_price is None:
        if re.search(r"(?i)\b(?:buy|sell|long|short|entry)\s*now\b", working) or (
            symbol and direction and re.search(r"(?i)\b(?:tp|target)\b", working)
        ):
            entry_mode = "market_now"

    # 5. Targets
    targets = []
    # 5a. Multi-line 'Targets:' section with header on its own line
    m_multi_tp = re.search(
        r"(?im)^\s*(?:take\s+profit\s+targets?|take\s+profit|targets?|tps?)\s*[:\-]?\s*$\n([\s\S]*?)(?=\n\s*(?:stop|sl\b|follow|manage|note|published|--)|$)",
        working,
    )
    if m_multi_tp:
        for ln in m_multi_tp.group(1).splitlines():
            for num in _forex_parse_target_line(ln):
                if num not in targets:
                    targets.append(num)

    # 5b. Line-by-line target lines ('TP 3736', '1 TP 3739', '¹TP 3736', 'TARGET1: 3728', 'tp 1st: 3788')
    if not targets:
        for ln in working.splitlines():
            if re.search(r"(?i)(?:^\s*\d{0,2}\s*[\)\.\:\-]?\s*(?:take\s+profit|targets?|tps?)\b|\b(?:tp|target)\s*(?:\d+(?:st|nd|rd|th)?|1st|2nd|3rd|4th|5th)\b)", ln):
                for num in _forex_parse_target_line(ln):
                    if num not in targets:
                        targets.append(num)
    targets = _clean_targets(targets)

    # 6. Direction sanity filter so stray numbers never become invalid TPs
    if entry_price is not None and direction in ("BUY", "SELL"):
        all_ent = [entry_price] + additional_entries
        ref_low, ref_high = min(all_ent), max(all_ent)
        if direction == "BUY":
            targets = [t for t in targets if t > ref_low]
        else:
            targets = [t for t in targets if t < ref_high]

    issues = []
    if not symbol:
        issues.append("missing forex symbol")
    if not direction:
        issues.append("missing direction")
    if entry_price is None and entry_mode != "market_now":
        issues.append("missing entry")

    status = "parsed" if not issues else "needs_review"
    review = "not_required" if status == "parsed" else "required"
    confidence = 0.94 if (status == "parsed" and entry_price is not None) else (0.86 if status == "parsed" else 0.4)

    entry_disp = (
        "/".join(f"{e:g}" for e in ([entry_price] + additional_entries))
        if entry_price is not None
        else entry_mode
    )
    expl_parts = [f"Forex parser: symbol={symbol or 'none'}, dir={direction or 'none'}, entry={entry_disp}"]
    if stop_loss is not None:
        expl_parts.append(f"SL={stop_loss:g}")
    if targets:
        expl_parts.append(f"TPs={'/'.join(f'{t:g}' for t in targets)}")
    if issues:
        expl_parts.append(f"Issues: {', '.join(issues)}")

    res = {
        "category": "FOREX",
        "symbol": symbol,
        "direction": direction,
        "entry_price": entry_price,
        "additional_entries": additional_entries,
        "stop_loss": stop_loss,
        "targets": targets,
        "option_type": None,
        "strike_price": None,
        "expiry_date": None,
        "entry_mode": entry_mode,
        "status": status,
        "confidence": confidence,
        "review": review,
        "explanation": " | ".join(expl_parts) + ".",
    }
    if sl_rules and status == "parsed":
        apply_default_sl(res, category or "Forex", sl_rules)
        apply_default_targets(res, category or "Forex", sl_rules)
    return res

def _parse_single_mcx_block(block, category="MCX", sl_rules=None):
    """Parse a single MCX futures trade block (untouched)."""
    comm_pattern = "|".join(sorted(MCX_COMMODITY_ALIASES.keys(), key=len, reverse=True))
    symbol = None
    m_comm = re.search(rf"\b({comm_pattern})\b", block, re.I)
    if m_comm:
        symbol = MCX_COMMODITY_ALIASES[m_comm.group(1).upper()]

    direction = None
    if re.search(r"\b(?:SELL|SHORT)\b", block, re.I):
        direction = "SELL"
    elif re.search(r"\b(?:BUY|LONG)\b", block, re.I):
        direction = "BUY"

    entry_price = None
    additional_entries = []
    entry_mode = "limit"

    m_hdr = re.search(
        rf"(?i)\b(?:buy|sell|long|short)\s+(?:mcx\s+)?(?:{comm_pattern})[ \t]*[:\-]?[ \t]*([^\n]+)",
        block,
    )
    if m_hdr:
        nums = _extract_clean_numbers(m_hdr.group(1))
        if nums:
            entry_price = nums[0]
            additional_entries = nums[1:]

    if entry_price is None:
        m_ent = re.search(r"(?i)\b(?:entry|at|cmp|near)[ \t]*[:\-]?[ \t]*([^\n]+)", block)
        if m_ent:
            nums = _extract_clean_numbers(m_ent.group(1))
            if nums:
                entry_price = nums[0]
                additional_entries = nums[1:]

    if entry_price is None and re.search(r"(?i)\b(?:buy|sell)\s*now\b", block):
        entry_mode = "market_now"

    stop_loss = None
    m_sl = re.search(
        r"(?i)\b(?:stoploss|stop\s*loss|sl)[ \t]*[:\-]?[ \t]*(?:\r?\n[ \t]*)?([0-9,]+(?:\.[0-9]+)?)",
        block,
    )
    if m_sl:
        stop_loss = _parse_number(m_sl.group(1))

    targets = []
    for m_tp in re.finditer(
        r"(?im)^\s*(?:targets?|tps?)\s*(?:\d+(?:st|nd|rd|th)?)?\s*[:\-]?\s*([^\n]+)$",
        block,
    ):
        for num in _extract_targets_from_block(m_tp.group(1)):
            if num not in targets:
                targets.append(num)
    targets = _clean_targets(targets)

    issues = []
    if not symbol:
        issues.append("missing MCX commodity")
    if not direction:
        issues.append("missing direction")
    if entry_price is None and entry_mode != "market_now":
        issues.append("missing entry")

    status = "parsed" if not issues else "needs_review"
    review = "not_required" if status == "parsed" else "required"
    confidence = 0.93 if status == "parsed" else 0.4

    expl_parts = [f"MCX parser: symbol={symbol or 'none'}, dir={direction or 'none'}, entry={entry_price if entry_price is not None else entry_mode}"]
    if stop_loss is not None:
        expl_parts.append(f"SL={stop_loss:g}")
    if targets:
        expl_parts.append(f"TPs={'/'.join(f'{t:g}' for t in targets)}")
    if issues:
        expl_parts.append(f"Issues: {', '.join(issues)}")

    res = {
        "category": "MCX",
        "symbol": symbol,
        "direction": direction,
        "entry_price": entry_price,
        "additional_entries": additional_entries,
        "stop_loss": stop_loss,
        "targets": targets,
        "option_type": None,
        "strike_price": None,
        "expiry_date": None,
        "entry_mode": entry_mode,
        "status": status,
        "confidence": confidence,
        "review": review,
        "explanation": " | ".join(expl_parts) + ".",
    }
    if sl_rules and status == "parsed":
        apply_default_sl(res, category or "MCX", sl_rules)
        apply_default_targets(res, category or "MCX", sl_rules)
    return res


def _split_multi_trade_blocks(raw_text, header_re):
    """Split a message into 2..10 trade blocks when multiple signal headers are present."""
    matches = list(header_re.finditer(raw_text))
    if not (2 <= len(matches) <= 10):
        return []
    blocks = []
    for idx, m in enumerate(matches):
        start = m.start()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(raw_text)
        chunk = raw_text[start:end].strip()
        if chunk:
            blocks.append(chunk)
    return blocks


def _parse_mcx_signals(message, category="MCX", sl_rules=None):
    """Split multi-commodity MCX messages (e.g. Copper + Zinc + Aluminium) into separate signals (untouched)."""
    raw = _normalize_text(message)
    if re.search(r"\b(?:CE|PE|CALL|PUT)\b", raw, re.I):
        return [_parse_v34_hybrid(message, category=category, sl_rules=sl_rules)]

    comm_pattern = "|".join(sorted(MCX_COMMODITY_ALIASES.keys(), key=len, reverse=True))
    header_re = re.compile(
        rf"(?im)^[ \t]*(?:buy|sell|long|short)\s+(?:mcx\s+)?(?:{comm_pattern})\b"
    )
    blocks = _split_multi_trade_blocks(raw, header_re)
    if blocks:
        parsed_blocks = [_parse_single_mcx_block(b, category=category, sl_rules=sl_rules) for b in blocks]
        if sum(1 for x in parsed_blocks if x.get("symbol") and x.get("direction")) >= 2:
            return parsed_blocks

    return [_parse_single_mcx_block(raw, category=category, sl_rules=sl_rules)]


def _parse_crypto_signals(message, category="Crypto", sl_rules=None):
    """Support single or multi-coin Crypto messages (untouched)."""
    raw = _normalize_text(message)
    header_re = re.compile(
        r"(?im)^[ \t]*(?:(?:#[A-Z0-9]{2,15}(?:\s*/\s*(?:USDT|USDC|BUSD))?|[A-Z0-9]{2,15}\s*/\s*(?:USDT|USDC|BUSD)|[A-Z0-9]{2,15}(?:USDT|USDC|BUSD))\b|(?:buy|sell|long|short)\s+#?[A-Z0-9]{2,15}(?:\s*/\s*(?:USDT|USDC|BUSD)|USDT|USDC|BUSD)?\b)"
    )
    blocks = _split_multi_trade_blocks(raw, header_re)
    if blocks:
        parsed_blocks = [_parse_single_crypto_block(b, category=category, sl_rules=sl_rules) for b in blocks]
        if sum(1 for x in parsed_blocks if x.get("symbol") and x.get("direction")) >= 2:
            return parsed_blocks

    return [_parse_single_crypto_block(message, category=category, sl_rules=sl_rules)]


def _parse_forex_signals(message, category="Forex", sl_rules=None):
    """Support single or multi-pair Forex messages (2..10 trades in one message)."""
    raw = _normalize_text(message)
    fx_tokens = sorted(set(FOREX_SYMBOL_ALIASES.keys()) | FOREX_PAIRS, key=len, reverse=True)
    fx_pattern = "|".join(re.escape(t) for t in fx_tokens)
    header_re = re.compile(
        rf"(?im)^[ \t]*#?(?:(?:buy|sell|long|short)\s+(?:now\s+|limit\s+|stop\s+)?(?:{fx_pattern})\b|(?:{fx_pattern})(?:\s*/\s*(?:usd|gold|xauusd))?\s+(?:buy|sell|long|short)\b)"
    )
    blocks = _split_multi_trade_blocks(raw, header_re)
    if blocks:
        parsed_blocks = [_parse_single_forex_block(b, category=category, sl_rules=sl_rules) for b in blocks]
        if sum(1 for x in parsed_blocks if x.get("symbol") and x.get("direction")) >= 2:
            return parsed_blocks

    return [_parse_single_forex_block(message, category=category, sl_rules=sl_rules)]


def parse_signals(message, category="", sl_rules=None):
    """Route signals to dedicated Crypto, Forex, MCX, or untouched V3.4+ Indian/Stock Options parser."""
    cat_norm = (category or "").strip().lower()
    if not cat_norm:
        cat_norm = _detect_category("", _normalize_text(message)).lower()

    if cat_norm == "crypto":
        return _parse_crypto_signals(message, category=category or "Crypto", sl_rules=sl_rules)
    if cat_norm in ("forex", "fx"):
        return _parse_forex_signals(message, category=category or "Forex", sl_rules=sl_rules)
    if cat_norm == "mcx":
        return _parse_mcx_signals(message, category=category or "MCX", sl_rules=sl_rules)

    # Indian Options, Stock Options, and any other category stay 100% on V3.4+ hybrid parser
    return [_parse_v34_hybrid(message, category=category, sl_rules=sl_rules)]


def parse_signal(message, category="", sl_rules=None):
    """Single-signal wrapper for callers expecting one dict."""
    items = parse_signals(message, category=category, sl_rules=sl_rules)
    return items[0]