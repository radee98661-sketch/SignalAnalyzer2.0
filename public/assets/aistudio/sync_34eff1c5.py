import json, threading, time
from urllib.request import Request,urlopen
from .database import SOURCE_COLUMNS, utcnow
from .parser import parse_signal

def payload_shape(value, depth=0):
    """Safe diagnostic: report JSON shape, never notification values or secrets."""
    if depth >= 2: return type(value).__name__
    if isinstance(value, dict): return {str(k): payload_shape(v, depth + 1) for k, v in list(value.items())[:20]}
    if isinstance(value, list): return {"array_length": len(value), "first_item": payload_shape(value[0], depth + 1) if value else None}
    return type(value).__name__

APPS_SCRIPT_COLUMNS = {
    "Received At": "received_at", "Notification Time": "notification_time",
    "Source App": "source_app", "Package": "package", "Channel": "channel",
    "Category": "category", "Title": "title", "Message": "message",
    "Notification ID": "notification_id", "Delivery ID": "delivery_id",
}

def apps_script_row(row):
    """Translate the supplied lower_snake_case Apps Script fields verbatim."""
    return {column: row.get(field, "") for column, field in APPS_SCRIPT_COLUMNS.items()}

class SyncWorker:
    def __init__(self,db): self.db=db; self.stop_event=threading.Event(); self.thread=threading.Thread(target=self._loop,daemon=True,name="sheet-sync")
    def start(self): self.thread.start()
    def _loop(self):
        while not self.stop_event.wait(max(5,int(self.db.settings().get("sync_interval_seconds","30")))):
            try:
                cfg=self.db.settings()
                if cfg.get("google_sheets_auto_sync","true").lower()=="true": sync_google_sheets(self.db)
            except Exception: pass
def sync_google_sheets(db):
    with db.connection() as c:
        integration=c.execute("SELECT * FROM integrations WHERE provider='google_sheets'").fetchone()
        log_id=c.execute("INSERT INTO synchronization_logs(started_at,provider,status) VALUES(?,?,?)",(utcnow(),"google_sheets","running")).lastrowid
    imported=duplicates=skipped=failed=0
    try:
        if not integration: raise ValueError("Google Sheets endpoint is not configured; demo mode does not fabricate data.")
        config=json.loads(integration["config_json"]); url=config.get("apps_script_url","")
        if not url: raise ValueError("Apps Script URL is not configured.")
        token=json.loads(integration["secrets_json"]).get("access_token","")
        headers={"User-Agent":"SignalAnalyzer/1.0"}
        if token: headers["Authorization"]="Bearer "+token
        req=Request(url,headers=headers)
        with urlopen(req,timeout=20) as r: payload=json.load(r)
        # Supports the documented Apps Script envelope supplied by the user:
        # {ok, server_time_ms, next_since_ms, total_new, data:{worksheet:[snake_case rows]}}.
        if isinstance(payload,dict) and isinstance(payload.get("data"),dict):
            if payload.get("ok") is False:
                # The Apps Script response explicitly supplies this error; keep it bounded
                # so configuration mistakes are actionable without logging payload data.
                reason=str(payload.get("error") or "no error detail supplied")[:500]
                raise ValueError("Apps Script reported an unsuccessful response: " + reason)
            worksheets=payload["data"]; endpoint_cursor=payload.get("next_since_ms")
            source_format="apps_script_data"
        elif isinstance(payload,dict) and isinstance(payload.get("worksheets"),dict):
            worksheets=payload["worksheets"]; endpoint_cursor=None; source_format="worksheets"
        else:
            raise ValueError("Unsupported endpoint response. Safe response shape: " + json.dumps(payload_shape(payload), separators=(",", ":")))
        mapping=config.get("column_mapping") or {}
        for worksheet,worksheet_rows in worksheets.items():
            if not isinstance(worksheet_rows,list): skipped += 1; continue
            for row in worksheet_rows:
                if source_format == "apps_script_data" and isinstance(row,dict): row=apps_script_row(row)
                elif mapping and isinstance(row,dict): row={target:row.get(source,"") for target,source in mapping.items()}
                if not isinstance(row,dict) or not all(k in row for k in SOURCE_COLUMNS): skipped+=1; continue
                try:
                    _,status=db.ingest(row,parse_signal(row["Message"],row["Category"])); imported += status=="imported"; duplicates += status=="duplicate"
                except Exception: failed+=1
        if endpoint_cursor is not None: db.set_settings({"google_sheets_next_since_ms":endpoint_cursor})
        status="success"; detail=f"Completed {source_format} import." + (f" Endpoint cursor saved: {endpoint_cursor}." if endpoint_cursor is not None else "")
    except Exception as e: status="failed"; detail=str(e)
    with db.connection() as c:
        c.execute("UPDATE synchronization_logs SET finished_at=?,status=?,imported_rows=?,duplicate_rows=?,skipped_rows=?,failed_rows=?,detail=? WHERE id=?",(utcnow(),status,imported,duplicates,skipped,failed,detail,log_id)); db.log(c,"INFO" if status=="success" else "ERROR","sync",detail)
    return {"status":status,"imported":imported,"duplicates":duplicates,"skipped":skipped,"failed":failed,"detail":detail}
