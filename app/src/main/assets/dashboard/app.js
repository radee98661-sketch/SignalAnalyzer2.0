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
          if (!$('#review-drawer-backdrop')?.classList.contains('open')) {
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
let signalSearchQuery = '';
let af = { category: '', channel: '', date_from: '', date_to: '', pnl_basis: 'tp1' };
let selectedSignals = new Set();
let expandedSignals = new Set();
let chartCache = new Map();
let lastSignalsData = [];
let lastSignalsSummary = null;
let reviewQueueIds = [];
let reviewQueueIdx = 0;
let reviewQueueMode = false;

function fmtCompactPrice(v) {
  if (v == null || v === '' || isNaN(Number(v))) return '—';
  const n = Number(v);
  const a = Math.abs(n);
  if (a >= 1000) return Number.isInteger(n) ? String(n) : n.toFixed(2);
  if (a >= 10) return Number(n.toFixed(2)).toString();
  if (a >= 1) return Number(n.toFixed(4)).toString();
  return Number(n.toFixed(5)).toString();
}

function isEditedSignal(r) {
  return String(r?.parsing_explanation || '').toLowerCase().includes('edited');
}

function buildSignalLadderHtml(r, compact = false) {
  const status = r.verification_status || '';
  const isSlHit = status === 'stop_loss_hit';
  const isSlAfterTp = Boolean(r.closed_by_sl_after_tp);
  const isEntryActive = status === 'entry_hit';
  const isEntryReached = isEntryActive || status === 'target_hit' || isSlHit || Number(r.trades_count || 0) > 0;
  const isMarketNow = r.entry_mode === 'market_now';
  const targets = Array.isArray(r.targets) ? r.targets : [];
  const shownTargets = targets.slice(0, 3);
  const extraCount = Math.max(0, targets.length - 3);
  const maxTpHit = Number(r.max_tp_hit || 0);

  const nodes = [
    {
      kind: 'sl',
      label: isSlAfterTp ? 'SL*' : 'SL',
      price: fmtCompactPrice(r.stop_loss),
      hit: isSlHit,
      active: false,
      warn: isSlAfterTp,
    },
    {
      kind: 'entry',
      label: isMarketNow ? 'MKT' : 'ENTRY',
      price: r.entry_price != null ? fmtCompactPrice(r.entry_price) : (isMarketNow ? 'MKT NOW' : '—'),
      hit: isEntryReached,
      active: isEntryActive,
      warn: false,
    },
  ];
  if (!shownTargets.length) {
    nodes.push({ kind: 'tp', label: 'TP1', price: '—', hit: false, active: false, warn: false });
  } else {
    shownTargets.forEach((p, i) => {
      const n = i + 1;
      const isHit = maxTpHit >= n || (status === 'target_hit' && n === 1 && maxTpHit === 0);
      nodes.push({
        kind: 'tp',
        label: (i === 2 && extraCount > 0) ? `TP3+${extraCount}` : `TP${n}`,
        price: fmtCompactPrice(p),
        hit: isHit,
        active: false,
        warn: false,
      });
    });
  }

  function segClass(left, right) {
    if (left.kind === 'sl' && left.hit) return 'lit-sl';
    if (left.kind === 'sl' && left.warn) return 'lit-warn';
    if (right.kind === 'tp' && right.hit) return 'lit-tp';
    if (left.kind === 'entry' && left.active && right.kind === 'tp') return 'lit-active';
    return '';
  }

  const stepsHtml = nodes.map((nd, idx) => {
    const leftSeg = idx > 0 ? `<span class="ladder-seg left ${segClass(nodes[idx - 1], nd)}"></span>` : '';
    const rightSeg = idx < nodes.length - 1 ? `<span class="ladder-seg right ${segClass(nd, nodes[idx + 1])}"></span>` : '';
    const dotCls = `ladder-dot kind-${nd.kind} ${nd.hit ? 'hit' : ''} ${nd.active ? 'active' : ''} ${nd.warn ? 'warn' : ''}`.trim();
    const lblCls = nd.kind === 'sl' ? (nd.hit ? 'lbl-sl-hit' : nd.warn ? 'lbl-warn' : 'lbl-sl')
      : nd.kind === 'entry' ? (nd.hit || nd.active ? 'lbl-entry' : '')
      : nd.hit ? 'lbl-tp-hit' : '';
    const priceCls = (nd.kind === 'tp' && nd.hit) ? 'price-hit'
      : (nd.kind === 'sl' && nd.hit) ? 'price-sl'
      : nd.warn ? 'price-warn'
      : nd.price === '—' ? 'price-muted' : '';
    const displayLbl = (nd.kind === 'tp' && nd.hit) ? `${nd.label} ✓` : nd.label;
    return `
      <div class="ladder-step">
        <div class="ladder-track-row">${leftSeg}${rightSeg}<span class="${dotCls}"></span></div>
        <span class="ladder-lbl ${lblCls}">${esc(displayLbl)}</span>
        <span class="ladder-price ${priceCls}">${esc(nd.price)}</span>
      </div>`;
  }).join('');

  return `<div class="tp-ladder ${compact ? 'compact' : ''}"><div class="ladder-nodes">${stepsHtml}</div></div>`;
}

function updateBatchBar() {
  const bar = $('#batch-action-bar');
  const lbl = $('#batch-count-label');
  if (!bar) return;
  const count = selectedSignals.size;
  if (count > 0 && currentView === 'signals') {
    bar.classList.remove('hidden');
    if (lbl) lbl.textContent = `${count} selected`;
  } else {
    bar.classList.add('hidden');
  }
}

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

function renderCandlestickChart(container, chartData, hoverOut, signalObj = null) {
  const candles = chartData.candles || [];
  if (!candles.length) {
    const label = chartData.trading_symbol ? `${chartData.trading_symbol} (token ${chartData.resolved_symbol})` : (chartData.resolved_symbol || chartData.symbol || '');
    const reasonHtml = chartData.detail ? `<div style="margin-top:6px;color:#ffbb66">${esc(chartData.detail)}</div>` : '';
    container.innerHTML = `<div class="chart-empty">No cached 1m candles yet for <b>${esc(label)}</b> (${esc(chartData.provider)}).${reasonHtml}</div>`;
    return;
  }
  const fsHeaderHtml = signalObj ? `
    <div class="chart-fs-header">
      <div class="chart-fs-top">
        <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
          <strong style="font-size:16px;color:#fff">${esc(signalObj.symbol || chartData.trading_symbol || chartData.resolved_symbol)}</strong>
          ${signalObj.direction ? `<span class="dir-pill ${signalObj.direction === 'BUY' ? 'dir-buy' : 'dir-sell'}">${esc(signalObj.direction)}</span>` : ''}
          ${statusBadge(signalObj.verification_status, signalObj.is_manual_needed, signalObj.is_auto_scheduled, signalObj.closed_by_sl_after_tp, signalObj.max_tp_hit)}
        </div>
        <span class="muted" style="font-size:11px">Scroll to zoom &bull; Drag to pan &bull; Double-click to reset &bull; Esc to exit</span>
      </div>
      ${buildSignalLadderHtml(signalObj, true)}
    </div>` : '';
  container.innerHTML = `
    ${fsHeaderHtml}
    <div class="chart-toolbar">
      <div class="chart-btn-group">
        <button type="button" class="ch-btn" data-act="zoom-in">+</button>
        <button type="button" class="ch-btn" data-act="zoom-out">&minus;</button>
        <button type="button" class="ch-btn" data-act="focus">Focus Trade</button>
        <button type="button" class="ch-btn" data-act="fit">Fit All</button>
      </div>
      <button type="button" class="ch-btn" data-act="fullscreen">&#x26F6; Full Screen</button>
    </div>
    <div class="chart-fs-readout" style="display:none;font-size:11px;color:#9fb0cf;padding:2px 4px;"></div>
    <div class="chart-canvas-holder" style="flex:1;min-height:0;width:100%;">
      <canvas class="ohlc-canvas"></canvas>
      <div class="chart-hud"></div>
    </div>`;
  const toolbar = container.querySelector('.chart-toolbar');
  const fsHeader = container.querySelector('.chart-fs-header');
  const fsReadout = container.querySelector('.chart-fs-readout');
  const fsBtn = container.querySelector('[data-act="fullscreen"]');
  const holder = container.querySelector('.chart-canvas-holder');
  const hud = container.querySelector('.chart-hud');
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
      const topChrome = (fsHeader?.offsetHeight || 0) + (toolbar?.offsetHeight || 36) + (fsReadout?.offsetHeight || 20) + 34;
      return { width: Math.max(holder.clientWidth || window.innerWidth - 24, 260), height: Math.max(holder.clientHeight || (window.innerHeight - topChrome), 260) };
    }
    return { width: Math.max(holder.clientWidth || container.clientWidth || 320, 260), height: 280 };
  }
  function updateHud(startIdx, endIdx) {
    if (!hud) return;
    if (activeIdx < startIdx || activeIdx > endIdx || activeIdx < 0 || activeIdx >= candles.length) {
      hud.style.display = 'none';
      return;
    }
    const c = candles[activeIdx];
    const isBull = c.close >= c.open;
    const isLeftHalf = (activeIdx - viewStart) < (visibleCount * 0.5);
    hud.classList.toggle('hud-right', isLeftHalf);
    let deltaHtml = '';
    const entryRef = Number(chartData.entry_price);
    if (chartData.entry_price != null && !isNaN(entryRef) && entryRef > 0) {
      const isSell = String(chartData.direction || '').toUpperCase() === 'SELL';
      const diff = isSell ? (entryRef - c.close) : (c.close - entryRef);
      const pct = (diff / entryRef) * 100;
      const sign = diff >= 0 ? '+' : '';
      deltaHtml = `<div class="chart-hud-delta ${diff >= 0 ? 'green-txt' : 'red'}">${sign}${pct.toFixed(2)}% vs Entry</div>`;
    }
    const evList = markersByIdx.get(activeIdx) || [];
    const evHtml = evList.length ? `<div class="chart-hud-note">&#x2605; ${esc(evList.map(e => e.detailText).join(' • '))}</div>` : '';
    hud.innerHTML = `
      <div class="chart-hud-head">
        <span>${esc(fmtShortTime(c.candle_timestamp))}</span>
        <span class="${isBull ? 'green-txt' : 'red'}">${isBull ? 'BULL' : 'BEAR'}</span>
      </div>
      <div class="chart-hud-ohlc">O:${esc(fmtCompactPrice(c.open))} H:${esc(fmtCompactPrice(c.high))}</div>
      <div class="chart-hud-ohlc">L:${esc(fmtCompactPrice(c.low))} <b class="${isBull ? 'green-txt' : 'red'}">C:${esc(fmtCompactPrice(c.close))}</b></div>
      ${deltaHtml}${evHtml}`;
    hud.style.display = 'block';
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
    const pad = { top: 22, right: 72, bottom: 26, left: 10 };
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
    ctx.font = '10px ui-monospace, monospace'; ctx.textAlign = 'left';
    for (let g = 0; g <= 4; g++) {
      const p = minP + ((maxP - minP) * g) / 4, y = yOf(p);
      ctx.strokeStyle = '#1b2742'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(width - pad.right, y); ctx.stroke();
      ctx.fillStyle = '#7f91b0'; ctx.fillText(fmtCompactPrice(p), width - pad.right + 5, y + 3);
    }
    ctx.save(); ctx.beginPath(); ctx.rect(pad.left, pad.top - 14, plotW, plotH + 14); ctx.clip();
    function drawLevel(price, label, color) {
      if (price == null || isNaN(Number(price))) return;
      const num = Number(price); if (num < minP || num > maxP) return;
      const y = yOf(num);
      ctx.save(); ctx.setLineDash([4, 4]); ctx.strokeStyle = color; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(pad.left, y); ctx.lineTo(width - pad.right, y); ctx.stroke(); ctx.restore();
      ctx.fillStyle = color; ctx.font = 'bold 10px ui-monospace, monospace'; ctx.fillText(`${label}: ${fmtCompactPrice(num)}`, pad.left + 4, Math.max(12, y - 3)); ctx.font = '10px ui-monospace, monospace';
    }
    drawLevel(chartData.entry_price, 'E1', '#60a5fa');
    (chartData.additional_entries || []).forEach((eVal, i) => drawLevel(eVal, `E${i + 2}`, '#60a5fa'));
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
      ctx.save(); ctx.setLineDash([3, 3]); ctx.strokeStyle = 'rgba(231,237,249,0.75)'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(cx, pad.top); ctx.lineTo(cx, height - pad.bottom); ctx.moveTo(pad.left, cy); ctx.lineTo(width - pad.right, cy); ctx.stroke(); ctx.restore();
      ctx.fillStyle = '#60a5fa'; ctx.beginPath(); ctx.arc(cx, cy, 3.5, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
    // Highlighted Axis Pills when hovering, else default start/end labels
    if (activeIdx >= startIdx && activeIdx <= endIdx) {
      const c = candles[activeIdx], cx = xOf(activeIdx), cy = yOf(c.close);
      const pTxt = fmtCompactPrice(c.close);
      ctx.fillStyle = '#60a5fa';
      const pillY = Math.max(pad.top, Math.min(height - pad.bottom - 16, cy - 8));
      ctx.fillRect(width - pad.right + 2, pillY, pad.right - 4, 16);
      ctx.fillStyle = '#061324'; ctx.font = 'bold 10px ui-monospace, monospace';
      ctx.fillText(pTxt, width - pad.right + 5, pillY + 11);

      const tTxt = fmtShortTime(c.candle_timestamp);
      const tW = ctx.measureText(tTxt).width + 10;
      const tX = Math.max(pad.left, Math.min(width - pad.right - tW, cx - tW / 2));
      ctx.fillStyle = '#e7edf9';
      ctx.fillRect(tX, height - pad.bottom + 4, tW, 16);
      ctx.fillStyle = '#061324';
      ctx.fillText(tTxt, tX + 5, height - pad.bottom + 15);
    } else {
      ctx.fillStyle = '#7f91b0'; ctx.font = '10px ui-monospace, monospace';
      ctx.fillText(fmtShortTime(candles[startIdx].candle_timestamp), pad.left, height - 8);
      const endLbl = fmtShortTime(candles[endIdx].candle_timestamp);
      ctx.fillText(endLbl, Math.max(pad.left, width - pad.right - ctx.measureText(endLbl).width), height - 8);
    }
    updateHud(startIdx, endIdx);
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
    const plotW = Math.max(rect.width - 82, 40);
    return Math.max(0, Math.min(candles.length - 1, Math.floor(viewStart + ((clientX - rect.left - 10) / plotW) * visibleCount)));
  }
  function toggleFullscreen(forceExit = false) {
    const enable = forceExit ? false : !container.classList.contains('chart-fullscreen');
    if (enable) {
      if (container.parentNode !== document.body && container.parentNode) { container.parentNode.insertBefore(placeholder, container); document.body.appendChild(container); }
      container.classList.add('chart-fullscreen'); fsReadout.style.display = 'block'; fsBtn.innerHTML = '&#x2715; Exit Full';
    } else if (container.classList.contains('chart-fullscreen')) {
      container.classList.remove('chart-fullscreen'); fsReadout.style.display = 'none'; fsBtn.innerHTML = '&#x26F6; Full Screen';
      if (placeholder.parentNode) { placeholder.parentNode.insertBefore(container, placeholder); placeholder.parentNode.removeChild(placeholder); }
    }
    requestAnimationFrame(draw);
  }
  container._exitFullscreen = () => toggleFullscreen(true);
  container.querySelectorAll('.ch-btn').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const act = btn.dataset.act;
      if (act === 'zoom-in') zoomBy(0.65);
      else if (act === 'zoom-out') zoomBy(1.5);
      else if (act === 'focus') { focusTradeRange(); draw(); }
      else if (act === 'fit') { fitAllRange(); draw(); }
      else if (act === 'fullscreen') toggleFullscreen(false);
    };
  });
  let mouseDown = false, dragStartX = 0, dragStartView = 0;
  canvas.addEventListener('mousedown', e => { mouseDown = true; dragStartX = e.clientX; dragStartView = viewStart; });
  window.addEventListener('mousemove', e => {
    if (!mouseDown) return;
    includeAllLevelsInScale = false;
    viewStart = dragStartView - ((e.clientX - dragStartX) / Math.max(canvas.clientWidth - 82, 40)) * visibleCount;
    clampViewport(); draw();
  });
  window.addEventListener('mouseup', () => { mouseDown = false; });
  canvas.addEventListener('mousemove', e => { if (!mouseDown) { activeIdx = clientXToCandleIdx(e.clientX); draw(); } });
  canvas.addEventListener('mouseleave', () => { if (!mouseDown) { activeIdx = -1; draw(); } });
  canvas.addEventListener('dblclick', () => { activeIdx = -1; fitAllRange(); draw(); });
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const plotW = Math.max(rect.width - 82, 40);
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left - 10) / plotW));
    zoomBy(e.deltaY > 0 ? 1.25 : 0.8, ratio);
  }, { passive: false });
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
    const sigObj = lastSignalsData.find(x => x.id === signalId) || null;
    renderCandlestickChart(wrap, data, info, sigObj);
  } catch (e) {
    wrap.innerHTML = `<p class="red" style="padding:12px">Chart error: ${esc(e.message)}</p>`;
  }
}

function renderSignalCard(r) {
  const isExpanded = expandedSignals.has(r.id);
  const isChecked = selectedSignals.has(r.id);
  const dirClass = r.direction === 'BUY' ? 'dir-buy' : r.direction === 'SELL' ? 'dir-sell' : 'dir-none';
  const isMarketNow = r.entry_mode === 'market_now';
  const isEdited = isEditedSignal(r);
  const needsReview = r.parsing_status !== 'parsed';
  const targetsText = Array.isArray(r.targets) && r.targets.length ? r.targets.join(' / ') : '—';
  const optionMeta = (r.strike_price || r.option_type) ? ` ${r.strike_price || ''} ${r.option_type || ''}` : '';
  return `
  <div class="signal-card ${isExpanded ? 'open' : ''} ${needsReview ? 'card-needs-review' : ''}" data-card-id="${r.id}">
    <div class="signal-card-header" data-toggle-id="${r.id}">
      <label class="card-check" onclick="event.stopPropagation()">
        <input class="signal-select" data-id="${r.id}" type="checkbox" ${isChecked ? 'checked' : ''}>
      </label>
      <div class="card-main-info">
        <div class="card-top-row">
          <span class="card-id">#${r.id}</span>
          <strong class="card-symbol">${esc(r.symbol || 'UNPARSED')}${esc(optionMeta)}</strong>
          ${r.direction ? `<span class="dir-pill ${dirClass}">${esc(r.direction)}</span>` : ''}
          ${isMarketNow ? '<span class="micro-badge micro-mkt">MKT</span>' : ''}
          ${isEdited ? '<span class="micro-badge micro-edited">EDITED</span>' : ''}
          <span class="cat-pill">${esc(r.category || '')}</span>
          <span class="card-channel">${esc(r.channel || '')}</span>
          <span class="card-time-meta">${esc(fmtShortTime(r.notification_time))} &bull; ${esc(r.age_minutes)}m ago</span>
        </div>
        ${buildSignalLadderHtml(r)}
        ${levelPnlStripHtml(r, sf.pnl_basis)}
      </div>
      <div class="card-right-badges">
        ${pnlBadge(r.basis_pnl_amount ?? r.pnl_amount, r.pnl_currency, r.trades_count)}
        ${phaseBadge(r.monitoring_phase)}
        ${needsReview ? `<button type="button" class="quick-fix-chip" data-quick-fix="${r.id}">Quick Fix &#x270E;</button>` : ''}
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
              <button type="button" class="chart-refresh-btn" data-review-card="${r.id}">Quick Fix / Edit</button>
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

function getFilteredVisibleSignals() {
  const q = signalSearchQuery.trim().toLowerCase();
  if (!q) return lastSignalsData;
  return lastSignalsData.filter(r =>
    String(r.symbol || '').toLowerCase().includes(q) ||
    String(r.channel || '').toLowerCase().includes(q) ||
    String(r.category || '').toLowerCase().includes(q) ||
    String(r.message || '').toLowerCase().includes(q) ||
    (`#${r.id}`).includes(q)
  );
}

function renderSignalTable(data) {
  const container = $('#signals-list');
  updateBatchBar();
  if (!data.length) {
    container.innerHTML = `<div class="review-empty-state"><p class="muted" style="margin:0">No signals match the active filters or search.</p></div>`;
    return;
  }
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
  document.querySelectorAll('.signal-select').forEach(x => {
    x.onchange = () => {
      if (x.checked) selectedSignals.add(+x.dataset.id); else selectedSignals.delete(+x.dataset.id);
      updateBatchBar();
    };
  });
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
  document.querySelectorAll('[data-quick-fix]').forEach(btn => {
    btn.onclick = e => { e.stopPropagation(); reviewParse(+btn.dataset.quickFix, null); };
  });
  document.querySelectorAll('[data-review-card]').forEach(btn => {
    btn.onclick = e => { e.stopPropagation(); reviewParse(+btn.dataset.reviewCard, null); };
  });
  document.querySelectorAll('.chart-refresh-btn[data-chart-id]').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const id = +btn.dataset.chartId;
      busy(btn, 'Fetching...', async () => { await loadCardChart(id, true); await loadSignals(); });
    };
  });
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
  signalSearchQuery = '';
  if ($('#signal-search')) $('#signal-search').value = '';
  expandedSignals.add(Number(signalId));
  show('signals');
}

function closeReviewDrawer() {
  const backdrop = $('#review-drawer-backdrop');
  if (backdrop) backdrop.classList.remove('open');
  reviewQueueMode = false;
  reviewQueueIds = [];
  reviewQueueIdx = 0;
}

function openReviewQueue() {
  const queue = lastSignalsData.filter(r => r.parsing_status === 'needs_review').map(r => r.id);
  reviewQueueMode = true;
  reviewQueueIds = queue;
  reviewQueueIdx = 0;
  if (!queue.length) {
    renderReviewDrawerEmpty();
  } else {
    reviewParse(queue[0], queue);
  }
}

function renderReviewDrawerEmpty() {
  const backdrop = $('#review-drawer-backdrop');
  const panel = $('#parse-review');
  const badge = $('#review-drawer-badge');
  const title = $('#review-drawer-title');
  if (backdrop) backdrop.classList.add('open');
  if (badge) { badge.className = 'pill badge-green'; badge.textContent = 'All Caught Up'; }
  if (title) title.textContent = 'Review Queue Complete';
  if ($('#review-prev-btn')) $('#review-prev-btn').style.display = 'none';
  if ($('#review-next-btn')) $('#review-next-btn').style.display = 'none';
  if (panel) {
    panel.innerHTML = `
      <div class="review-empty-state">
        <div class="review-empty-check">&#x2713;</div>
        <h3 style="margin:0">0 signals need review</h3>
        <p class="muted" style="margin:0">Every queued signal has been fixed and sent for verification.</p>
        <button type="button" class="primary" id="review-empty-done" style="margin-top:10px">Done</button>
      </div>`;
    $('#review-empty-done').onclick = closeReviewDrawer;
  }
}

async function reviewParse(id, queueIds = null) {
  const backdrop = $('#review-drawer-backdrop');
  const panel = $('#parse-review');
  const badge = $('#review-drawer-badge');
  const title = $('#review-drawer-title');
  const prevBtn = $('#review-prev-btn');
  const nextBtn = $('#review-next-btn');

  if (Array.isArray(queueIds)) {
    reviewQueueMode = true;
    reviewQueueIds = queueIds;
    reviewQueueIdx = Math.max(0, queueIds.indexOf(id));
  } else if (queueIds === null) {
    reviewQueueMode = false;
    reviewQueueIds = [id];
    reviewQueueIdx = 0;
  }

  if (backdrop) backdrop.classList.add('open');
  panel.innerHTML = '<p class="muted">Loading signal quick-fix drawer...</p>';

  if (prevBtn && nextBtn) {
    const showNav = reviewQueueMode && reviewQueueIds.length > 1;
    prevBtn.style.display = showNav ? 'inline-block' : 'none';
    nextBtn.style.display = showNav ? 'inline-block' : 'none';
    prevBtn.disabled = reviewQueueIdx <= 0;
    nextBtn.disabled = reviewQueueIdx >= reviewQueueIds.length - 1;
    prevBtn.onclick = () => { if (reviewQueueIdx > 0) { reviewQueueIdx--; reviewParse(reviewQueueIds[reviewQueueIdx], reviewQueueIds); } };
    nextBtn.onclick = () => { if (reviewQueueIdx < reviewQueueIds.length - 1) { reviewQueueIdx++; reviewParse(reviewQueueIds[reviewQueueIdx], reviewQueueIds); } };
  }

  try {
    const x = await api('/api/v1/signals/' + id + '/parse-detail');
    const s = x.signal;
    const isOpt = /options/i.test(s.category || '') || Boolean(s.option_type) || s.strike_price != null;
    let curDir = (s.direction || '').toUpperCase();
    let curOptType = (s.option_type || '').toUpperCase();
    let useMarketNow = s.entry_mode === 'market_now' && s.entry_price == null;

    if (badge) {
      badge.className = `pill ${s.parsing_status === 'needs_review' ? 'badge-orange' : 'badge-blue'}`;
      badge.textContent = reviewQueueMode ? `Review ${reviewQueueIdx + 1} / ${reviewQueueIds.length}` : `Quick Fix #${s.id}`;
    }
    if (title) title.textContent = `${s.symbol || 'UNPARSED'} • ${s.category || ''}`;

    panel.innerHTML = `
      <div class="review-raw-card">
        <div class="review-raw-meta">
          <span><b>${esc(s.channel || 'Unknown')}</b> &bull; ${esc(fmtDateTime(s.notification_time))}</span>
          <div style="display:flex;gap:6px;align-items:center">
            ${isEditedSignal(s) ? '<span class="micro-badge micro-edited">EDITED</span>' : ''}
            <button type="button" class="ch-btn" id="modal-jump-signal">Open #${s.id}</button>
          </div>
        </div>
        <pre class="raw-msg-box" style="max-height:150px">${esc(s.message || '')}</pre>
        ${s.parsing_explanation ? `<div style="font-size:11px;color:var(--orange)"><b>Parser:</b> ${esc(s.parsing_explanation)}</div>` : ''}
      </div>

      <form id="modal-fix-form" style="display:grid;gap:10px">
        <div style="display:grid;grid-template-columns:1.2fr 1fr;gap:10px;align-items:end">
          <label>Symbol<input name="symbol" value="${esc(s.symbol || '')}" placeholder="e.g. BTCUSDT, NIFTY"></label>
          <div>
            <div style="font-size:12px;color:var(--muted);margin-bottom:6px">Direction</div>
            <div class="review-seg-group">
              <button type="button" class="seg-pill ${curDir === 'BUY' ? 'active-buy' : ''}" data-seg-dir="BUY">BUY</button>
              <button type="button" class="seg-pill ${curDir === 'SELL' ? 'active-sell' : ''}" data-seg-dir="SELL">SELL</button>
            </div>
          </div>
        </div>

        <div style="display:grid;grid-template-columns:1.25fr 0.95fr;gap:10px;align-items:end">
          <label>Entry Price<input id="qf-entry-input" name="entry_price" type="number" step="any" value="${useMarketNow ? '' : esc(s.entry_price ?? '')}" placeholder="${useMarketNow ? 'Candle open at signal time' : 'e.g. 0.2325'}" ${useMarketNow ? 'disabled' : ''}></label>
          <button type="button" id="qf-market-now-btn" class="seg-pill ${useMarketNow ? 'active-mkt' : ''}" style="height:38px">&#x26A1; Market Now</button>
        </div>

        <div style="display:grid;grid-template-columns:0.9fr 1.3fr;gap:10px">
          <label>Stop Loss<input name="stop_loss" type="number" step="any" value="${esc(s.stop_loss ?? '')}" placeholder="SL price"></label>
          <label>Targets (comma or /)<input name="targets" value="${esc((s.targets || []).join(', '))}" placeholder="TP1, TP2, TP3"></label>
        </div>

        <label>Additional Entries (optional, comma)<input name="additional_entries" value="${esc((s.additional_entries || []).join(', '))}" placeholder="2nd entry, 3rd entry"></label>

        ${isOpt ? `
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;align-items:end;padding-top:6px;border-top:1px solid var(--line)">
          <div>
            <div style="font-size:12px;color:var(--muted);margin-bottom:6px">Option</div>
            <div class="review-seg-group">
              <button type="button" class="seg-pill ${curOptType === 'CE' ? 'active-buy' : ''}" data-seg-opt="CE">CE</button>
              <button type="button" class="seg-pill ${curOptType === 'PE' ? 'active-sell' : ''}" data-seg-opt="PE">PE</button>
            </div>
          </div>
          <label>Strike<input name="strike_price" type="number" step="any" value="${esc(s.strike_price ?? '')}"></label>
          <label>Expiry<input name="expiry_date" value="${esc(s.expiry_date || '')}"></label>
        </div>` : ''}

        <div class="review-drawer-foot">
          <button type="button" id="modal-reparse-btn">&#x2728; Re-parse Auto</button>
          <button type="submit" class="primary" id="qf-save-verify-btn">${reviewQueueMode && reviewQueueIds.length > 1 ? 'Save, Verify &amp; Next &rarr;' : 'Save &amp; Verify Now'}</button>
        </div>
      </form>

      <details style="margin-top:4px">
        <summary class="muted" style="cursor:pointer;font-size:12px">Re-parse history (${(x.history || []).length})</summary>
        <div class="table-wrap" style="margin-top:8px">${table(x.history || [], ['parsed_at', 'run_type', 'status', 'confidence', 'explanation'])}</div>
      </details>`;

    panel.querySelectorAll('[data-seg-dir]').forEach(btn => {
      btn.onclick = () => {
        curDir = btn.dataset.segDir;
        panel.querySelectorAll('[data-seg-dir]').forEach(b => {
          b.classList.remove('active-buy', 'active-sell');
          if (b.dataset.segDir === curDir) b.classList.add(curDir === 'BUY' ? 'active-buy' : 'active-sell');
        });
      };
    });
    panel.querySelectorAll('[data-seg-opt]').forEach(btn => {
      btn.onclick = () => {
        curOptType = curOptType === btn.dataset.segOpt ? '' : btn.dataset.segOpt;
        panel.querySelectorAll('[data-seg-opt]').forEach(b => {
          b.classList.remove('active-buy', 'active-sell');
          if (b.dataset.segOpt === curOptType) b.classList.add(curOptType === 'CE' ? 'active-buy' : 'active-sell');
        });
      };
    });
    const mktBtn = $('#qf-market-now-btn');
    const entryInp = $('#qf-entry-input');
    if (mktBtn && entryInp) {
      mktBtn.onclick = () => {
        useMarketNow = !useMarketNow;
        mktBtn.classList.toggle('active-mkt', useMarketNow);
        entryInp.disabled = useMarketNow;
        if (useMarketNow) { entryInp.value = ''; entryInp.placeholder = 'Candle open at signal time'; }
        else { entryInp.placeholder = 'e.g. 0.2325'; entryInp.focus(); }
      };
    }

    $('#modal-jump-signal').onclick = () => { closeReviewDrawer(); openSignalInManagement(s.id); };
    $('#modal-reparse-btn').onclick = () => busy($('#modal-reparse-btn'), 'Re-parsing...', async () => {
      await api(`/api/v1/signals/${s.id}/reparse`, { method: 'POST' });
      await reviewParse(s.id, reviewQueueMode ? reviewQueueIds : null);
      refreshActiveView();
    });
    $('#modal-fix-form').onsubmit = async e => {
      e.preventDefault();
      const saveBtn = $('#qf-save-verify-btn');
      await busy(saveBtn, 'Saving & Verifying...', async () => {
        const fd = Object.fromEntries(new FormData(e.target));
        const parseNumList = str => String(str || '').split(/[,/]/).map(v => v.trim()).filter(Boolean).map(Number).filter(n => !isNaN(n));
        const entryNum = useMarketNow ? null : (fd.entry_price !== '' && fd.entry_price != null ? Number(fd.entry_price) : null);
        const payload = {
          symbol: (fd.symbol || '').trim().toUpperCase() || null,
          direction: curDir || null,
          entry_price: entryNum,
          additional_entries: parseNumList(fd.additional_entries),
          stop_loss: fd.stop_loss !== '' && fd.stop_loss != null ? Number(fd.stop_loss) : null,
          targets: parseNumList(fd.targets),
          option_type: isOpt ? (curOptType || null) : (s.option_type || null),
          strike_price: isOpt ? (fd.strike_price !== '' && fd.strike_price != null ? Number(fd.strike_price) : null) : s.strike_price,
          expiry_date: isOpt ? ((fd.expiry_date || '').trim().toUpperCase() || null) : s.expiry_date,
          entry_mode: useMarketNow ? 'market_now' : (entryNum != null ? 'limit' : (s.entry_mode || 'limit')),
          manual_review_status: 'resolved',
        };
        await api(`/api/v1/signals/${s.id}`, { method: 'PUT', body: JSON.stringify(payload) });
        await api(`/api/v1/signals/${s.id}/verify`, { method: 'POST' }).catch(() => {});
        await refreshActiveView();
        if (reviewQueueMode) {
          reviewQueueIds = reviewQueueIds.filter(qId => qId !== s.id);
          if (!reviewQueueIds.length) {
            renderReviewDrawerEmpty();
          } else {
            reviewQueueIdx = Math.min(reviewQueueIdx, reviewQueueIds.length - 1);
            await reviewParse(reviewQueueIds[reviewQueueIdx], reviewQueueIds);
          }
        } else {
          closeReviewDrawer();
        }
      });
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

function renderActiveFilterChips() {
  const chips = [];
  if (sf.channel) chips.push({ lbl: `Channel: ${sf.channel}`, clear: () => { sf.channel = ''; } });
  if (sf.status) chips.push({ lbl: `Status: ${sf.status}`, clear: () => { sf.status = ''; } });
  if (sf.tp_filter) chips.push({ lbl: `TP: ${sf.tp_filter}`, clear: () => { sf.tp_filter = ''; } });
  if (sf.date_from) chips.push({ lbl: `From: ${sf.date_from}`, clear: () => { sf.date_from = ''; } });
  if (sf.date_to) chips.push({ lbl: `To: ${sf.date_to}`, clear: () => { sf.date_to = ''; } });
  if (sf.notification_filter && sf.notification_filter !== 'primary') chips.push({ lbl: `Type: ${sf.notification_filter}`, clear: () => { sf.notification_filter = 'primary'; } });
  if (sf.pnl_basis && sf.pnl_basis !== 'tp1') chips.push({ lbl: `Basis: ${sf.pnl_basis.toUpperCase()}`, clear: () => { sf.pnl_basis = 'tp1'; } });

  const badge = $('#active-filter-badge');
  const toggleBtn = $('#toggle-signal-filters');
  const resetBtn = $('#clear-signal-filters');
  if (badge) {
    badge.textContent = String(chips.length);
    badge.style.display = chips.length ? 'inline-flex' : 'none';
  }
  if (toggleBtn) toggleBtn.classList.toggle('has-active', chips.length > 0);
  if (resetBtn) resetBtn.style.display = (chips.length > 0 || sf.category || signalSearchQuery) ? 'inline-block' : 'none';

  const host = $('#active-filter-chips');
  if (!host) return;
  host.innerHTML = chips.map((c, idx) => `<button type="button" class="active-chip" data-clear-chip="${idx}">${esc(c.lbl)} &times;</button>`).join('');
  host.querySelectorAll('[data-clear-chip]').forEach(btn => {
    btn.onclick = () => { chips[+btn.dataset.clearChip].clear(); loadSignals(); };
  });
}

async function loadSignals() {
  let f = await api('/api/v1/signals/filters' + (sf.category ? '?category=' + encodeURIComponent(sf.category) : ''));
  const catButtons = [
    `<button class="category-filter ${sf.category === '' ? 'active' : ''}" data-cat="">All Categories</button>`,
    ...f.categories.map(c => `<button class="category-filter ${c === sf.category ? 'active' : ''}" data-cat="${esc(c)}">${esc(c)}</button>`)
  ];
  $('#category-filters').innerHTML = catButtons.join('');
  let s = $('#channel-filter');
  s.innerHTML = '<option value="">All channels</option>' + f.channels.map(c => `<option>${esc(c)}</option>`).join('');
  s.value = sf.channel;
  $('#verification-filter').value = sf.status;
  if ($('#tp-filter')) $('#tp-filter').value = sf.tp_filter;
  if ($('#pnl-basis-filter')) $('#pnl-basis-filter').value = sf.pnl_basis;
  if ($('#signal-date-from')) $('#signal-date-from').value = sf.date_from;
  if ($('#signal-date-to')) $('#signal-date-to').value = sf.date_to;
  $('#notification-type-filter').value = sf.notification_filter;
  document.querySelectorAll('[data-cat]').forEach(b => b.onclick = () => { sf.category = b.dataset.cat; sf.channel = ''; loadSignals(); });
  renderActiveFilterChips();

  let q = new URLSearchParams(sf);
  [...q].forEach(([k, v]) => !v && q.delete(k));
  const qs = q.size ? '?' + q : '';
  let [data, summary] = await Promise.all([
    api('/api/v1/signals' + qs),
    api('/api/v1/signals/summary' + qs)
  ]);
  lastSignalsData = data;
  lastSignalsSummary = summary;

  // Update Needs-Review queue badge
  const reviewCount = data.filter(r => r.parsing_status === 'needs_review').length;
  const revBtn = $('#open-review-queue-btn');
  const revCntEl = $('#review-queue-count');
  if (revBtn && revCntEl) {
    revCntEl.textContent = String(reviewCount);
    revBtn.style.display = reviewCount > 0 ? 'inline-flex' : 'none';
  }

  const sumHost = $('#signals-pnl-summary');
  if (sumHost && summary) {
    const basisLbl = { tp1: 'TP1', tp2: 'TP2', tp3: 'TP3', highest_tp: 'Best TP' }[summary.basis] || 'TP1';
    const inrCls = Number(summary.pnl_inr || 0) >= 0 ? 'green-txt' : 'red';
    const usdCls = Number(summary.pnl_usd || 0) >= 0 ? 'green-txt' : 'red';
    sumHost.innerHTML = `
      <div class="pnl-summary-metrics">
        <span><b>${esc(summary.total_signals)}</b> sigs</span>
        <span>Win <b class="${Number(summary.win_rate) >= 50 ? 'green-txt' : ''}">${esc(summary.win_rate)}%</b></span>
        <button type="button" id="cycle-pnl-basis-btn" class="basis-cycle-pill">Basis: ${esc(basisLbl)} &#x21BB;</button>
        <span>TP1:<b>${esc(summary.tp1_hits)}</b></span>
        <span>TP2:<b>${esc(summary.tp2_hits)}</b></span>
        <span>TP3:<b>${esc(summary.tp3_hits)}</b></span>
        <span>TP→SL:<b>${esc(summary.tp_then_sl_hits)}</b></span>
        <span>SL:<b>${esc(summary.sl_only_hits)}</b></span>
        <span>Active:<b>${esc(summary.active_monitoring)}</b></span>
      </div>
      <div class="pnl-summary-totals">
        <span class="${inrCls}">${esc(fmtPnl(summary.pnl_inr, 'INR'))}</span>
        ${Number(summary.pnl_usd || 0) !== 0 ? `<span class="${usdCls}">${esc(fmtPnl(summary.pnl_usd, 'USD'))}</span>` : ''}
      </div>`;
    const cycleBtn = $('#cycle-pnl-basis-btn');
    if (cycleBtn) {
      cycleBtn.onclick = () => {
        const order = ['tp1', 'tp2', 'tp3', 'highest_tp'];
        sf.pnl_basis = order[(order.indexOf(sf.pnl_basis) + 1) % order.length];
        loadSignals();
      };
    }
  }

  const visibleData = getFilteredVisibleSignals();
  renderSignalTable(visibleData);

  let selectCurrent = () => { getFilteredVisibleSignals().forEach(x => selectedSignals.add(x.id)); renderSignalTable(getFilteredVisibleSignals()); };
  $('#select-all').onclick = selectCurrent;
  if ($('#select-filtered')) $('#select-filtered').onclick = selectCurrent;
  $('#clear-selection').onclick = () => { selectedSignals.clear(); renderSignalTable(getFilteredVisibleSignals()); };
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
  updateBatchBar();
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
$('#close-parse-review').onclick = closeReviewDrawer;
if ($('#review-drawer-backdrop')) {
  $('#review-drawer-backdrop').onclick = e => {
    if (e.target === $('#review-drawer-backdrop')) closeReviewDrawer();
  };
}
window.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    const fsChart = document.querySelector('.chart-box.chart-fullscreen');
    if (fsChart && typeof fsChart._exitFullscreen === 'function') { fsChart._exitFullscreen(); return; }
    if ($('#review-drawer-backdrop')?.classList.contains('open')) closeReviewDrawer();
  }
});
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

function resetAllSignalFilters() {
  sf = { category: '', channel: '', status: '', tp_filter: '', pnl_basis: 'tp1', date_from: '', date_to: '', notification_filter: 'primary' };
  signalSearchQuery = '';
  if ($('#signal-search')) $('#signal-search').value = '';
  loadSignals();
}

$('#refresh-signals').onclick = () => busy($('#refresh-signals'), 'Refreshing...', loadSignals);
if ($('#open-review-queue-btn')) $('#open-review-queue-btn').onclick = openReviewQueue;
if ($('#toggle-signal-filters')) {
  $('#toggle-signal-filters').onclick = () => {
    const drawer = $('#signal-filter-drawer');
    if (drawer) drawer.classList.toggle('open');
  };
}
if ($('#drawer-done-filters')) {
  $('#drawer-done-filters').onclick = () => $('#signal-filter-drawer')?.classList.remove('open');
}
if ($('#drawer-reset-filters')) $('#drawer-reset-filters').onclick = resetAllSignalFilters;
if ($('#signal-search')) {
  $('#signal-search').oninput = e => {
    signalSearchQuery = e.target.value || '';
    renderActiveFilterChips();
    renderSignalTable(getFilteredVisibleSignals());
  };
}
$('#channel-filter').onchange = e => { sf.channel = e.target.value; loadSignals(); };
$('#verification-filter').onchange = e => { sf.status = e.target.value; loadSignals(); };
if ($('#tp-filter')) $('#tp-filter').onchange = e => { sf.tp_filter = e.target.value; loadSignals(); };
if ($('#pnl-basis-filter')) $('#pnl-basis-filter').onchange = e => { sf.pnl_basis = e.target.value; loadSignals(); };
if ($('#signal-date-from')) $('#signal-date-from').onchange = e => { sf.date_from = e.target.value; loadSignals(); };
if ($('#signal-date-to')) $('#signal-date-to').onchange = e => { sf.date_to = e.target.value; loadSignals(); };
$('#notification-type-filter').onchange = e => { sf.notification_filter = e.target.value; loadSignals(); };
$('#clear-signal-filters').onclick = resetAllSignalFilters;

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
