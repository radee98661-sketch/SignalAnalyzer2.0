import base64, hashlib, hmac, json, time, socket
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

class ProviderError(Exception): pass

_DOH_CACHE = {}

def _resolve_doh_ipv4(host):
    """Resolve host via Cloudflare DNS-over-HTTPS (1.1.1.1) to bypass ISP DNS sinkholes."""
    if host in _DOH_CACHE:
        return _DOH_CACHE[host]
    try:
        doh_url = f"https://1.1.1.1/dns-query?name={host}&type=A"
        req = Request(doh_url, headers={"Accept": "application/dns-json", "User-Agent": "SignalAnalyzer/1.0"})
        with urlopen(req, timeout=5) as r:
            data = json.load(r)
        ips = [ans["data"] for ans in data.get("Answer", []) if ans.get("type") == 1]
        if ips:
            _DOH_CACHE[host] = ips[-1]
            return ips[-1]
    except Exception:
        pass
    return None


def _urlopen_ipv4(req, timeout=15):
    """Resolve via Cloudflare DoH (1.1.1.1) with IPv4 fallback to bypass ISP DNS blocks on Binance."""
    orig_getaddrinfo = socket.getaddrinfo

    def doh_getaddrinfo(host, port, family=0, type=0, proto=0, flags=0):
        if host and "binance" in host.lower():
            clean_ip = _resolve_doh_ipv4(host)
            if clean_ip:
                return orig_getaddrinfo(clean_ip, port, socket.AF_INET, type, proto, flags)
        return orig_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

    socket.getaddrinfo = doh_getaddrinfo
    try:
        return urlopen(req, timeout=timeout)
    finally:
        socket.getaddrinfo = orig_getaddrinfo


class TwelveData:
    name="twelve_data"; intervals={"1min","5min","15min","1h","4h","1day"}
    def fetch(self,symbol,interval,start,end,config,api_key):
        if not api_key: raise ProviderError("Twelve Data API key is not configured.")
        if interval not in self.intervals: raise ProviderError("Unsupported Twelve Data interval.")
        params={"symbol":symbol,"interval":interval,"apikey":api_key,"format":"JSON","timezone":config.get("timezone","UTC"),"order":"ASC"}
        if start: params["start_date"]=start.strftime("%Y-%m-%d %H:%M:%S")
        if end: params["end_date"]=end.strftime("%Y-%m-%d %H:%M:%S")
        if not(start and end): params["outputsize"]=min(max(int(config.get("outputsize",5000)),1),5000)
        try:
            with urlopen(Request((config.get("base_url") or "https://api.twelvedata.com").rstrip("/")+"/time_series?"+urlencode(params),headers={"User-Agent":"SignalAnalyzer/1.0"}),timeout=30) as r: payload=json.load(r); headers=dict(r.headers.items())
        except HTTPError as e: raise ProviderError(f"Twelve Data HTTP {e.code}: {e.read().decode('utf-8','replace')[:300]}")
        except Exception as e: raise ProviderError(f"Twelve Data request failed: {e}")
        if payload.get("status")=="error": raise ProviderError(payload.get("message","Twelve Data returned an error."))
        out=[]
        for x in payload.get("values",[]):
            try:
                t=datetime.fromisoformat(x["datetime"].replace("Z","+00:00"));t=t.replace(tzinfo=timezone.utc) if t.tzinfo is None else t.astimezone(timezone.utc)
                out.append({"provider":"twelve_data","symbol":symbol,"exchange":"FOREX","interval":interval,"candle_timestamp":t.isoformat(),"open":float(x["open"]),"high":float(x["high"]),"low":float(x["low"]),"close":float(x["close"]),"volume":float(x.get("volume") or 0)})
            except (KeyError,TypeError,ValueError): pass
        return out,headers


class BinanceFutures:
    name="binance"
    intervals={"1m","3m","5m","15m","30m","1h","2h","4h","1d"}
    DEFAULT_ENDPOINTS = [
        ("https://fapi.binance.com", "/fapi/v1/klines", "USD-M"),
        ("https://fapi1.binance.com", "/fapi/v1/klines", "USD-M"),
        ("https://fapi2.binance.com", "/fapi/v1/klines", "USD-M"),
        ("https://fapi3.binance.com", "/fapi/v1/klines", "USD-M"),
        ("https://fapi4.binance.com", "/fapi/v1/klines", "USD-M"),
        ("https://data-api.binance.vision", "/api/v3/klines", "SPOT_MIRROR"),
    ]

    def _candidate_endpoints(self, config):
        custom = (config or {}).get("base_url", "").strip().rstrip("/")
        if custom:
            path = "/api/v3/klines" if "binance.vision" in custom else "/fapi/v1/klines"
            exch = "SPOT_MIRROR" if "binance.vision" in custom else "USD-M"
            return [(custom, path, exch)] + [ep for ep in self.DEFAULT_ENDPOINTS if ep[0] != custom]
        return list(self.DEFAULT_ENDPOINTS)

    def fetch(self,symbol,interval,start,end,config):
        if interval not in self.intervals: raise ProviderError("Unsupported Binance USD-M interval.")
        endpoints = self._candidate_endpoints(config)
        cursor=int(start.timestamp()*1000); finish=int(end.timestamp()*1000); out=[]
        active_ep = None
        req_headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept": "application/json",
        }

        while cursor<=finish:
            limit = 1000 if (active_ep and active_ep[2] == "SPOT_MIRROR") else 1500
            rows=None; last_error=None; tried_hosts=[]

            candidates = [active_ep] if active_ep else endpoints
            for base, path, exch in candidates:
                tried_hosts.append(base)
                q = urlencode({"symbol":symbol.upper(),"interval":interval,"startTime":cursor,"endTime":finish,"limit":limit})
                for attempt in range(2):
                    try:
                        with _urlopen_ipv4(Request(base + path + "?" + q, headers=req_headers), timeout=15) as r:
                            rows=json.load(r)
                        active_ep = (base, path, exch)
                        break
                    except HTTPError as e:
                        if e.code < 500 and e.code != 429:
                            raise ProviderError(f"Binance HTTP {e.code}: {e.read().decode('utf-8','replace')[:300]}")
                        last_error=f"HTTP {e.code}"
                    except (URLError, socket.timeout, TimeoutError) as e:
                        last_error=str(e)
                    except Exception as e:
                        last_error=str(e)
                    if attempt < 1: time.sleep(0.5)
                if rows is not None:
                    break

            if rows is None:
                raise ProviderError(f"Binance endpoints did not respond ({last_error}). Tried: {', '.join(tried_hosts)}.")
            if not isinstance(rows,list): raise ProviderError("Unexpected Binance Kline response.")
            if not rows: break

            exch_label = active_ep[2] if active_ep else "USD-M"
            out.extend({
                "provider":"binance",
                "symbol":symbol.upper(),
                "exchange":exch_label,
                "interval":interval,
                "candle_timestamp":datetime.fromtimestamp(x[0]/1000,timezone.utc).isoformat(),
                "open":float(x[1]),
                "high":float(x[2]),
                "low":float(x[3]),
                "close":float(x[4]),
                "volume":float(x[5])
            } for x in rows)

            next_cursor=int(rows[-1][0])+1
            if next_cursor<=cursor or len(rows)<limit: break
            cursor=next_cursor; time.sleep(.08)
        return out


def _totp(secret):
    clean="".join((secret or "").upper().split()).replace("-", "")
    if not clean: raise ProviderError("TOTP secret is required.")
    try: key=base64.b32decode(clean+"="*((8-len(clean)%8)%8))
    except Exception: raise ProviderError("TOTP secret is not valid base32.")
    digest=hmac.new(key,int(time.time()//30).to_bytes(8,"big"),hashlib.sha1).digest(); offset=digest[-1]&15
    return str((((digest[offset]&127)<<24)|(digest[offset+1]<<16)|(digest[offset+2]<<8)|digest[offset+3])%1000000).zfill(6)


class AngelOneProvider:
    name="angel_one"; base_url="https://apiconnect.angelone.in"; intervals={"ONE_MINUTE","THREE_MINUTE","FIVE_MINUTE","TEN_MINUTE","FIFTEEN_MINUTE","THIRTY_MINUTE","ONE_HOUR","ONE_DAY"}
    def _headers(self,key,token=None):
        h={"Content-Type":"application/json","Accept":"application/json","X-UserType":"USER","X-SourceID":"WEB","X-PrivateKey":key,"X-ClientLocalIP":"127.0.0.1","X-ClientPublicIP":"127.0.0.1","X-MACAddress":"00:00:00:00:00:00","User-Agent":"SignalAnalyzer/1.0"}
        if token:h["Authorization"]="Bearer "+token
        return h
    def authenticate(self,secrets):
        if any(not secrets.get(k) for k in ("api_key","client_id","password","totp_secret")): raise ProviderError("Angel One API key, Client ID, password/PIN, and TOTP secret are required.")
        body=json.dumps({"clientcode":secrets["client_id"],"password":secrets["password"],"totp":_totp(secrets["totp_secret"])}).encode()
        try:
            with urlopen(Request(self.base_url+"/rest/auth/angelbroking/user/v1/loginByPassword",data=body,headers=self._headers(secrets["api_key"]),method="POST"),timeout=25) as r: payload=json.load(r)
        except HTTPError as e: raise ProviderError(f"Angel One authentication HTTP {e.code}: {e.read().decode('utf-8','replace')[:300]}")
        except Exception as e: raise ProviderError(f"Angel One authentication failed: {e}")
        data=payload.get("data") or {}
        if not payload.get("status") or not data.get("jwtToken"): raise ProviderError(payload.get("message") or "Angel One rejected authentication.")
        return {"jwt_token":data["jwtToken"],"authenticated_at":datetime.now(timezone.utc).isoformat()}
 
    def search_scrip(self, token, key, exchange, search_symbol):
        body = json.dumps({"exchange": exchange, "searchsymbol": search_symbol}).encode()
        try:
            with urlopen(
                Request(
                    self.base_url + "/rest/secure/angelbroking/order/v1/searchScrip",
                    data=body,
                    headers=self._headers(key, token),
                    method="POST",
                ),
                timeout=20,
            ) as r:
                response = json.load(r)
        except HTTPError as e:
            raise ProviderError(f"Angel One searchScrip HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")
        except Exception as e:
            raise ProviderError(f"Angel One searchScrip failed: {e}")
        if not response.get("status"):
            raise ProviderError(response.get("message") or "Angel One searchScrip returned no data.")
        return response.get("data") or []

    def fetch(self,token,key,exchange,symboltoken,interval,start,end):
        if interval not in self.intervals: raise ProviderError("Unsupported Angel One interval.")
        payload={"exchange":exchange,"symboltoken":str(symboltoken),"interval":interval,"fromdate":start.strftime("%Y-%m-%d %H:%M"),"todate":end.strftime("%Y-%m-%d %H:%M")}
        try:
            with urlopen(Request(self.base_url+"/rest/secure/angelbroking/historical/v1/getCandleData",data=json.dumps(payload).encode(),headers=self._headers(key,token),method="POST"),timeout=30) as r: response=json.load(r)
        except HTTPError as e: raise ProviderError(f"Angel One candle HTTP {e.code}: {e.read().decode('utf-8','replace')[:300]}")
        except Exception as e: raise ProviderError(f"Angel One candle request failed: {e}")
        if not response.get("status"): raise ProviderError(response.get("message") or "Angel One returned no candle data.")
        out=[]
        for x in response.get("data") or []:
            try:
                stamp=datetime.fromisoformat(str(x[0]).replace("Z","+00:00")); stamp=stamp.replace(tzinfo=timezone.utc) if stamp.tzinfo is None else stamp.astimezone(timezone.utc)
                out.append({"provider":"angel_one","symbol":str(symboltoken),"exchange":exchange,"interval":interval,"candle_timestamp":stamp.isoformat(),"open":float(x[1]),"high":float(x[2]),"low":float(x[3]),"close":float(x[4]),"volume":float(x[5] or 0)})
            except (IndexError,TypeError,ValueError): continue
        return out