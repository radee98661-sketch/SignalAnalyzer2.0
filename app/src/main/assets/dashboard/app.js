const $ = s => document.querySelector(s);

// Session token lives strictly in browser memory — never localStorage, sessionStorage, or URL.
let authSessionToken = '';
let eventSocket = null;
let wsReconnectTimer = null;
let wsRefreshTimer = null;
let currentView = 'overview';

function showAuthModal(errMsg = '') {
  const overlay = $('#lan-auth-overlay');
  if (overlay) overlay.classList.remove('hidden');
  if ($('#lan-auth-error')) $('#lan-auth-error').textContent = errMsg;
  if ($('#lock-session-btn')) $('#lock-session-btn').style.display = 'none';
  if ($('#health')) $('#health').textContent = 'Locked — Pair with Android device';
  disconnectWebSocket();
}

function hideAuthModal() {
  const overlay = $('#lan-auth-overlay');
  if (overlay) overlay.classList.add('hidden');
  if ($('#lan-auth-error')) $('#lan-auth-error').textContent = '';
  if ($('#lock-session-btn')) $('#lock-session-btn').style.display = 'inline-block';
}

function api(u, o = {}) {
  const headers = {
    'Content-Type': 'application/json',
    'X-Requested-With': 'SignalAnalyzerDashboard',
    ...(o.headers || {}),
  };
  if (authSessionToken) {
    headers['Authorization'] = 'Bearer ' + authSessionToken;
  }
  return fetch(u, { ...o, headers }).then(async r => {
    let x = {};
    try { x = await r.json(); } catch (_) {}
    if (r.status === 401) {
      authSessionToken = '';
      showAuthModal(x.error || 'Session expired. Re-enter access token.');
      throw Error(x.error || 'Authentication required');
    }
    if (!r.ok) throw Error(x.error || x.detail || `Request failed (${r.status})`);
    return x;
  });
}

const esc = x => String(x ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function disconnectWebSocket() {
  if (wsReconnectTimer) { clearTimeout(wsReconnectTimer); wsReconnectTimer = null; }
  if (eventSocket) {
    try { eventSocket.close(); } catch (_) {}
    eventSocket = null;
  }
}

function connectWebSocket() {
  disconnectWebSocket();
  if (!authSessionToken) return;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${proto}//${location.host}/ws/events`);
  eventSocket = ws;
  ws.onopen = () => {
    ws.send(JSON.stringify({ type: 'auth', token: authSessionToken }));
  };
  ws.onmessage = ev => {
    try {
      const msg = JSON.parse(ev.data);
      if (msg.type === 'auth_ok') {
        $('#health').textContent = 'Connected (Live LAN sync)';
        return;
      }
      if (msg.type === 'auth_error') {
        authSessionToken = '';
        showAuthModal('WebSocket authorization failed.');
        return;
      }
      // Coalesce rapid updates into a single refresh of the active view
      if (['signal.changed', 'sync.completed', 'schedule.changed', 'market.cache.changed', 'api.health.changed'].includes(msg.type)) {
        clearTimeout(wsRefreshTimer);
        wsRefreshTimer = setTimeout(() => {
          loadApiHealth().catch(() => {});
          if (!$('#parse-review-dialog')?.open) {
            refreshActiveView();
          }
        }, 400);
      }
    } catch (_) {}
  };
  ws.onclose = () => {
    if (authSessionToken) {
      $('#health').textContent = 'Reconnecting live stream...';
      wsReconnectTimer = setTimeout(connectWebSocket, 3500);
    }
  };
}

function refreshActiveView() {
  if (!authSessionToken) return;
  if (currentView === 'overview') overview().catch(() => {});
  else if (currentView === 'signals') loadSignals().catch(() => {});
  else if (currentView === 'notifications') notificationLog().catch(() => {});
  else if (currentView === 'market') { loadApiHealth().catch(() => {}); coverage().catch(() => {}); }
  else if (currentView === 'analytics') loadAnalytics().catch(() => {});
  else if (currentView === 'logs') loadLogs().catch(() => {});
}

async function busy(button, label, task) {
  let original = button.textContent;
  button.disabled = true;
  button.textContent = label;
  try {
    return await task();
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

function fmtDateTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  return d.toLocaleString('en-IN', {
    day: '2-digit', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: true
  });
}

function fmtShortTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso);
  return d.toLocaleString('en-IN', {
    day: '2-digit', month: 'short',
    hour: '2-digit', minute: '2-digit', hour12: false
  });
}

function fmtPnl(amount, currency = 'INR') {
  const num = Number(amount || 0);
  const sym = currency === 'USD' ? '$' : '₹';
  const sign = num > 0 ? '+' : num < 0 ? '-' : '';
  const absStr = Math.abs(num).toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
  return `${sign}${sym}${absStr}`;
}

function table(d, f) {
  return d.length
    ? `<table><thead><tr>${f.map(x => `<th>${esc(x)}</th>`).join('')}</tr></thead><tbody>${d.map(r => `<tr>${f.map(x => `<td>${esc(Array.isArray(r[x]) ? r[x].join(', ') : r[x])}</td>`).join('')}</tr>`).join('')}</tbody></table>`
    : '<p class="muted">No data yet.</p>';
}

function bars(d) {
  const maxVal = Math.max(1, ...d.map(x => Number(x.value || 0)));
  return d.length
    ? d.map(x => `<div><span class="bar-label"><b>${esc(x.name)}</b><b>${esc(x.value)}</b></span><span class="bar"><i style="width:${Math.min(100, Math.round((Number(x.value || 0) / maxVal) * 100))}%"></i></span></div>`).join('')
    : '<p class="muted">No data yet.</p>';
}

let sf = { category: '', channel: '', status: '', tp_filter: '', pnl_basis: 'tp1', date_from: '', date_to: '', notification_filter: 'primary' };
let af = { category: '', channel: '', date_from: '', date_to: '', pnl_basis: 'tp1' };
let selectedSignals = new Set();
let expandedSignals = new Set();
let chartCache = new Map();

function statusBadge(status, isManualNeeded, isAutoScheduled, closedBySlAfterTp = false, maxTpHit = 0) {
  if (status === 'target_hit' && closedBySlAfterTp) {
    return `<span class="pill badge-green">TP${Math.max(1, maxTpHit)} ✓ → SL Closed</span>`;
  }
  if (status === 'target_hit' && maxTpHit >= 1) {
    return `<span class="pill badge-green">TP${maxTpHit} Hit</span>`;
  }
  const map = {
    target_hit: ['TP Hit', 'badge-green'],
    stop_loss_hit: ['SL Hit', 'badge-red'],
    entry_hit: ['Entry Hit', 'badge-blue'],
    ambiguous: ['Ambiguous', 'badge-orange'],
    awaiting_market_data: ['Awaiting Data', 'badge-orange'],
    historical_data_missing: ['No Historical Data', 'badge-muted'],
    pending: [
      isManualNeeded ? 'Review Needed' : isAutoScheduled ? 'Monitoring' : 'Pending',
      isManualNeeded ? 'badge-orange' : isAutoScheduled ? 'badge-blue' : 'badge-muted'
    ],
  };
  const [label, cls] = map[status] || [status || 'pending', 'badge-muted'];
  return `<span class="pill ${cls}">${esc(label)}</span>`;
}

function phaseBadge(phase) {
  const map = {
    idle_15m: ['Idle 15m', ''],
    fast_15m: ['15m Fast', ''],
    hourly_1h: ['1h Monitor', 'phase-hourly'],
    completed: ['Auto-Stopped', 'phase-done'],
    expired: ['Expired', 'phase-done'],
  };
  if (!map[phase]) return '';
  const [lbl, cls] = map[phase];
  return `<span class="phase-pill ${cls}">${esc(lbl)}</span>`;
}

function levelPnlStripHtml(r, basis = 'tp1') {
  const cur = r.pnl_currency || 'INR';
  const levels = Array.isArray(r.tp_levels) && r.tp_levels.length ? r.tp_levels.slice(0, 3) : [];
  const chips = levels.map(lv => {
    const isBasis = (basis === `tp${lv.level}`) || (basis === 'highest_tp' && lv.level === r.max_tp_hit && lv.hit);
    const cls = `${lv.hit ? 'lvl-hit' : ''} ${isBasis ? 'lvl-basis' : ''}`.trim();
    return `<span class="lvl-chip ${cls}">${esc(lv.label)}${lv.hit ? ' ✓' : ''}: ${esc(fmtPnl(lv.money_pnl, cur))}</span>`;
  });
  if (r.stop_loss != null || Number(r.sl_pnl_amount || 0) !== 0) {
    const slHit = r.verification_status === 'stop_loss_hit';
    const slAfter = Boolean(r.closed_by_sl_after_tp);
    const slCls = slHit ? 'lvl-sl-hit' : slAfter ? 'lvl-sl-after' : 'lvl-sl-risk';
    const slLbl = slHit ? 'SL ✗' : slAfter ? 'SL* Closed' : 'SL Risk';
    chips.push(`<span class="lvl-chip ${slCls}">${slLbl}: ${esc(fmtPnl(r.sl_pnl_amount, cur))}</span>`);
  }
  return chips.length ? `<div class="level-pnl-strip">${chips.join('')}</div>` : '';
}

function pnlBadge(amount, currency, tradesCount) {
  const num = Number(amount || 0);
  if (!tradesCount && num === 0) return '';
  const cls = num > 0 ? 'pnl-pos' : num < 0 ? 'pnl-neg' : 'pnl-zero';
  const tradeTag = tradesCount > 1 ? `${tradesCount} trades • ` : '';
  return `<span class="pill ${cls}">${esc(tradeTag + fmtPnl(num, currency))}</span>`;
}

function buildLegsHtml(r) {
  const legs = Array.isArray(r.legs) ? r.legs : [];
  if (!legs.length) return '';
  return `
    <div class="legs-box">
      <div class="legs-title">Entry Legs &amp; Position P&amp;L (${r.trades_count || 0} of ${legs.length} triggered)</div>
      <div class="legs-grid">
        ${legs.map(lg => {
          const entered = Boolean(lg.entered);
          const mPnl = Number(lg.money_pnl || 0);
          const pnlCls = !entered ? 'muted' : mPnl > 0 ? 'green-txt' : mPnl < 0 ? 'red' : 'muted';
          const statusLbl = !entered
            ? 'Not triggered'
            : lg.outcome === 'target_hit'
              ? `TP${Math.max(...(lg.targets_hit || [1]))} Hit @ ${lg.exit_price}`
              : lg.outcome === 'stop_loss_hit'
                ? `SL Hit @ ${lg.exit_price}`
                : lg.outcome === 'ambiguous'
                  ? 'Ambiguous candle'
                  : 'Open (Monitoring)';
          return `
            <div class="leg-item ${entered ? 'leg-active' : 'leg-idle'}">
              <div class="leg-head">
                <b>Entry ${esc(lg.leg)}: ${esc(lg.entry_price)}</b>
                <span class="${pnlCls}"><b>${entered ? esc(fmtPnl(mPnl, lg.currency || r.pnl_currency)) : '—'}</b></span>
              </div>
              <div class="leg-meta">
                <span>${esc(statusLbl)}</span>
                ${lg.position_label ? `<span>Size: ${esc(lg.position_label)}</span>` : ''}
              </div>
            </div>`;
        }).join('')}
      </div>
    </div>`;
}

function buildTimelineHtml(r) {
  const events = Array.isArray(r.events) ? r.events : [];
  const targets = Array.isArray(r.targets) ? r.targets : [];
  const tpEventsByNum = {};
  const entryEvents = [];
  let slEvent = null;
  let slAfterTpEvent = null;
  let ambEvent = null;
  for (const ev of events) {
    if (ev.type === 'entry_hit') entryEvents.push(ev);
    if (ev.type === 'tp_hit') {
      const m = String(ev.label || '').match(/TP(\d+)/i);
      if (m && !tpEventsByNum[Number(m[1])]) tpEventsByNum[Number(m[1])] = ev;
    }
    if (ev.type === 'sl_hit' && !slEvent) slEvent = ev;
    if (ev.type === 'sl_after_tp' && !slAfterTpEvent) slAfterTpEvent = ev;
    if (ev.type === 'ambiguous' && !ambEvent) ambEvent = ev;
  }
  const allEntries = [r.entry_price, ...(r.additional_entries || [])].filter(v => v != null);
  const steps = [{ state: 'done', title: 'Signal Sent', price: allEntries.length ? `Entry @ ${allEntries.join(' / ')}` : '—', time: fmtDateTime(r.notification_time) }];
  if (allEntries.length > 1) {
    allEntries.forEach((eVal, idx) => {
      const legNum = idx + 1;
      const hitEv = entryEvents.find(e => Number(e.leg) === legNum) || entryEvents[idx];
      steps.push({ state: hitEv ? 'done' : 'wait', title: `Entry ${legNum}`, price: `${eVal}`, time: hitEv ? fmtDateTime(hitEv.candle_time) : 'Not reached yet' });
    });
  } else {
    const entryEvent = entryEvents[0] || null;
    steps.push({ state: entryEvent ? 'done' : 'wait', title: 'Entry Hit', price: r.entry_price != null ? `${r.entry_price}` : 'Not parsed', time: entryEvent ? fmtDateTime(entryEvent.candle_time) : 'Not reached yet' });
  }
  if (targets.length) {
    targets.forEach((tVal, idx) => {
      const tpNum = idx + 1;
      const hit = tpEventsByNum[tpNum];
      steps.push({ state: hit ? 'win' : 'wait', title: `TP${tpNum}`, price: `${tVal}`, time: hit ? fmtDateTime(hit.candle_time) : 'Awaiting' });
    });
  } else {
    steps.push({ state: 'wait', title: 'Targets (TP)', price: 'None parsed', time: '—' });
  }
  if (slAfterTpEvent) {
    steps.push({ state: 'warn', title: 'SL Closed After TP (Profitable)', price: `${slAfterTpEvent.price ?? r.stop_loss ?? ''}`, time: fmtDateTime(slAfterTpEvent.candle_time) });
  } else {
    steps.push({ state: slEvent ? 'loss' : 'wait', title: 'Stop Loss (SL)', price: r.stop_loss != null ? `${r.stop_loss}` : 'None parsed', time: slEvent ? fmtDateTime(slEvent.candle_time) : 'Not hit' });
  }
  if (ambEvent) steps.push({ state: 'warn', title: 'Ambiguous Candle', price: 'TP & SL in same 1m bar', time: fmtDateTime(ambEvent.candle_time) });
  return `<div class="timeline-track">${steps.map(st => `
    <div class="tl-step tl-${st.state}">
      <div class="tl-dot"></div>
      <div class="tl-body">
        <div class="tl-head"><b>${esc(st.title)}</b><span class="tl-price">${esc(st.price)}</span></div>
        <div class="tl-time">${esc(st.time)}</div>
      </div>
    </div>`).join('')}</div>`;
}

function renderCandlestickChart(container, chartData, hoverOut) {
  const candles = chartData.candles || [];
  if (!candles.length) {
    const label = chartData.trading_symbol ? `${chartData.trading_symbol} (token ${chartData.resolved_symbol})` : (chartData.resolved_symbol || chartData.symbol || '');
    const reasonHtml = chartData.detail ? `<div style="margin-top:6px;color:#ffbb66">${esc(chartData.detail)}</div>` : '';
    container.innerHTML = `<div class="chart-empty">No cached 1m candles yet for <b>${esc(label)}</b> (${esc(chartData.provider)}).${reasonHtml}</div>`;
    return;
  }
  container.innerHTML = `
    <div class="chart-toolbar">
      <div class="chart-btn-group">
        <button type="button" class="ch-btn" data-act="zoom-in">+</button>
        <button type="button" class="ch-btn" data-act="zoom-out">&minus;</button>
        <button type="button" class="ch-btn" data-act="focus">Focus Trade</button>
        <button type="button" class="ch-btn" data-act="fit">Fit All</button>
      </div>
      <button type="button" class="ch-btn" data-act="fullscreen">&#x26F6; Full Screen</button>
    </div>
    <div class="chart-fs-readout" style="display:none;font-size:11px;color:#9fb0cf;padding:4px 2px 6px;"></div>
    <div class="chart-canvas-holder" style="flex:1;min-height:0;width:100%;"><canvas class="ohlc-canvas"></canvas></div>`;
  const toolbar = container.querySelector('.chart-toolbar');
  const fsReadout = container.querySelector('.chart-fs-readout');
  const fsBtn = container.querySelector('[data-act="fullscreen"]');
  const holder = container.querySelector('.chart-canvas-holder');
  const canvas = container.querySelector('canvas');
  const ctx = canvas.getContext('2d');
  const placeholder = document.createComment('chart-placeholder');
  const candleTimesMs = candles.map(c => new Date(c.candle_timestamp).getTime());
  function nearestIdx(iso) {
    if (!iso) return -1;
    const targetMs = new Date(iso).getTime();
    if (isNaN(targetMs)) return -1;
    let best = 0, bestDiff = Infinity;
    for (let i = 0; i < candleTimesMs.length; i++) {
      const diff = Math.abs(candleTimesMs[i] - targetMs);
      if (diff < bestDiff) { bestDiff = diff; best = i; }
    }
    return best;
  }
  const markers = [], markersByIdx = new Map();
  function addMarker(idx, label, color, price, detailText) {
    if (idx < 0 || idx >= candles.length) return;
    const item = { idx, label, color, price, detailText };
    markers.push(item);
    if (!markersByIdx.has(idx)) markersByIdx.set(idx, []);
    markersByIdx.get(idx).push(item);
  }
  const sigIdx = nearestIdx(chartData.notification_time);
  if (sigIdx >= 0) addMarker(sigIdx, 'SIG', '#94a3bd', candles[sigIdx].close, `Signal Sent (${fmtDateTime(chartData.notification_time)})`);
  for (const ev of (chartData.events || [])) {
    const idx = nearestIdx(ev.candle_time);
    if (idx < 0) continue;
    const isTp = ev.type === 'tp_hit';
    const isSlAfterTp = ev.type === 'sl_after_tp';
    const isSl = ev.type === 'sl_hit' || ev.type === 'ambiguous';
    const shortLbl = ev.type === 'entry_hit' ? (ev.leg ? `E${ev.leg}` : 'ENTRY') : isTp ? String(ev.label || 'TP').split(' ')[0] : isSlAfterTp ? 'SL*' : isSl ? 'SL' : ev.label;
    const col = isTp ? '#62d6a5' : isSlAfterTp ? '#f59e0b' : isSl ? '#ff7285' : '#60a5fa';
    addMarker(idx, shortLbl, col, ev.price ?? candles[idx].close, `${ev.label} @ ${ev.price ?? ''} (${fmtDateTime(ev.candle_time)})`);
  }
  const minVisible = Math.min(8, candles.length), maxVisible = Math.max(candles.length, minVisible);
  let viewStart = 0, visibleCount = candles.length, activeIdx = -1, includeAllLevelsInScale = false;
  function clampViewport() {
    visibleCount = Math.max(minVisible, Math.min(maxVisible, visibleCount));
    viewStart = Math.max(0, Math.min(Math.max(0, candles.length - visibleCount), viewStart));
  }
  function focusTradeRange() {
    const ks = markers.map(m => m.idx);
    if (ks.length) {
      const firstEv = Math.min(...ks), lastEv = Math.max(...ks), span = Math.max(lastEv - firstEv + 1, 16), padBars = Math.max(5, Math.round(span * 0.25));
      viewStart = Math.max(0, firstEv - padBars); visibleCount = Math.min(candles.length, span + padBars * 2);
    } else { viewStart = 0; visibleCount = Math.min(candles.length, 60); }
    includeAllLevelsInScale = true; clampViewport();
  }
  function fitAllRange() { viewStart = 0; visibleCount = candles.length; includeAllLevelsInScale = true; clampViewport(); }
  if (candles.length > 45) focusTradeRange(); else fitAllRange();
  function getDimensions() {
    if (container.classList.contains('chart-fullscreen')) {
      const topChrome = (toolbar?.offsetHeight || 36) + (fsReadout?.offsetHeight || 22) + 20;
      return { width: Math.max(holder.clientWidth || window.innerWidth - 20, 260), height: Math.max(holder.clientHeight || (window.innerHeight - topChrome), 260) };
    }
    return { width: Math.max(holder.clientWidth || container.clientWidth || 320, 260), height: 270 };
  }
  function draw() {
    if (!document.body.contains(container)) return;
    const { width, height } = getDimensions();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
      canvas.width = Math.round(width * dpr); canvas.height = Math.round(height * dpr);
      canvas.style.width = width + 'px'; canvas.style.height = height + 'px';
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const pad = { top: 22, right: 68, bottom: 26, left: 10 };
    const plotW = Math.max(width - pad.left - pad.right, 40), plotH = Math.max(height - pad.top - pad.bottom, 40);
    const startIdx = Math.max(0, Math.floor(viewStart)), endIdx = Math.min(candles.length - 1, Math.ceil(viewStart + visibleCount));
    let cMin = Infinity, cMax = -Infinity;
    for (let i = startIdx; i <= endIdx; i++) { if (candles[i].low < cMin) cMin = candles[i].low; if (candles[i].high > cMax) cMax = candles[i].high; }
    if (!isFinite(cMin) || !isFinite(cMax)) { cMin = 0; cMax = 1; }
    let minP = cMin, maxP = cMax;
    const cSpan = Math.max(cMax - cMin, Math.abs(cMax) * 0.002 || 1);
    const allLevels = [chartData.entry_price, ...(chartData.additional_entries || []), chartData.stop_loss, ...(chartData.targets || [])].filter(v => v != null && !isNaN(Number(v))).map(Number);
    for (const lvl of allLevels) { if (includeAllLevelsInScale || (lvl >= cMin - cSpan * 0.6 && lvl <= cMax + cSpan * 0.6)) { if (lvl < minP) minP = lvl; if (lvl > maxP) maxP = lvl; } }
    if (minP === maxP) { minP -= 1; maxP += 1; }
    const margin = (maxP - minP) * 0.09; minP -= margin; maxP += margin;
    const yOf = p => pad.top + ((maxP - p) / (maxP - minP)) * plotH;
    const stepX = plotW / Math.max(visibleCount, 1), xOf = idx => pad.left + (idx - viewStart + 0.5) * stepX;
    const bodyW = Math.max(2, Math.min(14, stepX * 0.68));
    ctx.fillStyle = '#090f1d'; ctx.fillRect(0, 0, width, height);
    ctx.font = '10px system-ui, sans-serif'; ctx.textAlign = 'left';
    for (let g = 0; g <= 4; g++) {
      const p = minP + ((maxP - minP) * g) / 4, y = yOf(p);
      ctx.strokeStyle = '#1b2742'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(width - pad.right, y); ctx.stroke();
      ctx.fillStyle = '#7f91b0'; ctx.fillText(p >= 100 ? p.toFixed(2) : p.toFixed(4), width - pad.right + 5, y + 3);
    }
    ctx.save(); ctx.beginPath(); ctx.rect(pad.left, pad.top - 14, plotW, plotH + 14); ctx.clip();
    function drawLevel(price, label, color) {
      if (price == null || isNaN(Number(price))) return;
      const num = Number(price); if (num < minP || num > maxP) return;
      const y = yOf(num);
      ctx.save(); ctx.setLineDash([4, 4]); ctx.strokeStyle = color; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(width - pad.right, y); ctx.stroke(); ctx.restore();
      ctx.fillStyle = color; ctx.font = 'bold 10px system-ui, sans-serif'; ctx.fillText(`${label}: ${num}`, pad.left + 4, Math.max(12, y - 3)); ctx.font = '10px system-ui, sans-serif';
    }
    drawLevel(chartData.entry_price, 'ENTRY 1', '#60a5fa');
    (chartData.additional_entries || []).forEach((eVal, i) => drawLevel(eVal, `ENTRY ${i + 2}`, '#60a5fa'));
    drawLevel(chartData.stop_loss, 'SL', '#ff7285');
    (chartData.targets || []).forEach((tVal, i) => drawLevel(tVal, `TP${i + 1}`, '#62d6a5'));
    for (let i = startIdx; i <= endIdx; i++) {
      const c = candles[i], cx = xOf(i), col = c.close >= c.open ? '#34d399' : '#f87171';
      ctx.strokeStyle = col; ctx.fillStyle = col; ctx.lineWidth = 1.2;
      ctx.beginPath(); ctx.moveTo(cx, yOf(c.high)); ctx.lineTo(cx, yOf(c.low)); ctx.stroke();
      const topY = yOf(Math.max(c.open, c.close)), botY = yOf(Math.min(c.open, c.close));
      ctx.fillRect(cx - bodyW / 2, topY, bodyW, Math.max(1.5, botY - topY));
    }
    markers.forEach((m, mOrder) => {
      if (m.idx < startIdx - 1 || m.idx > endIdx + 1) return;
      const cx = xOf(m.idx), cy = yOf(m.price);
      ctx.fillStyle = m.color; ctx.beginPath(); ctx.arc(cx, cy, 4.5, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = '#090f1d'; ctx.lineWidth = 1.5; ctx.stroke();
      ctx.font = 'bold 9px system-ui, sans-serif';
      const tagW = ctx.measureText(m.label).width + 8;
      const tagX = Math.max(pad.left + 2, Math.min(width - pad.right - tagW - 2, cx - tagW / 2));
      const tagY = Math.max(15, Math.min(height - pad.bottom - 6, cy - 10 - (mOrder % 2) * 14));
      ctx.fillStyle = m.color; ctx.fillRect(tagX, tagY - 10, tagW, 13);
      ctx.fillStyle = '#06131f'; ctx.fillText(m.label, tagX + 4, tagY);
    });
    if (activeIdx >= startIdx && activeIdx <= endIdx) {
      const c = candles[activeIdx], cx = xOf(activeIdx), cy = yOf(c.close);
      ctx.save(); ctx.setLineDash([3, 3]); ctx.strokeStyle = '#e7edf9'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(cx, pad.top); ctx.lineTo(cx, height - pad.bottom); ctx.moveTo(pad.left, cy); ctx.lineTo(width - pad.right, cy); ctx.stroke(); ctx.restore();
    }
    ctx.restore();
    ctx.fillStyle = '#7f91b0'; ctx.font = '10px system-ui, sans-serif';
    ctx.fillText(fmtShortTime(candles[startIdx].candle_timestamp), pad.left, height - 8);
    const endLbl = fmtShortTime(candles[endIdx].candle_timestamp);
    ctx.fillText(endLbl, Math.max(pad.left, width - pad.right - ctx.measureText(endLbl).width), height - 8);
    const statusText = (activeIdx >= 0 && activeIdx < candles.length)
      ? (() => {
          const c = candles[activeIdx], evList = markersByIdx.get(activeIdx) || [];
          const evNote = evList.length ? `  |  ★ ${evList.map(e => e.detailText).join(' • ')}` : '';
          return `${fmtDateTime(c.candle_timestamp)}  |  O: ${c.open}  H: ${c.high}  L: ${c.low}  C: ${c.close}${evNote}`;
        })()
      : `Showing bars ${startIdx + 1}–${endIdx + 1} of ${candles.length} (${chartData.provider} • ${chartData.trading_symbol || chartData.resolved_symbol})`;
    if (hoverOut) hoverOut.textContent = statusText;
    if (fsReadout) fsReadout.textContent = statusText;
  }
  function zoomBy(factor, anchorRatio = 0.5) {
    includeAllLevelsInScale = false;
    const anchor = viewStart + anchorRatio * visibleCount;
    visibleCount = Math.max(minVisible, Math.min(maxVisible, visibleCount * factor));
    viewStart = anchor - anchorRatio * visibleCount;
    clampViewport(); draw();
  }
  function clientXToCandleIdx(clientX) {
    const rect = canvas.getBoundingClientRect();
    const plotW = Math.max(rect.width - 78, 40);
    return Math.max(0, Math.min(candles.length - 1, Math.floor(viewStart + ((clientX - rect.left - 10) / plotW) * visibleCount)));
  }
  container.querySelectorAll('.ch-btn').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const act = btn.dataset.act;
      if (act === 'zoom-in') zoomBy(0.65);
      else if (act === 'zoom-out') zoomBy(1.5);
      else if (act === 'focus') { focusTradeRange(); draw(); }
      else if (act === 'fit') { fitAllRange(); draw(); }
      else if (act === 'fullscreen') {
        const enable = !container.classList.contains('chart-fullscreen');
        if (enable) {
          if (container.parentNode !== document.body && container.parentNode) { container.parentNode.insertBefore(placeholder, container); document.body.appendChild(container); }
          container.classList.add('chart-fullscreen'); fsReadout.style.display = 'block'; fsBtn.innerHTML = '&#x2715; Exit Full';
        } else {
          container.classList.remove('chart-fullscreen'); fsReadout.style.display = 'none'; fsBtn.innerHTML = '&#x26F6; Full Screen';
          if (placeholder.parentNode) { placeholder.parentNode.insertBefore(container, placeholder); placeholder.parentNode.removeChild(placeholder); }
        }
        requestAnimationFrame(draw);
      }
    };
  });
  let mouseDown = false, dragStartX = 0, dragStartView = 0;
  canvas.addEventListener('mousedown', e => { mouseDown = true; dragStartX = e.clientX; dragStartView = viewStart; });
  window.addEventListener('mousemove', e => {
    if (!mouseDown) return;
    includeAllLevelsInScale = false;
    viewStart = dragStartView - ((e.clientX - dragStartX) / Math.max(canvas.clientWidth - 78, 40)) * visibleCount;
    clampViewport(); draw();
  });
  window.addEventListener('mouseup', () => { mouseDown = false; });
  canvas.addEventListener('mousemove', e => { if (!mouseDown) { activeIdx = clientXToCandleIdx(e.clientX); draw(); } });
  canvas.addEventListener('mouseleave', () => { if (!mouseDown) { activeIdx = -1; draw(); } });
  canvas.addEventListener('wheel', e => { e.preventDefault(); zoomBy(e.deltaY > 0 ? 1.25 : 0.8, 0.5); }, { passive: false });
  draw();
}

async function loadCardChart(signalId, forceRefresh = false) {
  const wrap = document.querySelector(`#chart-wrap-${signalId}`);
  const info = document.querySelector(`#chart-hover-${signalId}`);
  if (!wrap) return;
  wrap.innerHTML = '<p class="muted" style="padding:12px">Loading OHLC candles &amp; markers...</p>';
  try {
    if (forceRefresh) await api(`/api/v1/signals/${signalId}/verify`, { method: 'POST' });
    const data = await api(`/api/v1/signals/${signalId}/chart`);
    chartCache.set(signalId, data);
    renderCandlestickChart(wrap, data, info);
  } catch (e) {
    wrap.innerHTML = `<p class="red" style="padding:12px">Chart error: ${esc(e.message)}</p>`;
  }
}

function renderSignalCard(r) {
  const isExpanded = expandedSignals.has(r.id);
  const isChecked = selectedSignals.has(r.id);
  const dirClass = r.direction === 'BUY' ? 'dir-buy' : r.direction === 'SELL' ? 'dir-sell' : 'dir-none';
  const allEntries = [r.entry_price, ...(r.additional_entries || [])].filter(v => v != null);
  const isMarketNow = r.entry_mode === 'market_now';
  const entryText = allEntries.length ? allEntries.join(' / ') : (isMarketNow ? 'Market Now' : '—');
  const targetsText = Array.isArray(r.targets) && r.targets.length ? r.targets.join(' / ') : '—';
  const optionMeta = (r.strike_price || r.option_type) ? ` ${r.strike_price || ''} ${r.option_type || ''}` : '';
  return `
  <div class="signal-card ${isExpanded ? 'open' : ''}" data-card-id="${r.id}">
    <div class="signal-card-header" data-toggle-id="${r.id}">
      <label class="card-check" onclick="event.stopPropagation()">
        <input class="signal-select" data-id="${r.id}" type="checkbox" ${isChecked ? 'checked' : ''}>
      </label>
      <div class="card-main-info">
        <div class="card-top-row">
          <span class="card-id">#${r.id}</span>
          <strong class="card-symbol">${esc(r.symbol || 'UNPARSED')}${esc(optionMeta)}</strong>
          ${r.direction ? `<span class="dir-pill ${dirClass}">${esc(r.direction)}</span>` : ''}
          <span class="cat-pill">${esc(r.category || '')}</span>
          <span class="card-channel">${esc(r.channel || '')}</span>
        </div>
        <div class="card-sub-row">
          <span>Entry: <b>${esc(entryText)}</b></span>
          <span>SL: <b>${esc(r.stop_loss ?? '—')}</b>${r.is_default_sl ? ' <small class="muted">(Default)</small>' : ''}</span>
          <span>TP: <b>${esc(targetsText)}</b>${r.is_default_tp ? ' <small class="muted">(Default)</small>' : ''}</span>
          <span>Sent: <b>${esc(fmtShortTime(r.notification_time))}</b> (${esc(r.age_minutes)}m ago)</span>
        </div>
        ${levelPnlStripHtml(r, sf.pnl_basis)}
      </div>
      <div class="card-right-badges">
        ${pnlBadge(r.basis_pnl_amount ?? r.pnl_amount, r.pnl_currency, r.trades_count)}
        ${phaseBadge(r.monitoring_phase)}
        <span class="parse-pill ${r.parsing_status === 'parsed' ? 'p-ok' : 'p-warn'}">${esc(r.parsing_status)} (${esc(r.parsing_confidence)})</span>
        ${statusBadge(r.verification_status, r.is_manual_needed, r.is_auto_scheduled, r.closed_by_sl_after_tp, r.max_tp_hit)}
        <span class="chevron">${isExpanded ? '▲' : '▼'}</span>
      </div>
    </div>
    <div class="signal-card-body" style="display:${isExpanded ? 'block' : 'none'}">
      <div class="status-banner ${r.is_manual_needed ? 'banner-warn' : 'banner-info'}">
        <b>Status &amp; Schedule:</b> ${esc(r.status_reason)}
        ${r.next_scheduled_at ? `<div class="muted" style="margin-top:4px">Next automatic candle fetch: <b>${esc(fmtDateTime(r.next_scheduled_at))}</b></div>` : ''}
      </div>
      ${buildLegsHtml(r)}
      <div class="dropdown-grid">
        <div class="dd-panel"><h4>Trade Execution Timeline</h4>${buildTimelineHtml(r)}</div>
        <div class="dd-panel">
          <div class="toolbar" style="margin-bottom:6px">
            <h4 style="margin:0">Candle Chart &amp; Trade Markers</h4>
            <div style="display:flex;gap:6px;flex-wrap:wrap">
              <button type="button" class="chart-refresh-btn" data-review-card="${r.id}">Edit / Review</button>
              <button type="button" class="chart-refresh-btn" data-chart-id="${r.id}">Fetch &amp; Refresh Chart</button>
              <button type="button" class="chart-refresh-btn btn-danger" data-delete-id="${r.id}">Delete</button>
            </div>
          </div>
          <div id="chart-hover-${r.id}" class="muted" style="font-size:11px;margin-bottom:6px">Expand to load 1m OHLC chart...</div>
          <div id="chart-wrap-${r.id}" class="chart-box"></div>
        </div>
      </div>
      <div class="dropdown-grid" style="margin-top:12px">
        <div class="dd-panel">
          <h4>Parsed Data</h4>
          <div class="kv-grid">
            <div><span>Symbol</span><b>${esc(r.symbol || '—')}</b></div>
            <div><span>Direction</span><b>${esc(r.direction || '—')}</b></div>
            <div><span>Entry 1</span><b>${esc(r.entry_price ?? (isMarketNow ? 'Market Now' : '—'))}</b></div>
            <div><span>Extra Entries</span><b>${esc((r.additional_entries || []).join(', ') || '—')}</b></div>
            <div><span>Stop Loss</span><b>${esc(r.stop_loss ?? '—')}</b></div>
            <div><span>Targets</span><b>${esc(targetsText)}</b></div>
            <div><span>Realized P&amp;L</span><b>${r.trades_count ? esc(fmtPnl(r.pnl_amount, r.pnl_currency)) : '—'}</b></div>
            <div><span>Option</span><b>${esc([r.option_type, r.strike_price, r.expiry_date].filter(Boolean).join(' ') || '—')}</b></div>
          </div>
          <p class="muted" style="font-size:11px;margin:8px 0 0">${esc(r.parsing_explanation || '')}</p>
        </div>
        <div class="dd-panel">
          <h4>Original Raw Notification</h4>
          <p class="muted" style="font-size:11px;margin:0 0 6px"><b>Sent:</b> ${esc(fmtDateTime(r.notification_time))} &bull; <b>Channel:</b> ${esc(r.channel)}</p>
          <pre class="raw-msg-box">${esc(r.message)}</pre>
        </div>
      </div>
    </div>
  </div>`;
}

function renderSignalTable(data) {
  const container = $('#signals-list');
  if (!data.length) { container.innerHTML = '<p class="muted">No signals match the active filters.</p>'; return; }
  const recentLive = data.filter(r => !r.is_historical);
  const olderManual = data.filter(r => r.is_historical);
  let html = '';
  if (recentLive.length) {
    html += `<div class="signal-section-header"><div><h3>Recent &amp; Scheduled Signals (${recentLive.length})</h3><p class="muted">Signals within 1 hour or running automatic verification.</p></div><button type="button" id="select-live-group">Select recent (${recentLive.length})</button></div><div class="cards-stack">${recentLive.map(renderSignalCard).join('')}</div>`;
  }
  if (olderManual.length) {
    html += `<div class="signal-section-header older-header"><div><h3>Older &amp; Completed Signals (${olderManual.length})</h3><p class="muted">Select checkboxes to re-parse, process, or delete.</p></div><button type="button" id="select-older-group">Select older (${olderManual.length})</button></div><div class="cards-stack">${olderManual.map(renderSignalCard).join('')}</div>`;
  }
  container.innerHTML = html;
  document.querySelectorAll('.signal-select').forEach(x => { x.onchange = () => { if (x.checked) selectedSignals.add(+x.dataset.id); else selectedSignals.delete(+x.dataset.id); }; });
  document.querySelectorAll('[data-toggle-id]').forEach(hdr => {
    hdr.onclick = () => {
      const id = +hdr.dataset.toggleId;
      const card = document.querySelector(`.signal-card[data-card-id="${id}"]`);
      const body = card.querySelector('.signal-card-body');
      const chev = card.querySelector('.chevron');
      if (expandedSignals.has(id)) { expandedSignals.delete(id); card.classList.remove('open'); body.style.display = 'none'; chev.textContent = '▼'; }
      else { expandedSignals.add(id); card.classList.add('open'); body.style.display = 'block'; chev.textContent = '▲'; loadCardChart(id, false); }
    };
  });
  document.querySelectorAll('[data-review-card]').forEach(btn => { btn.onclick = e => { e.stopPropagation(); reviewParse(+btn.dataset.reviewCard); }; });
  document.querySelectorAll('.chart-refresh-btn[data-chart-id]').forEach(btn => { btn.onclick = e => { e.stopPropagation(); const id = +btn.dataset.chartId; busy(btn, 'Fetching...', async () => { await loadCardChart(id, true); await loadSignals(); }); }; });
  document.querySelectorAll('[data-delete-id]').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const id = +btn.dataset.deleteId;
      if (!confirm(`Delete signal #${id}?`)) return;
      busy(btn, 'Deleting...', async () => { await api(`/api/v1/signals/${id}`, { method: 'DELETE' }); selectedSignals.delete(id); expandedSignals.delete(id); await loadSignals(); });
    };
  });
  if ($('#select-live-group')) $('#select-live-group').onclick = () => { recentLive.forEach(x => selectedSignals.add(x.id)); renderSignalTable(data); };
  if ($('#select-older-group')) $('#select-older-group').onclick = () => { olderManual.forEach(x => selectedSignals.add(x.id)); renderSignalTable(data); };
  expandedSignals.forEach(id => { if (data.some(x => x.id === id)) loadCardChart(id, false); });
}

async function openSignalInManagement(signalId) {
  sf = { category: '', channel: '', status: '', tp_filter: '', pnl_basis: 'tp1', date_from: '', date_to: '', notification_filter: 'all' };
  expandedSignals.add(Number(signalId));
  show('signals');
}

async function reviewParse(id) {
  let panel = $('#parse-review'), dialog = $('#parse-review-dialog');
  panel.innerHTML = '<p class="muted">Loading parsed signal review...</p>';
  if (!dialog.open) dialog.showModal();
  try {
    let x = await api('/api/v1/signals/' + id + '/parse-detail'), s = x.signal;
    panel.innerHTML = `
      <div class="actions" style="margin-bottom:12px">
        <button type="button" id="modal-reparse-btn">Re-parse Now</button>
        <button type="button" class="primary" id="modal-jump-signal">Open in Signal Management (#${s.id})</button>
      </div>
      <div class="grid">
        <article style="margin-bottom:0">
          <h3>Original notification</h3>
          <p style="font-size:12px"><b>Time:</b> ${esc(fmtDateTime(s.notification_time))}<br><b>Channel:</b> ${esc(s.channel)}<br><b>Category:</b> ${esc(s.category)}</p>
          <pre class="raw-msg-box">${esc(s.message)}</pre>
        </article>
        <article style="margin-bottom:0">
          <h3>Manual correction</h3>
          <form id="modal-fix-form" class="form-grid" style="grid-template-columns:1fr 1fr;gap:8px">
            <label>Symbol<input name="symbol" value="${esc(s.symbol || '')}"></label>
            <label>Direction<select name="direction"><option value="">—</option><option value="BUY" ${s.direction === 'BUY' ? 'selected' : ''}>BUY</option><option value="SELL" ${s.direction === 'SELL' ? 'selected' : ''}>SELL</option></select></label>
            <label>Entry 1<input name="entry_price" type="number" step="any" value="${esc(s.entry_price ?? '')}"></label>
            <label>Extra Entries<input name="additional_entries" value="${esc((s.additional_entries || []).join(', '))}"></label>
            <label>Stop Loss<input name="stop_loss" type="number" step="any" value="${esc(s.stop_loss ?? '')}"></label>
            <label>Targets<input name="targets" value="${esc((s.targets || []).join(', '))}"></label>
            <label>Option Type<select name="option_type"><option value="">None</option><option value="CE" ${s.option_type === 'CE' ? 'selected' : ''}>CE</option><option value="PE" ${s.option_type === 'PE' ? 'selected' : ''}>PE</option></select></label>
            <label>Strike Price<input name="strike_price" type="number" step="any" value="${esc(s.strike_price ?? '')}"></label>
            <label style="grid-column:span 2">Expiry Date<input name="expiry_date" value="${esc(s.expiry_date || '')}"></label>
            <button type="submit" class="primary" style="grid-column:span 2">Save Manual Fix</button>
          </form>
        </article>
      </div>
      <p class="muted" style="font-size:12px;margin:10px 0"><b>Parser note:</b> ${esc(s.parsing_explanation || '—')}</p>
      <h3>Re-parse history</h3>
      ${table(x.history || [], ['parsed_at', 'run_type', 'status', 'confidence', 'explanation'])}`;
    $('#modal-jump-signal').onclick = () => { dialog.close(); openSignalInManagement(s.id); };
    $('#modal-reparse-btn').onclick = () => busy($('#modal-reparse-btn'), 'Re-parsing...', async () => { await api(`/api/v1/signals/${s.id}/reparse`, { method: 'POST' }); await reviewParse(s.id); });
    $('#modal-fix-form').onsubmit = async e => {
      e.preventDefault();
      const fd = Object.fromEntries(new FormData(e.target));
      const parseNumList = str => String(str || '').split(',').map(v => v.trim()).filter(Boolean).map(Number).filter(n => !isNaN(n));
      const payload = {
        symbol: (fd.symbol || '').trim().toUpperCase() || null,
        direction: fd.direction || null,
        entry_price: fd.entry_price !== '' ? Number(fd.entry_price) : null,
        additional_entries: parseNumList(fd.additional_entries),
        stop_loss: fd.stop_loss !== '' ? Number(fd.stop_loss) : null,
        targets: parseNumList(fd.targets),
        option_type: fd.option_type || null,
        strike_price: fd.strike_price !== '' ? Number(fd.strike_price) : null,
        expiry_date: (fd.expiry_date || '').trim().toUpperCase() || null,
        entry_mode: fd.entry_price !== '' ? 'limit' : (s.entry_mode || 'limit'),
        manual_review_status: 'resolved',
      };
      await api(`/api/v1/signals/${s.id}`, { method: 'PUT', body: JSON.stringify(payload) });
      await reviewParse(s.id);
      refreshActiveView();
    };
  } catch (e) { panel.innerHTML = `<p class="red">Error: ${esc(e.message)}</p>`; }
}

async function notificationLog() {
  let q = new URLSearchParams({ limit: $('#notification-limit').value });
  const ps = $('#notification-parsing-status')?.value || '', nt = $('#notification-log-type')?.value || 'all',
    ch = $('#notification-channel').value, cat = $('#notification-category').value,
    df = $('#notification-date-from').value, dt = $('#notification-date-to').value;
  if (ps) q.set('parsing_status', ps);
  if (nt && nt !== 'all') q.set('notification_filter', nt);
  if (ch) q.set('channel', ch);
  if (cat) q.set('category', cat);
  if (df) q.set('date_from', df);
  if (dt) q.set('date_to', dt);
  let [items, syncs, filters] = await Promise.all([api('/api/v1/notifications?' + q), api('/api/v1/sync/logs'), api('/api/v1/notifications/filters')]);
  if ($('#notification-channel').options.length === 1) {
    $('#notification-channel').innerHTML = '<option value="">All channels</option>' + filters.channels.map(x => `<option>${esc(x)}</option>`).join('');
    $('#notification-category').innerHTML = '<option value="">All categories</option>' + filters.categories.map(x => `<option>${esc(x)}</option>`).join('');
  }
  const host = $('#notifications-table');
  if (!items.length) host.innerHTML = '<p class="muted">No notifications match.</p>';
  else {
    host.innerHTML = `<table><thead><tr><th>Actions</th><th>ID</th><th>Sent</th><th>Channel</th><th>Category</th><th>Parse</th><th>Summary</th><th>Verification</th><th>Raw Message</th></tr></thead><tbody>${items.map(x => {
      const pStatus = x.parsing_status || 'unparsed';
      const eList = [x.entry_price, ...(x.additional_entries || [])].filter(v => v != null);
      const summary = x.signal_id ? `${x.symbol || '—'} ${x.direction || ''} | E: ${eList.join('/') || '—'} | SL: ${x.stop_loss ?? '—'} | TP: ${(x.targets || []).join('/') || '—'}` : 'Not parsed';
      const act = x.signal_id ? `<button type="button" class="ch-btn" data-review-sig="${x.signal_id}">Review</button> <button type="button" class="ch-btn" data-goto-sig="${x.signal_id}">#${x.signal_id}</button>` : '—';
      return `<tr><td>${act}</td><td>${esc(x.id)}</td><td>${esc(fmtShortTime(x.notification_time || x.received_at))}</td><td>${esc(x.channel)}</td><td>${esc(x.category)}</td><td><span class="parse-pill ${pStatus === 'parsed' ? 'p-ok' : 'p-warn'}">${esc(pStatus)}</span></td><td style="font-size:11px">${esc(summary)}</td><td>${esc(x.verification_status || '—')}</td><td class="message">${esc(x.message)}</td></tr>`;
    }).join('')}</tbody></table>`;
    host.querySelectorAll('[data-review-sig]').forEach(b => b.onclick = () => reviewParse(+b.dataset.reviewSig));
    host.querySelectorAll('[data-goto-sig]').forEach(b => b.onclick = () => openSignalInManagement(+b.dataset.gotoSig));
  }
  $('#notification-sync-history').innerHTML = table(syncs, ['started_at', 'finished_at', 'status', 'imported_rows', 'duplicate_rows', 'skipped_rows', 'failed_rows', 'detail']);
  $('#notification-status').textContent = `Showing ${items.length} stored messages`;
}

async function overview() {
  let x = await api('/api/v1/overview'), m = x.metrics;
  $('#metrics').innerHTML = Object.entries({
    Notifications: m.notifications, 'Parsed signals': m.parsed, Pending: m.pending,
    'Target hits': m.target_hits, 'Stop-loss hits': m.stop_loss_hits, 'Win rate': m.win_rate + '%',
    'P&L (INR)': fmtPnl(m.pnl_inr, 'INR'), 'P&L (USD)': fmtPnl(m.pnl_usd, 'USD'),
  }).map(([k, v]) => `<div class="metric"><span>${k}</span><b>${esc(v)}</b></div>`).join('');
  $('#categories').innerHTML = bars(x.categories || []);
  $('#channels').innerHTML = bars(x.channels || []);
  $('#recent-signals').innerHTML = table(x.recent_signals || [], ['id', 'channel', 'symbol', 'direction', 'entry_price', 'verification_status']);
}

async function loadSignals() {
  let f = await api('/api/v1/signals/filters' + (sf.category ? '?category=' + encodeURIComponent(sf.category) : ''));
  $('#category-filters').innerHTML = f.categories.map(c => `<button class="category-filter ${c === sf.category ? 'active' : ''}" data-cat="${esc(c)}">${esc(c)}</button>`).join('');
  let s = $('#channel-filter');
  s.innerHTML = '<option value="">All channels</option>' + f.channels.map(c => `<option>${esc(c)}</option>`).join('');
  s.value = sf.channel;
  $('#verification-filter').value = sf.status;
  if ($('#tp-filter')) $('#tp-filter').value = sf.tp_filter;
  if ($('#pnl-basis-filter')) $('#pnl-basis-filter').value = sf.pnl_basis;
  if ($('#signal-date-from')) $('#signal-date-from').value = sf.date_from;
  if ($('#signal-date-to')) $('#signal-date-to').value = sf.date_to;
  $('#notification-type-filter').value = sf.notification_filter;
  document.querySelectorAll('[data-cat]').forEach(b => b.onclick = () => { sf.category = sf.category === b.dataset.cat ? '' : b.dataset.cat; sf.channel = ''; loadSignals(); });
  let q = new URLSearchParams(sf);
  [...q].forEach(([k, v]) => !v && q.delete(k));
  const qs = q.size ? '?' + q : '';
  let [data, summary] = await Promise.all([
    api('/api/v1/signals' + qs),
    api('/api/v1/signals/summary' + qs)
  ]);
  const sumHost = $('#signals-pnl-summary');
  if (sumHost && summary) {
    const basisLbl = { tp1: 'TP1', tp2: 'TP2', tp3: 'TP3', highest_tp: 'Highest TP' }[summary.basis] || 'TP1';
    const inrCls = Number(summary.pnl_inr || 0) >= 0 ? 'green-txt' : 'red';
    const usdCls = Number(summary.pnl_usd || 0) >= 0 ? 'green-txt' : 'red';
    sumHost.innerHTML = `
      <div class="pnl-summary-metrics">
        <span>Signals: <b>${esc(summary.total_signals)}</b></span>
        <span>Win: <b>${esc(summary.win_rate)}%</b></span>
        <span>TP1: <b>${esc(summary.tp1_hits)}</b></span>
        <span>TP2: <b>${esc(summary.tp2_hits)}</b></span>
        <span>TP3: <b>${esc(summary.tp3_hits)}</b></span>
        <span>All TPs: <b>${esc(summary.all_tp_hits)}</b></span>
        <span>TP→SL: <b>${esc(summary.tp_then_sl_hits)}</b></span>
        <span>SL Only: <b>${esc(summary.sl_only_hits)}</b></span>
        <span>Active: <b>${esc(summary.active_monitoring)}</b></span>
      </div>
      <div class="pnl-summary-totals">
        <span class="muted" style="font-size:11px;font-weight:500">${esc(basisLbl)} P&amp;L:</span>
        <span class="${inrCls}">${esc(fmtPnl(summary.pnl_inr, 'INR'))}</span>
        ${Number(summary.pnl_usd || 0) !== 0 ? `<span class="${usdCls}">${esc(fmtPnl(summary.pnl_usd, 'USD'))}</span>` : ''}
      </div>`;
  }
  renderSignalTable(data);
  let selectCurrent = () => { data.forEach(x => selectedSignals.add(x.id)); renderSignalTable(data); };
  $('#select-all').onclick = selectCurrent;
  $('#select-filtered').onclick = selectCurrent;
  $('#clear-selection').onclick = () => { selectedSignals.clear(); renderSignalTable(data); };
  $('#reparse-selected').onclick = () => busy($('#reparse-selected'), 'Re-parsing...', async () => {
    let r = await api('/api/v1/signals/reparse', { method: 'POST', body: JSON.stringify({ signal_ids: [...selectedSignals] }) });
    $('#process-result').textContent = `Re-parsed ${r.results.length} selected signals.`;
    loadSignals();
  });
  $('#delete-selected').onclick = () => {
    const ids = [...selectedSignals];
    if (!ids.length || !confirm(`Delete ${ids.length} selected signal(s)?`)) return;
    busy($('#delete-selected'), 'Deleting...', async () => {
      const r = await api('/api/v1/signals/delete', { method: 'POST', body: JSON.stringify({ signal_ids: ids }) });
      ids.forEach(id => { selectedSignals.delete(id); expandedSignals.delete(id); });
      $('#process-result').textContent = `Deleted ${r.deleted} signal(s).`;
      loadSignals();
    });
  };
  $('#process-selected').onclick = () => busy($('#process-selected'), 'Processing...', async () => {
    let r = await api('/api/v1/signals/process', { method: 'POST', body: JSON.stringify({ signal_ids: [...selectedSignals] }) });
    $('#process-result').textContent = `Processed ${r.results.length} signal(s).`;
    selectedSignals.clear();
    loadSignals();
  });
}

async function integrations() {
  let a = await api('/api/v1/integrations'), by = Object.fromEntries(a.map(x => [x.provider, x]));
  [['google_sheets', $('#sheets-form')], ['binance', $('#binance-config')], ['twelve_data', $('#twelve-form')]].forEach(([id, f]) =>
    Object.entries(by[id]?.config || {}).forEach(([k, v]) => f[k] && (f[k].value = Array.isArray(v) ? v.join(', ') : v))
  );
}

async function coverage() {
  const rows = await api('/api/v1/market/coverage');
  const host = $('#coverage');
  if (!rows.length) { host.innerHTML = '<p class="muted">No cached candles yet.</p>'; return; }
  host.innerHTML = `<table><thead><tr><th>Provider</th><th>Symbol</th><th>Exchange</th><th>Interval</th><th>Candles</th><th>Earliest</th><th>Latest</th><th>Action</th></tr></thead><tbody>${rows.map(r => `<tr><td>${esc(r.provider)}</td><td>${esc(r.symbol)}</td><td>${esc(r.exchange)}</td><td>${esc(r.interval)}</td><td>${esc(r.candles)}</td><td>${esc(r.earliest)}</td><td>${esc(r.latest)}</td><td><button type="button" class="ch-btn btn-danger" data-del-cache="${esc(r.provider)}|${esc(r.symbol)}">Delete</button></td></tr>`).join('')}</tbody></table>`;
  host.querySelectorAll('[data-del-cache]').forEach(b => {
    b.onclick = async () => {
      const [prov, sym] = b.dataset.delCache.split('|');
      if (!confirm(`Delete cached candles for ${prov}:${sym}?`)) return;
      await api(`/api/v1/market/cache?provider=${encodeURIComponent(prov)}&symbol=${encodeURIComponent(sym)}`, { method: 'DELETE' });
      coverage();
    };
  });
}

const PROVIDER_INTERVALS = {
  angel_one: [['ONE_MINUTE', '1m (ONE_MINUTE)'], ['FIVE_MINUTE', '5m (FIVE_MINUTE)'], ['FIFTEEN_MINUTE', '15m (FIFTEEN_MINUTE)'], ['ONE_HOUR', '1h (ONE_HOUR)'], ['ONE_DAY', '1d (ONE_DAY)']],
  twelve_data: [['1min', '1min'], ['5min', '5min'], ['15min', '15min'], ['1h', '1h'], ['4h', '4h'], ['1day', '1day']],
  binance: [['1m', '1m'], ['3m', '3m'], ['5m', '5m'], ['15m', '15m'], ['30m', '30m'], ['1h', '1h'], ['4h', '4h'], ['1d', '1d']],
};

function syncResolvedSelection() {
  const p = $('#candle-provider').value, iv = $('#candle-interval').value;
  if (p === 'angel_one') {
    const opt = $('#ao-contract').options[$('#ao-contract').selectedIndex];
    $('#resolved-symbol').value = opt ? opt.value : '';
    $('#resolved-exchange').value = opt?.dataset?.exchange || ($('#ao-category').value.split('|')[0] || 'NFO');
    $('#resolved-preview').textContent = opt?.value ? `Angel One | ${$('#resolved-exchange').value}:${opt.value} (${opt.dataset.label || ''}) | ${iv}` : 'Refresh Angel One Master to select a contract.';
  } else if (p === 'twelve_data') {
    $('#resolved-symbol').value = $('#td-symbol').value; $('#resolved-exchange').value = 'FOREX';
    $('#resolved-preview').textContent = `Twelve Data | ${$('#td-symbol').value} | ${iv}`;
  } else {
    $('#resolved-symbol').value = $('#bn-symbol').value; $('#resolved-exchange').value = 'USD-M';
    $('#resolved-preview').textContent = `Binance USD-M | ${$('#bn-symbol').value} | ${iv}`;
  }
}

async function loadAngelCatalog(resetU = false, resetE = false) {
  const [exchange, instrument_type] = $('#ao-category').value.split('|');
  const params = new URLSearchParams({ exchange, instrument_type });
  if (!resetU && $('#ao-underlying').value) params.set('underlying', $('#ao-underlying').value);
  if (!resetE && $('#ao-expiry').value) params.set('expiry', $('#ao-expiry').value);
  if ($('#ao-filter').value.trim()) params.set('q', $('#ao-filter').value.trim());
  try {
    const data = await api('/api/v1/angel-one/catalog?' + params);
    $('#ao-underlying').innerHTML = (data.underlyings || []).length ? data.underlyings.map(u => `<option value="${esc(u)}">${esc(u)}</option>`).join('') : '<option value="">No underlyings</option>';
    if (data.selected_underlying) $('#ao-underlying').value = data.selected_underlying;
    $('#ao-expiry').innerHTML = (data.expiries || []).length ? data.expiries.map(e => `<option value="${esc(e)}">${esc(e)}</option>`).join('') : '<option value="">Spot / No Expiry</option>';
    if (data.selected_expiry) $('#ao-expiry').value = data.selected_expiry;
    $('#ao-contract').innerHTML = (data.contracts || []).length ? data.contracts.map(c => `<option value="${esc(c.instrument_token)}" data-exchange="${esc(c.exchange_segment)}" data-label="${esc(c.trading_symbol)}">${esc(c.trading_symbol)} (${esc(c.instrument_token)})</option>`).join('') : '<option value="">No matching contracts</option>';
  } catch (err) { $('#ao-contract').innerHTML = `<option value="">Error: ${esc(err.message)}</option>`; }
  syncResolvedSelection();
}

function updateProviderUI() {
  const p = $('#candle-provider').value;
  document.querySelectorAll('.ao-field').forEach(el => el.style.display = p === 'angel_one' ? 'grid' : 'none');
  document.querySelectorAll('.td-field').forEach(el => el.style.display = p === 'twelve_data' ? 'grid' : 'none');
  document.querySelectorAll('.bn-field').forEach(el => el.style.display = p === 'binance' ? 'grid' : 'none');
  const opts = PROVIDER_INTERVALS[p] || PROVIDER_INTERVALS.binance;
  $('#candle-interval').innerHTML = opts.map(([v, l]) => `<option value="${v}">${l}</option>`).join('');
  if (p === 'angel_one') loadAngelCatalog(false, false); else syncResolvedSelection();
}

let defaultSlState = [];
function renderDefaultSlCategories() {
  const host = $('#default-sl-categories');
  if (!host) return;
  host.innerHTML = defaultSlState.map((cat, cIdx) => {
    const isUsd = cat.sizing_mode === 'usd', u = cat.unit === 'pips' ? 'pips' : cat.unit === 'pct' ? '%' : 'pts';
    return `<div class="sl-cat-card"><div class="sl-cat-header"><div><h3>${esc(cat.title)}</h3><span class="muted" style="font-size:11px">${esc(cat.unit_label)}</span></div><div class="sl-cat-fallback-grid">
      <label>SL (${u})<input type="number" step="any" data-cat-field="category_default" data-cat-idx="${cIdx}" value="${esc(cat.category_default)}"></label>
      <label>TP1<input type="number" step="any" data-cat-field="tp1" data-cat-idx="${cIdx}" value="${esc(cat.tp1)}"></label>
      <label>TP2<input type="number" step="any" data-cat-field="tp2" data-cat-idx="${cIdx}" value="${esc(cat.tp2)}"></label>
      <label>TP3<input type="number" step="any" data-cat-field="tp3" data-cat-idx="${cIdx}" value="${esc(cat.tp3)}"></label>
      ${isUsd ? `<label>USD ($)<input type="number" step="any" data-cat-field="usd_amount" data-cat-idx="${cIdx}" value="${esc(cat.usd_amount)}"></label>` : `<label>Lots<input type="number" step="any" data-cat-field="lots" data-cat-idx="${cIdx}" value="${esc(cat.lots)}"></label><label>Lot Size<input type="number" step="any" data-cat-field="lot_size" data-cat-idx="${cIdx}" value="${esc(cat.lot_size)}"></label>`}
    </div></div><div class="sl-symbols-grid">${cat.symbols.map((it, sIdx) => `<div class="sl-sym-item"><div class="sl-sym-top"><b>${esc(it.label)}</b><span>${esc(it.hint || u)}</span></div><div class="sl-mini-grid">
      <label>SL<input type="number" step="any" data-sym-field="sl" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.sl)}"></label>
      <label>TP1<input type="number" step="any" data-sym-field="tp1" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.tp1)}"></label>
      <label>TP2<input type="number" step="any" data-sym-field="tp2" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.tp2)}"></label>
      <label>TP3<input type="number" step="any" data-sym-field="tp3" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.tp3)}"></label>
      ${isUsd ? `<label class="span-2">USD ($)<input type="number" step="any" data-sym-field="usd_amount" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.usd_amount)}"></label>` : `<label>Lots<input type="number" step="any" data-sym-field="lots" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.lots)}"></label><label>Lot Size<input type="number" step="any" data-sym-field="lot_size" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(it.lot_size)}"></label>`}
    </div></div>`).join('')}</div></div>`;
  }).join('');
  host.querySelectorAll('[data-cat-field]').forEach(inp => inp.oninput = () => { defaultSlState[+inp.dataset.catIdx][inp.dataset.catField] = inp.value; });
  host.querySelectorAll('[data-sym-field]').forEach(inp => inp.oninput = () => { defaultSlState[+inp.dataset.catIdx].symbols[+inp.dataset.symIdx][inp.dataset.symField] = inp.value; });
}

async function loadDefaultSlSettings() {
  const res = await api('/api/v1/settings/default-sl');
  defaultSlState = res.categories || [];
  renderDefaultSlCategories();
}

function numOrNull(v) { if (v === '' || v == null) return null; const n = Number(v); return isNaN(n) ? null : n; }

async function loadApiHealth() {
  const health = await api('/api/v1/api-health');
  const banner = $('#api-health-banner');
  if (banner) {
    const alerts = health.active_alerts || [];
    if (alerts.length) {
      const isErr = health.overall_status === 'error' || alerts.some(a => a.severity === 'error');
      banner.className = `api-alert-banner ${isErr ? 'alert-error' : ''}`;
      banner.style.display = 'flex';
      banner.innerHTML = `
        <div><b>API Health Alert (${alerts.length}):</b> ${esc(alerts[0].message)}${alerts.length > 1 ? ` (+${alerts.length - 1} more)` : ''}</div>
        <button type="button" class="ch-btn" id="jump-api-diagnostics">View Diagnostics</button>`;
      $('#jump-api-diagnostics').onclick = () => show('market');
    } else {
      banner.style.display = 'none';
      banner.innerHTML = '';
    }
  }

  const panel = $('#api-health-panel');
  if (panel) {
    const providers = health.providers || [];
    const u = health.twelve_data_usage || {};
    const minPct = Math.min(100, Math.round(((u.minute_used || 0) / Math.max(u.minute_allowance || 8, 1)) * 100));
    const dayPct = Math.min(100, Math.round(((u.daily_used || 0) / Math.max(u.daily_allowance || 800, 1)) * 100));
    const badgeFor = st => ({
      healthy: ['HEALTHY', 'badge-green'],
      rate_limited: ['RATE LIMITED', 'badge-red'],
      error: ['ERROR', 'badge-red'],
      degraded: ['DEGRADED', 'badge-orange'],
      unconfigured: ['UNCONFIGURED', 'badge-muted'],
    }[st] || [st, 'badge-muted']);
    panel.innerHTML = `
      <div class="health-grid">
        ${providers.map(p => {
          const [lbl, cls] = badgeFor(p.status);
          return `<div class="health-card">
            <div class="health-card-head"><b>${esc(p.display_name)}</b><span class="pill ${cls}">${esc(lbl)}</span></div>
            <div class="muted" style="font-size:11px">24h Calls: <b>${esc(p.calls_24h)}</b> &bull; Errors: <b>${esc(p.errors_24h)}</b> &bull; 429/Quota: <b>${esc(p.rate_limit_hits_24h)}</b></div>
            ${p.last_error_message ? `<div style="font-size:11px;color:${p.status === 'degraded' ? '#fbbf24' : '#ff7285'}">${esc(p.last_error_category || 'error')}${p.last_http_code ? ` (HTTP ${p.last_http_code})` : ''}: ${esc(p.last_error_message)}</div>` : '<div class="muted" style="font-size:11px">No recent errors.</div>'}
          </div>`;
        }).join('')}
      </div>
      <div class="quota-bar-wrap">
        <div class="muted" style="font-size:11px">Twelve Data Minute Quota: <b>${Math.round(u.minute_used || 0)} / ${Math.round(u.minute_allowance || 8)}</b> credits (${minPct}%)</div>
        <div class="quota-bar"><i style="width:${minPct}%;background:${minPct >= 90 ? '#ff7285' : minPct >= 70 ? '#f59e0b' : '#62d6a5'}"></i></div>
        <div class="muted" style="font-size:11px">Twelve Data Daily Quota: <b>${Math.round(u.daily_used || 0)} / ${Math.round(u.daily_allowance || 800)}</b> credits (${dayPct}%)</div>
        <div class="quota-bar"><i style="width:${dayPct}%;background:${dayPct >= 90 ? '#ff7285' : dayPct >= 75 ? '#f59e0b' : '#60a5fa'}"></i></div>
      </div>`;
  }

  const incHost = $('#api-incidents-table');
  if (incHost) {
    const incs = (health.recent_incidents || []).map(x => ({
      Time: fmtShortTime(x.requested_at),
      Provider: x.provider,
      Category: x.error_category,
      HTTP: x.http_code ?? '—',
      Endpoint: x.endpoint,
      Error: x.error,
    }));
    incHost.innerHTML = table(incs, ['Time', 'Provider', 'Category', 'HTTP', 'Endpoint', 'Error']);
  }
}

async function loadAnalytics() {
  const filters = await api('/api/v1/signals/filters' + (af.category ? '?category=' + encodeURIComponent(af.category) : ''));
  const catSel = $('#analytics-category');
  const chanSel = $('#analytics-channel');
  if (catSel) {
    catSel.innerHTML = '<option value="">All categories</option>' + filters.categories.map(c => `<option>${esc(c)}</option>`).join('');
    catSel.value = af.category;
  }
  if (chanSel) {
    chanSel.innerHTML = '<option value="">All channels</option>' + filters.channels.map(c => `<option>${esc(c)}</option>`).join('');
    chanSel.value = af.channel;
  }
  if ($('#analytics-date-from')) $('#analytics-date-from').value = af.date_from;
  if ($('#analytics-date-to')) $('#analytics-date-to').value = af.date_to;
  if ($('#analytics-pnl-basis')) $('#analytics-pnl-basis').value = af.pnl_basis;

  const q = new URLSearchParams(af);
  [...q].forEach(([k, v]) => !v && q.delete(k));
  const data = await api('/api/v1/analytics/channels' + (q.size ? '?' + q : ''));
  const basisLbl = { tp1: 'TP1', tp2: 'TP2', tp3: 'TP3', highest_tp: 'Best TP' }[af.pnl_basis] || 'TP1';
  const formatted = data.map(r => ({
    Channel: r.channel,
    Total: r.total,
    'Win %': `${r.win_rate ?? 0}%`,
    'TP1+': r.tp1_hits ?? r.targets ?? 0,
    'TP2+': r.tp2_hits ?? 0,
    'TP3+': r.tp3_hits ?? 0,
    'All TPs': r.all_tp_hits ?? 0,
    'TP→SL': r.tp_then_sl_hits ?? 0,
    'SL Only': r.stops || 0,
    [`${basisLbl} INR`]: fmtPnl(r.pnl_inr, 'INR'),
    [`${basisLbl} USD`]: fmtPnl(r.pnl_usd, 'USD'),
    'Today TP/SL': `${r.today_tp || 0}/${r.today_sl || 0}`,
  }));
  $('#analytics-table').innerHTML = table(formatted, ['Channel', 'Total', 'Win %', 'TP1+', 'TP2+', 'TP3+', 'All TPs', 'TP→SL', 'SL Only', `${basisLbl} INR`, `${basisLbl} USD`, 'Today TP/SL']);
}

async function loadLogs() {
  await loadApiHealth().catch(() => {});
  $('#logs-table').innerHTML = table(await api('/api/v1/logs'), ['created_at', 'severity', 'source', 'message']);
}

function show(view) {
  currentView = view;
  document.querySelectorAll('nav button,.view').forEach(x => x.classList.remove('active'));
  document.querySelector(`[data-view="${view}"]`).classList.add('active');
  $('#' + view).classList.add('active');
  $('#page-title').textContent = document.querySelector(`[data-view="${view}"]`).textContent;
  if (!authSessionToken) return;
  loadApiHealth().catch(() => {});
  ({
    overview, notifications: notificationLog, integrations, signals: loadSignals,
    market: async () => { await loadApiHealth(); await coverage(); updateProviderUI(); },
    analytics: loadAnalytics, logs: loadLogs,
    settings: async () => {
      let x = await api('/api/v1/settings');
      Object.entries(x).forEach(([k, v]) => $('#settings-form')[k] && ($('#settings-form')[k].value = v));
      await loadDefaultSlSettings();
    }
  }[view] || (() => {}))();
}

// Event bindings
$('#close-parse-review').onclick = () => $('#parse-review-dialog').close();
document.querySelectorAll('nav button').forEach(b => b.onclick = () => show(b.dataset.view));
$('#lock-session-btn').onclick = () => { authSessionToken = ''; showAuthModal('Session locked.'); };

$('#lan-auth-form').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#lan-pair-submit');
  const rawToken = ($('#lan-token-input').value || '').trim();
  $('#lan-auth-error').textContent = '';
  await busy(btn, 'Connecting...', async () => {
    try {
      const resp = await fetch('/api/v1/auth/pair', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'SignalAnalyzerDashboard' },
        body: JSON.stringify({ token: rawToken }),
      });
      const data = await resp.json();
      if (!resp.ok || !data.session_token) {
        $('#lan-auth-error').textContent = data.error || 'Invalid access token.';
        return;
      }
      authSessionToken = data.session_token;
      $('#lan-token-input').value = '';
      hideAuthModal();
      connectWebSocket();
      show(currentView);
    } catch (err) {
      $('#lan-auth-error').textContent = 'Could not reach Android host: ' + err.message;
    }
  });
};

$('#refresh-signals').onclick = () => busy($('#refresh-signals'), 'Refreshing...', loadSignals);
$('#channel-filter').onchange = e => { sf.channel = e.target.value; loadSignals(); };
$('#verification-filter').onchange = e => { sf.status = e.target.value; loadSignals(); };
if ($('#tp-filter')) $('#tp-filter').onchange = e => { sf.tp_filter = e.target.value; loadSignals(); };
if ($('#pnl-basis-filter')) $('#pnl-basis-filter').onchange = e => { sf.pnl_basis = e.target.value; loadSignals(); };
if ($('#signal-date-from')) $('#signal-date-from').onchange = e => { sf.date_from = e.target.value; loadSignals(); };
if ($('#signal-date-to')) $('#signal-date-to').onchange = e => { sf.date_to = e.target.value; loadSignals(); };
$('#notification-type-filter').onchange = e => { sf.notification_filter = e.target.value; loadSignals(); };
$('#clear-signal-filters').onclick = () => { sf = { category: '', channel: '', status: '', tp_filter: '', pnl_basis: 'tp1', date_from: '', date_to: '', notification_filter: 'primary' }; loadSignals(); };

if ($('#refresh-analytics')) $('#refresh-analytics').onclick = () => busy($('#refresh-analytics'), 'Refreshing...', loadAnalytics);
if ($('#analytics-category')) $('#analytics-category').onchange = e => { af.category = e.target.value; af.channel = ''; loadAnalytics(); };
if ($('#analytics-channel')) $('#analytics-channel').onchange = e => { af.channel = e.target.value; loadAnalytics(); };
if ($('#analytics-date-from')) $('#analytics-date-from').onchange = e => { af.date_from = e.target.value; loadAnalytics(); };
if ($('#analytics-date-to')) $('#analytics-date-to').onchange = e => { af.date_to = e.target.value; loadAnalytics(); };
if ($('#analytics-pnl-basis')) $('#analytics-pnl-basis').onchange = e => { af.pnl_basis = e.target.value; loadAnalytics(); };
if ($('#clear-analytics-filters')) $('#clear-analytics-filters').onclick = () => { af = { category: '', channel: '', date_from: '', date_to: '', pnl_basis: 'tp1' }; loadAnalytics(); };
if ($('#refresh-api-health')) $('#refresh-api-health').onclick = () => busy($('#refresh-api-health'), 'Refreshing...', loadApiHealth);

$('#refresh-notifications').onclick = () => busy($('#refresh-notifications'), 'Refreshing...', notificationLog);
['#notification-limit', '#notification-parsing-status', '#notification-log-type', '#notification-channel', '#notification-category', '#notification-date-from', '#notification-date-to'].forEach(id => { if ($(id)) $(id).onchange = notificationLog; });
$('#clear-notification-filters').onclick = () => {
  ['#notification-parsing-status', '#notification-channel', '#notification-category', '#notification-date-from', '#notification-date-to'].forEach(id => { if ($(id)) $(id).value = ''; });
  if ($('#notification-log-type')) $('#notification-log-type').value = 'all';
  $('#notification-limit').value = '50';
  notificationLog();
};

$('#sheets-form').onsubmit = async e => {
  e.preventDefault();
  let d = Object.fromEntries(new FormData(e.target));
  await api('/api/v1/integrations/google_sheets', { method: 'POST', body: JSON.stringify({ config: { apps_script_url: d.apps_script_url, worksheets: d.worksheets.split(',').map(x => x.trim()).filter(Boolean) }, secrets: { access_token: d.access_token } }) });
  await api('/api/v1/settings', { method: 'POST', body: JSON.stringify({ sync_interval_seconds: d.sync_interval_seconds, google_sheets_auto_sync: d.auto_sync }) });
  $('#sheets-result').textContent = 'Saved.';
};
$('#sheets-test').onclick = () => busy($('#sheets-test'), 'Syncing...', async () => { try { $('#sheets-result').textContent = JSON.stringify(await api('/api/v1/sync', { method: 'POST' })); } catch (e) { $('#sheets-result').textContent = e.message; } });
$('#sheets-history').onclick = $('#sheets-test').onclick;
$('#binance-config').onsubmit = async e => { e.preventDefault(); await api('/api/v1/integrations/binance', { method: 'POST', body: JSON.stringify({ config: Object.fromEntries(new FormData(e.target)), secrets: {} }) }); $('#binance-result').textContent = 'Saved.'; };
$('#binance-test').onclick = () => busy($('#binance-test'), 'Testing...', async () => { try { $('#binance-result').textContent = JSON.stringify(await api('/api/v1/binance/test', { method: 'POST' })); } catch (e) { $('#binance-result').textContent = e.message; } });
$('#twelve-form').onsubmit = async e => {
  e.preventDefault(); let d = Object.fromEntries(new FormData(e.target));
  await api('/api/v1/integrations/twelve_data', { method: 'POST', body: JSON.stringify({ config: { base_url: d.base_url, symbols: d.symbols.split(',').map(x => x.trim()).filter(Boolean), timezone: d.timezone, outputsize: 5000 }, secrets: { api_key: d.api_key } }) });
  $('#twelve-result').textContent = 'Saved; API key is encrypted in Android Keystore.';
};
$('#angel-form').onsubmit = async e => { e.preventDefault(); await api('/api/v1/integrations/angel_one', { method: 'POST', body: JSON.stringify({ config: {}, secrets: Object.fromEntries(new FormData(e.target)) }) }); $('#angel-result').textContent = 'Saved; credentials encrypted in Android Keystore.'; };
$('#angel-test').onclick = async () => { try { $('#angel-result').textContent = JSON.stringify(await api('/api/v1/angel-one/test', { method: 'POST' })); } catch (e) { $('#angel-result').textContent = e.message; } };
$('#master-refresh').onclick = async () => { try { $('#angel-result').textContent = JSON.stringify(await api('/api/v1/angel-one/instruments/refresh', { method: 'POST' })); } catch (e) { $('#angel-result').textContent = e.message; } };
$('#market-master-refresh').onclick = () => busy($('#market-master-refresh'), 'Refreshing...', async () => { try { const r = await api('/api/v1/angel-one/instruments/refresh', { method: 'POST' }); $('#candle-result').textContent = `Updated ${r.count} instruments.`; await loadAngelCatalog(true, true); } catch (e) { $('#candle-result').textContent = e.message; } });
$('#candle-provider').onchange = updateProviderUI;
$('#ao-category').onchange = () => { $('#ao-filter').value = ''; loadAngelCatalog(true, true); };
$('#ao-underlying').onchange = () => { $('#ao-filter').value = ''; loadAngelCatalog(false, true); };
$('#ao-expiry').onchange = () => loadAngelCatalog(false, false);
let aoTimer = null;
$('#ao-filter').oninput = () => { clearTimeout(aoTimer); aoTimer = setTimeout(() => loadAngelCatalog(false, false), 250); };
$('#ao-contract').onchange = syncResolvedSelection;
$('#td-symbol').onchange = syncResolvedSelection;
$('#bn-symbol').onchange = syncResolvedSelection;
$('#candle-interval').onchange = syncResolvedSelection;

$('#candle-form').onsubmit = async e => {
  e.preventDefault(); syncResolvedSelection();
  let d = Object.fromEntries(new FormData(e.target));
  let url = d.provider === 'twelve_data' ? '/api/v1/twelve-data/candles' : d.provider === 'angel_one' ? '/api/v1/angel-one/candles' : '/api/v1/candles/fetch';
  let body = d.provider === 'twelve_data' ? { symbol: d.symbol, interval: d.interval, start: d.start, end: d.end }
    : d.provider === 'angel_one' ? { exchange: d.exchange, instrument_token: d.symbol, interval: d.interval, start: d.start, end: d.end }
    : { provider: 'binance', symbol: d.symbol, timeframe: d.interval, start: d.start, end: d.end };
  try {
    $('#candle-result').textContent = JSON.stringify(await api(url, { method: 'POST', body: JSON.stringify(body) }));
    if (d.provider === 'twelve_data') {
      let u = await api('/api/v1/twelve-data/usage');
      $('#twelve-usage').textContent = `Twelve Data credits: ${u.daily_used}/${u.daily_allowance} today; ${u.minute_used}/${u.minute_allowance} this minute.`;
    }
    coverage();
  } catch (err) { $('#candle-result').textContent = err.message; }
};

$('#instrument-form').onsubmit = async e => {
  e.preventDefault();
  let x = await api('/api/v1/angel-one/instruments?' + new URLSearchParams(Object.fromEntries(new FormData(e.target))));
  $('#instruments').innerHTML = table(x, ['trading_symbol', 'instrument_token', 'exchange_segment', 'instrument_type', 'expiry', 'strike', 'lot_size']);
};

$('#parse-preview').onclick = async () => {
  const res = await api('/api/v1/parse', { method: 'POST', body: JSON.stringify({ message: $('#parse-message').value, category: $('#parse-category')?.value || '' }) });
  $('#parse-output').textContent = JSON.stringify(res, null, 2);
};

$('#settings-form').onsubmit = async e => {
  e.preventDefault();
  await api('/api/v1/settings', { method: 'POST', body: JSON.stringify(Object.fromEntries(new FormData(e.target))) });
  alert('Settings saved');
};

$('#save-default-sl').onclick = () => busy($('#save-default-sl'), 'Saving...', async () => {
  const rules = {};
  for (const cat of defaultSlState) {
    const syms = {};
    for (const it of cat.symbols) syms[it.key] = { sl: numOrNull(it.sl), tp1: numOrNull(it.tp1), tp2: numOrNull(it.tp2), tp3: numOrNull(it.tp3), lots: numOrNull(it.lots), lot_size: numOrNull(it.lot_size), usd_amount: numOrNull(it.usd_amount) };
    rules[cat.key] = { category_default: numOrNull(cat.category_default), tp1: numOrNull(cat.tp1), tp2: numOrNull(cat.tp2), tp3: numOrNull(cat.tp3), lots: numOrNull(cat.lots) || 1, lot_size: numOrNull(cat.lot_size), usd_amount: numOrNull(cat.usd_amount) || 100, symbols: syms };
  }
  await api('/api/v1/settings/default-sl', { method: 'POST', body: JSON.stringify({ rules }) });
  $('#default-sl-result').textContent = 'Default SL, TP1–TP3, and Position Sizing rules saved.';
  await loadDefaultSlSettings();
});

$('#apply-default-sl-existing').onclick = () => busy($('#apply-default-sl-existing'), 'Applying...', async () => {
  const res = await api('/api/v1/settings/default-sl/apply-missing', { method: 'POST' });
  $('#default-sl-result').textContent = `Checked ${res.checked} signals — updated ${res.updated}.`;
});

document.querySelectorAll('[data-export]').forEach(btn => {
  btn.onclick = async () => {
    const kind = btn.dataset.export;
    try {
      const resp = await fetch(`/api/v1/export/${kind}.csv`, {
        headers: { 'Authorization': 'Bearer ' + authSessionToken, 'X-Requested-With': 'SignalAnalyzerDashboard' }
      });
      if (!resp.ok) throw Error('Export failed (' + resp.status + ')');
      const blob = await resp.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `${kind}.csv`; a.click();
      URL.revokeObjectURL(url);
    } catch (e) { $('#backup-result').textContent = e.message; }
  };
});

$('#backup').onclick = async () => { $('#backup-result').textContent = JSON.stringify(await api('/api/v1/backup', { method: 'POST' }), null, 2); };

{
  let f = $('#candle-form'), now = new Date(),
    end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 15, 30),
    start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 9, 15),
    localFmt = d => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  if (f && !f.start.value) f.start.value = localFmt(start);
  if (f && !f.end.value) f.end.value = localFmt(end);
}

showAuthModal('');
