const $ = s => document.querySelector(s),
  api = (u, o = {}) => fetch(u, { headers: { 'Content-Type': 'application/json' }, ...o }).then(async r => {
    let x = await r.json();
    if (!r.ok) throw Error(x.error || x.detail || 'Request failed');
    return x;
  }),
  esc = x => String(x ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

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
    ? `<table><thead><tr>${f.map(x => `<th>${x}</th>`).join('')}</tr></thead><tbody>${d.map(r => `<tr>${f.map(x => `<td>${esc(Array.isArray(r[x]) ? r[x].join(', ') : r[x])}</td>`).join('')}</tr>`).join('')}</tbody></table>`
    : '<p class="muted">No data yet.</p>';
}

function bars(d) {
  return d.length
    ? d.map(x => `<div><span class="bar-label"><b>${esc(x.name)}</b><b>${x.value}</b></span><span class="bar"><i style="width:${Math.min(100, x.value * 10)}%"></i></span></div>`).join('')
    : '<p class="muted">No data yet.</p>';
}

let sf = { category: '', channel: '', status: '', notification_filter: 'primary' };
let selectedSignals = new Set();
let expandedSignals = new Set();
let chartCache = new Map();

function statusBadge(status, isManualNeeded, isAutoScheduled) {
  const map = {
    target_hit: ['TP Hit', 'badge-green'],
    stop_loss_hit: ['SL Hit', 'badge-red'],
    entry_hit: ['Entry Hit', 'badge-blue'],
    ambiguous: ['Ambiguous', 'badge-orange'],
    awaiting_market_data: ['Awaiting Data', 'badge-orange'],
    historical_data_missing: ['No Historical Data', 'badge-muted'],
    pending: [
      isManualNeeded ? 'Manual Needed' : isAutoScheduled ? 'Scheduled' : 'Pending',
      isManualNeeded ? 'badge-orange' : isAutoScheduled ? 'badge-blue' : 'badge-muted'
    ],
  };
  const [label, cls] = map[status] || [status || 'pending', 'badge-muted'];
  return `<span class="pill ${cls}">${esc(label)}</span>`;
}

function pnlBadge(amount, currency, tradesCount) {
  if (!tradesCount) return '';
  const num = Number(amount || 0);
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
  let ambEvent = null;

  for (const ev of events) {
    if (ev.type === 'entry_hit') entryEvents.push(ev);
    if (ev.type === 'tp_hit') {
      const m = String(ev.label || '').match(/TP(\d+)/i);
      if (m && !tpEventsByNum[Number(m[1])]) tpEventsByNum[Number(m[1])] = ev;
    }
    if (ev.type === 'sl_hit' && !slEvent) slEvent = ev;
    if (ev.type === 'ambiguous' && !ambEvent) ambEvent = ev;
  }

  const allEntries = [r.entry_price, ...(r.additional_entries || [])].filter(v => v != null);
  const steps = [
    {
      state: 'done',
      title: 'Signal Sent',
      price: allEntries.length ? `Entry @ ${allEntries.join(' / ')}` : '—',
      time: fmtDateTime(r.notification_time),
    },
  ];

  if (allEntries.length > 1) {
    allEntries.forEach((eVal, idx) => {
      const legNum = idx + 1;
      const hitEv = entryEvents.find(e => Number(e.leg) === legNum) || entryEvents[idx];
      steps.push({
        state: hitEv ? 'done' : 'wait',
        title: `Entry ${legNum}`,
        price: `${eVal}`,
        time: hitEv ? fmtDateTime(hitEv.candle_time) : 'Not reached yet',
      });
    });
  } else {
    const entryEvent = entryEvents[0] || null;
    steps.push({
      state: entryEvent ? 'done' : 'wait',
      title: 'Entry Hit',
      price: r.entry_price != null ? `${r.entry_price}` : 'Not parsed',
      time: entryEvent ? fmtDateTime(entryEvent.candle_time) : 'Not reached yet',
    });
  }

  if (targets.length) {
    targets.forEach((tVal, idx) => {
      const tpNum = idx + 1;
      const hit = tpEventsByNum[tpNum];
      steps.push({
        state: hit ? 'win' : 'wait',
        title: `TP${tpNum}`,
        price: `${tVal}`,
        time: hit ? fmtDateTime(hit.candle_time) : 'Awaiting',
      });
    });
  } else {
    steps.push({
      state: 'wait',
      title: 'Targets (TP)',
      price: 'None parsed',
      time: '—',
    });
  }

  steps.push({
    state: slEvent ? 'loss' : 'wait',
    title: 'Stop Loss (SL)',
    price: r.stop_loss != null ? `${r.stop_loss}` : 'None parsed',
    time: slEvent ? fmtDateTime(slEvent.candle_time) : 'Not hit',
  });

  if (ambEvent) {
    steps.push({
      state: 'warn',
      title: 'Ambiguous Candle',
      price: 'TP & SL in same 1m bar',
      time: fmtDateTime(ambEvent.candle_time),
    });
  }

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
    const label = chartData.trading_symbol
      ? `${chartData.trading_symbol} (token ${chartData.resolved_symbol})`
      : (chartData.resolved_symbol || chartData.symbol || '');
    const reasonHtml = chartData.detail ? `<div style="margin-top:6px;color:#ffbb66">${esc(chartData.detail)}</div>` : '';
    container.innerHTML = `<div class="chart-empty">No cached 1m candles yet for <b>${esc(label)}</b> (${esc(chartData.provider)}).${reasonHtml}</div>`;
    return;
  }

  container.innerHTML = `
    <div class="chart-toolbar">
      <div class="chart-btn-group">
        <button type="button" class="ch-btn" data-act="zoom-in" title="Zoom In">+</button>
        <button type="button" class="ch-btn" data-act="zoom-out" title="Zoom Out">&minus;</button>
        <button type="button" class="ch-btn" data-act="focus">Focus Trade</button>
        <button type="button" class="ch-btn" data-act="fit">Fit All</button>
      </div>
      <button type="button" class="ch-btn" data-act="fullscreen">&#x26F6; Full Screen</button>
    </div>
    <div class="chart-fs-readout" style="display:none;font-size:11px;color:#9fb0cf;padding:4px 2px 6px;"></div>
    <div class="chart-canvas-holder" style="flex:1;min-height:0;width:100%;">
      <canvas class="ohlc-canvas"></canvas>
    </div>
  `;

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

  const markers = [];
  const markersByIdx = new Map();
  function addMarker(idx, label, color, price, detailText) {
    if (idx < 0 || idx >= candles.length) return;
    const item = { idx, label, color, price, detailText };
    markers.push(item);
    if (!markersByIdx.has(idx)) markersByIdx.set(idx, []);
    markersByIdx.get(idx).push(item);
  }

  const sigIdx = nearestIdx(chartData.notification_time);
  if (sigIdx >= 0) {
    addMarker(sigIdx, 'SIG', '#94a3bd', candles[sigIdx].close, `Signal Sent (${fmtDateTime(chartData.notification_time)})`);
  }
  for (const ev of (chartData.events || [])) {
    const idx = nearestIdx(ev.candle_time);
    if (idx < 0) continue;
    const isTp = ev.type === 'tp_hit';
    const isSl = ev.type === 'sl_hit' || ev.type === 'ambiguous';
    const shortLbl = ev.type === 'entry_hit'
      ? (ev.leg ? `E${ev.leg}` : 'ENTRY')
      : isTp
        ? ev.label.split(' ')[0]
        : isSl
          ? 'SL'
          : ev.label;
    addMarker(
      idx,
      shortLbl,
      isTp ? '#62d6a5' : isSl ? '#ff7285' : '#60a5fa',
      ev.price ?? candles[idx].close,
      `${ev.label} @ ${ev.price ?? ''} (${fmtDateTime(ev.candle_time)})`
    );
  }

  const minVisible = Math.min(8, candles.length);
  const maxVisible = Math.max(candles.length, minVisible);
  let viewStart = 0;
  let visibleCount = candles.length;
  let activeIdx = -1;
  let includeAllLevelsInScale = false;

  function clampViewport() {
    visibleCount = Math.max(minVisible, Math.min(maxVisible, visibleCount));
    const maxStart = Math.max(0, candles.length - visibleCount);
    viewStart = Math.max(0, Math.min(maxStart, viewStart));
  }

  function focusTradeRange() {
    const keyIndices = markers.map(m => m.idx);
    if (keyIndices.length) {
      const firstEv = Math.min(...keyIndices);
      const lastEv = Math.max(...keyIndices);
      const span = Math.max(lastEv - firstEv + 1, 16);
      const padBars = Math.max(5, Math.round(span * 0.25));
      viewStart = Math.max(0, firstEv - padBars);
      visibleCount = Math.min(candles.length, span + padBars * 2);
    } else {
      viewStart = 0;
      visibleCount = Math.min(candles.length, 60);
    }
    includeAllLevelsInScale = true;
    clampViewport();
  }

  function fitAllRange() {
    viewStart = 0;
    visibleCount = candles.length;
    includeAllLevelsInScale = true;
    clampViewport();
  }

  if (candles.length > 45) focusTradeRange();
  else fitAllRange();

  function getDimensions() {
    const isFs = container.classList.contains('chart-fullscreen');
    if (isFs) {
      const topChrome = (toolbar?.offsetHeight || 36) + (fsReadout?.offsetHeight || 22) + 20;
      const width = Math.max(holder.clientWidth || window.innerWidth - 20, 260);
      const height = Math.max(holder.clientHeight || (window.innerHeight - topChrome), 260);
      return { width, height };
    }
    const width = Math.max(holder.clientWidth || container.clientWidth || 320, 260);
    return { width, height: 270 };
  }

  function draw() {
    if (!document.body.contains(container)) return;
    const { width, height } = getDimensions();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    if (canvas.width !== Math.round(width * dpr) || canvas.height !== Math.round(height * dpr)) {
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      canvas.style.width = width + 'px';
      canvas.style.height = height + 'px';
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    const pad = { top: 22, right: 68, bottom: 26, left: 10 };
    const plotW = Math.max(width - pad.left - pad.right, 40);
    const plotH = Math.max(height - pad.top - pad.bottom, 40);

    const startIdx = Math.max(0, Math.floor(viewStart));
    const endIdx = Math.min(candles.length - 1, Math.ceil(viewStart + visibleCount));

    let cMin = Infinity, cMax = -Infinity;
    for (let i = startIdx; i <= endIdx; i++) {
      if (candles[i].low < cMin) cMin = candles[i].low;
      if (candles[i].high > cMax) cMax = candles[i].high;
    }
    if (!isFinite(cMin) || !isFinite(cMax)) { cMin = 0; cMax = 1; }

    let minP = cMin, maxP = cMax;
    const cSpan = Math.max(cMax - cMin, Math.abs(cMax) * 0.002 || 1);
    const allLevels = [
      chartData.entry_price,
      ...(chartData.additional_entries || []),
      chartData.stop_loss,
      ...(chartData.targets || []),
    ].filter(v => v != null && !isNaN(Number(v))).map(Number);

    for (const lvl of allLevels) {
      if (includeAllLevelsInScale || (lvl >= cMin - cSpan * 0.6 && lvl <= cMax + cSpan * 0.6)) {
        if (lvl < minP) minP = lvl;
        if (lvl > maxP) maxP = lvl;
      }
    }
    if (minP === maxP) { minP -= 1; maxP += 1; }
    const margin = (maxP - minP) * 0.09;
    minP -= margin;
    maxP += margin;

    const yOf = price => pad.top + ((maxP - price) / (maxP - minP)) * plotH;
    const stepX = plotW / Math.max(visibleCount, 1);
    const xOf = idx => pad.left + (idx - viewStart + 0.5) * stepX;
    const bodyW = Math.max(2, Math.min(14, stepX * 0.68));

    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = '#090f1d';
    ctx.fillRect(0, 0, width, height);

    ctx.font = '10px system-ui, sans-serif';
    ctx.textAlign = 'left';
    for (let g = 0; g <= 4; g++) {
      const p = minP + ((maxP - minP) * g) / 4;
      const y = yOf(p);
      ctx.strokeStyle = '#1b2742';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(width - pad.right, y);
      ctx.stroke();
      ctx.fillStyle = '#7f91b0';
      ctx.fillText(p >= 100 ? p.toFixed(2) : p.toFixed(4), width - pad.right + 5, y + 3);
    }

    ctx.save();
    ctx.beginPath();
    ctx.rect(pad.left, pad.top - 14, plotW, plotH + 14);
    ctx.clip();

    function drawLevel(price, label, color) {
      if (price == null || isNaN(Number(price))) return;
      const num = Number(price);
      if (num < minP || num > maxP) return;
      const y = yOf(num);
      ctx.save();
      ctx.setLineDash([4, 4]);
      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(width - pad.right, y);
      ctx.stroke();
      ctx.restore();
      ctx.fillStyle = color;
      ctx.font = 'bold 10px system-ui, sans-serif';
      ctx.fillText(`${label}: ${num}`, pad.left + 4, Math.max(12, y - 3));
      ctx.font = '10px system-ui, sans-serif';
    }
    drawLevel(chartData.entry_price, 'ENTRY 1', '#60a5fa');
    (chartData.additional_entries || []).forEach((eVal, i) => drawLevel(eVal, `ENTRY ${i + 2}`, '#60a5fa'));
    drawLevel(chartData.stop_loss, 'SL', '#ff7285');
    (chartData.targets || []).forEach((tVal, i) => drawLevel(tVal, `TP${i + 1}`, '#62d6a5'));

    for (let i = startIdx; i <= endIdx; i++) {
      const c = candles[i];
      const cx = xOf(i);
      const bull = c.close >= c.open;
      const col = bull ? '#34d399' : '#f87171';
      ctx.strokeStyle = col;
      ctx.fillStyle = col;
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(cx, yOf(c.high));
      ctx.lineTo(cx, yOf(c.low));
      ctx.stroke();
      const topY = yOf(Math.max(c.open, c.close));
      const botY = yOf(Math.min(c.open, c.close));
      ctx.fillRect(cx - bodyW / 2, topY, bodyW, Math.max(1.5, botY - topY));
    }

    markers.forEach((m, mOrder) => {
      if (m.idx < startIdx - 1 || m.idx > endIdx + 1) return;
      const cx = xOf(m.idx);
      const cy = yOf(m.price);
      ctx.fillStyle = m.color;
      ctx.beginPath();
      ctx.arc(cx, cy, 4.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#090f1d';
      ctx.lineWidth = 1.5;
      ctx.stroke();

      ctx.font = 'bold 9px system-ui, sans-serif';
      const tagW = ctx.measureText(m.label).width + 8;
      const tagX = Math.max(pad.left + 2, Math.min(width - pad.right - tagW - 2, cx - tagW / 2));
      const tagY = Math.max(15, Math.min(height - pad.bottom - 6, cy - 10 - (mOrder % 2) * 14));
      ctx.fillStyle = m.color;
      ctx.fillRect(tagX, tagY - 10, tagW, 13);
      ctx.fillStyle = '#06131f';
      ctx.fillText(m.label, tagX + 4, tagY);
      ctx.font = '10px system-ui, sans-serif';
    });

    if (activeIdx >= startIdx && activeIdx <= endIdx) {
      const c = candles[activeIdx];
      const cx = xOf(activeIdx);
      const cy = yOf(c.close);
      ctx.save();
      ctx.setLineDash([3, 3]);
      ctx.strokeStyle = '#e7edf9';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(cx, pad.top);
      ctx.lineTo(cx, height - pad.bottom);
      ctx.moveTo(pad.left, cy);
      ctx.lineTo(width - pad.right, cy);
      ctx.stroke();
      ctx.restore();
    }
    ctx.restore();

    if (activeIdx >= startIdx && activeIdx <= endIdx) {
      const c = candles[activeIdx];
      const cy = yOf(c.close);
      ctx.fillStyle = '#2563eb';
      ctx.fillRect(width - pad.right + 1, cy - 8, pad.right - 2, 16);
      ctx.fillStyle = '#ffffff';
      ctx.font = 'bold 10px system-ui, sans-serif';
      ctx.fillText(c.close >= 100 ? c.close.toFixed(2) : c.close.toFixed(4), width - pad.right + 5, cy + 3);
      ctx.font = '10px system-ui, sans-serif';
    }

    ctx.fillStyle = '#7f91b0';
    const firstVis = candles[startIdx];
    const lastVis = candles[endIdx];
    ctx.fillText(fmtShortTime(firstVis.candle_timestamp), pad.left, height - 8);
    if (endIdx - startIdx >= 4) {
      const midVis = candles[Math.floor((startIdx + endIdx) / 2)];
      ctx.fillText(fmtShortTime(midVis.candle_timestamp), pad.left + plotW * 0.42, height - 8);
    }
    const endLbl = fmtShortTime(lastVis.candle_timestamp);
    ctx.fillText(endLbl, Math.max(pad.left, width - pad.right - ctx.measureText(endLbl).width), height - 8);

    const statusText = (activeIdx >= 0 && activeIdx < candles.length)
      ? (() => {
          const c = candles[activeIdx];
          const evList = markersByIdx.get(activeIdx) || [];
          const evNote = evList.length ? `  |  ★ ${evList.map(e => e.detailText).join(' • ')}` : '';
          return `${fmtDateTime(c.candle_timestamp)}  |  O: ${c.open}  H: ${c.high}  L: ${c.low}  C: ${c.close}${evNote}`;
        })()
      : `Showing bars ${startIdx + 1}–${endIdx + 1} of ${candles.length} (${chartData.provider} • ${chartData.trading_symbol || chartData.resolved_symbol}) — drag to scroll, pinch/+/- to zoom, tap candle for OHLC`;

    if (hoverOut) hoverOut.textContent = statusText;
    if (fsReadout) fsReadout.textContent = statusText;
  }

  function applyFullscreenState(enable) {
    if (enable) {
      if (container.parentNode !== document.body && container.parentNode) {
        container.parentNode.insertBefore(placeholder, container);
        document.body.appendChild(container);
      }
      container.classList.add('chart-fullscreen');
      Object.assign(container.style, {
        position: 'fixed',
        inset: '0',
        width: '100vw',
        height: '100dvh',
        maxWidth: 'none',
        maxHeight: 'none',
        margin: '0',
        padding: '10px',
        zIndex: '99999',
        background: '#090f1d',
        display: 'flex',
        flexDirection: 'column',
        boxSizing: 'border-box',
      });
      document.body.style.overflow = 'hidden';
      fsReadout.style.display = 'block';
      fsBtn.innerHTML = '&#x2715; Exit Full';
      if (!document.fullscreenElement && container.requestFullscreen) {
        container.requestFullscreen().catch(() => {});
      }
    } else {
      container.classList.remove('chart-fullscreen');
      container.removeAttribute('style');
      document.body.style.overflow = '';
      fsReadout.style.display = 'none';
      fsBtn.innerHTML = '&#x26F6; Full Screen';
      if (placeholder.parentNode) {
        placeholder.parentNode.insertBefore(container, placeholder);
        placeholder.parentNode.removeChild(placeholder);
      }
      if (document.fullscreenElement === container && document.exitFullscreen) {
        document.exitFullscreen().catch(() => {});
      }
    }
    requestAnimationFrame(draw);
  }

  function zoomBy(factor, anchorRatio = 0.5) {
    includeAllLevelsInScale = false;
    const anchorCandle = viewStart + anchorRatio * visibleCount;
    visibleCount = Math.max(minVisible, Math.min(maxVisible, visibleCount * factor));
    viewStart = anchorCandle - anchorRatio * visibleCount;
    clampViewport();
    draw();
  }

  function clientXToCandleIdx(clientX) {
    const rect = canvas.getBoundingClientRect();
    const plotW = Math.max(rect.width - 10 - 68, 40);
    const relX = clientX - rect.left - 10;
    const idx = Math.floor(viewStart + (relX / plotW) * visibleCount);
    return Math.max(0, Math.min(candles.length - 1, idx));
  }

  container.querySelectorAll('.ch-btn').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const act = btn.dataset.act;
      if (act === 'zoom-in') zoomBy(0.65, 0.5);
      else if (act === 'zoom-out') zoomBy(1.5, 0.5);
      else if (act === 'focus') { focusTradeRange(); draw(); }
      else if (act === 'fit') { fitAllRange(); draw(); }
      else if (act === 'fullscreen') {
        applyFullscreenState(!container.classList.contains('chart-fullscreen'));
      }
    };
  });

  window.addEventListener('resize', () => {
    if (document.body.contains(container)) draw();
  });
  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement && container.classList.contains('chart-fullscreen')) {
      applyFullscreenState(false);
    } else if (document.fullscreenElement === container) {
      draw();
    }
  });
  window.addEventListener('keydown', e => {
    if (e.key === 'Escape' && container.classList.contains('chart-fullscreen')) {
      applyFullscreenState(false);
    }
  });

  let mouseDown = false, dragStartX = 0, dragStartView = 0;
  canvas.addEventListener('mousedown', e => {
    mouseDown = true;
    dragStartX = e.clientX;
    dragStartView = viewStart;
  });
  window.addEventListener('mousemove', e => {
    if (!mouseDown) return;
    const dx = e.clientX - dragStartX;
    const plotW = Math.max(canvas.clientWidth - 78, 40);
    includeAllLevelsInScale = false;
    viewStart = dragStartView - (dx / plotW) * visibleCount;
    clampViewport();
    draw();
  });
  window.addEventListener('mouseup', () => { mouseDown = false; });
  canvas.addEventListener('mousemove', e => {
    if (mouseDown) return;
    activeIdx = clientXToCandleIdx(e.clientX);
    draw();
  });
  canvas.addEventListener('mouseleave', () => {
    if (!mouseDown) { activeIdx = -1; draw(); }
  });
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const plotW = Math.max(rect.width - 78, 40);
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left - 10) / plotW));
    zoomBy(e.deltaY > 0 ? 1.25 : 0.8, ratio);
  }, { passive: false });

  let touchMode = 'none', touchMoved = false;
  let tStartX = 0, tStartView = 0;
  let pinchDist = 0, pinchStartView = 0, pinchStartVis = 0, pinchRatio = 0.5;

  canvas.addEventListener('touchstart', e => {
    if (e.touches.length === 1) {
      touchMode = 'pan';
      touchMoved = false;
      tStartX = e.touches[0].clientX;
      tStartView = viewStart;
    } else if (e.touches.length === 2) {
      touchMode = 'pinch';
      touchMoved = true;
      const dx = e.touches[1].clientX - e.touches[0].clientX;
      const dy = e.touches[1].clientY - e.touches[0].clientY;
      pinchDist = Math.max(10, Math.hypot(dx, dy));
      pinchStartView = viewStart;
      pinchStartVis = visibleCount;
      const rect = canvas.getBoundingClientRect();
      const midX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
      const plotW = Math.max(rect.width - 78, 40);
      pinchRatio = Math.max(0, Math.min(1, (midX - rect.left - 10) / plotW));
    }
  }, { passive: true });

  canvas.addEventListener('touchmove', e => {
    e.preventDefault();
    if (touchMode === 'pan' && e.touches.length === 1) {
      const dx = e.touches[0].clientX - tStartX;
      if (Math.abs(dx) > 4) touchMoved = true;
      const plotW = Math.max(canvas.clientWidth - 78, 40);
      includeAllLevelsInScale = false;
      viewStart = tStartView - (dx / plotW) * visibleCount;
      clampViewport();
      draw();
    } else if (touchMode === 'pinch' && e.touches.length === 2) {
      const dx = e.touches[1].clientX - e.touches[0].clientX;
      const dy = e.touches[1].clientY - e.touches[0].clientY;
      const newDist = Math.max(10, Math.hypot(dx, dy));
      const scale = pinchDist / newDist;
      includeAllLevelsInScale = false;
      const anchor = pinchStartView + pinchRatio * pinchStartVis;
      visibleCount = Math.max(minVisible, Math.min(maxVisible, pinchStartVis * scale));
      viewStart = anchor - pinchRatio * visibleCount;
      clampViewport();
      draw();
    }
  }, { passive: false });

  canvas.addEventListener('touchend', e => {
    if (touchMode === 'pan' && !touchMoved && e.changedTouches[0]) {
      activeIdx = clientXToCandleIdx(e.changedTouches[0].clientX);
      draw();
    }
    if (e.touches.length === 0) touchMode = 'none';
  });

  draw();
}

async function loadCardChart(signalId, forceRefresh = false) {
  const wrap = document.querySelector(`#chart-wrap-${signalId}`);
  const info = document.querySelector(`#chart-hover-${signalId}`);
  if (!wrap) return;
  wrap.innerHTML = '<p class="muted" style="padding:12px">Loading OHLC candles &amp; markers...</p>';
  try {
    if (forceRefresh) {
      await api(`/api/signals/${signalId}/verify`, { method: 'POST' });
    }
    const data = await api(`/api/signals/${signalId}/chart`);
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
  const entryText = allEntries.length
    ? allEntries.join(' / ')
    : (isMarketNow ? 'Market Now' : '—');
  const entryTag = (allEntries.length && isMarketNow) ? ' <small class="muted">(Market)</small>' : '';
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
          <span>Entry: <b>${esc(entryText)}</b>${entryTag}</span>
          <span>SL: <b>${esc(r.stop_loss ?? '—')}</b>${r.is_default_sl ? ' <small class="muted">(Default)</small>' : ''}</span>
          <span>TP: <b>${esc(targetsText)}</b>${r.is_default_tp ? ' <small class="muted">(Default)</small>' : ''}</span>
          <span>Sent: <b>${esc(fmtShortTime(r.notification_time))}</b> (${esc(r.age_minutes)}m ago)</span>
        </div>
      </div>
      <div class="card-right-badges">
        ${pnlBadge(r.pnl_amount, r.pnl_currency, r.trades_count)}
        <span class="parse-pill ${r.parsing_status === 'parsed' ? 'p-ok' : 'p-warn'}">${esc(r.parsing_status)} (${esc(r.parsing_confidence)})</span>
        ${statusBadge(r.verification_status, r.is_manual_needed, r.is_auto_scheduled)}
        <span class="chevron">${isExpanded ? '▲' : '▼'}</span>
      </div>
    </div>

    <div class="signal-card-body" style="display:${isExpanded ? 'block' : 'none'}">
      <div class="status-banner ${r.is_manual_needed ? 'banner-warn' : 'banner-info'}">
        <b>Status &amp; Schedule:</b> ${esc(r.status_reason)}
        ${r.next_scheduled_at ? `<div class="muted" style="margin-top:4px">Next automatic candle fetch: <b>${esc(fmtDateTime(r.next_scheduled_at))}</b> (every 15 mins while open)</div>` : ''}
      </div>

      ${buildLegsHtml(r)}

      <div class="dropdown-grid">
        <div class="dd-panel">
          <h4>Trade Execution Timeline (Date &amp; Time)</h4>
          ${buildTimelineHtml(r)}
        </div>

        <div class="dd-panel">
          <div class="toolbar" style="margin-bottom:6px">
            <h4 style="margin:0">Candle Chart &amp; Trade Markers</h4>
            <div style="display:flex;gap:6px;flex-wrap:wrap">
              <button type="button" class="chart-refresh-btn" data-chart-id="${r.id}">Fetch &amp; Refresh Chart</button>
              <button type="button" class="chart-refresh-btn btn-danger" data-delete-id="${r.id}">Delete Signal</button>
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
            <div><span>Stop Loss</span><b>${esc(r.stop_loss ?? '—')}${r.is_default_sl ? ' (Default)' : ''}</b></div>
            <div><span>Targets</span><b>${esc(targetsText)}${r.is_default_tp ? ' (Default)' : ''}</b></div>
            <div><span>Realized P&amp;L</span><b>${r.trades_count ? esc(`${fmtPnl(r.pnl_amount, r.pnl_currency)} (${r.trades_count} trade${r.trades_count > 1 ? 's' : ''})`) : '—'}</b></div>
            <div><span>Option</span><b>${esc([r.option_type, r.strike_price, r.expiry_date].filter(Boolean).join(' ') || '—')}</b></div>
          </div>
          <p class="muted" style="font-size:11px;margin:8px 0 0">${esc(r.parsing_explanation || '')}</p>
        </div>

        <div class="dd-panel">
          <h4>Original Raw Notification</h4>
          <p class="muted" style="font-size:11px;margin:0 0 6px">
            <b>Sent:</b> ${esc(fmtDateTime(r.notification_time))} &bull;
            <b>Channel:</b> ${esc(r.channel)} &bull;
            <b>ID:</b> ${esc(r.notification_id || '—')}
          </p>
          <pre class="raw-msg-box">${esc(r.message)}</pre>
        </div>
      </div>
    </div>
  </div>`;
}
function renderSignalTable(data) {
  document.querySelectorAll('body > .chart-fullscreen').forEach(el => el.remove());
  document.body.style.overflow = '';
  const container = $('#signals-list');
  if (!data.length) {
    container.innerHTML = '<p class="muted">No signals match the active filters.</p>';
    return;
  }

  const recentLive = data.filter(r => !r.is_historical);
  const olderManual = data.filter(r => r.is_historical);

  let html = '';

  if (recentLive.length) {
    html += `
      <div class="signal-section-header">
        <div>
          <h3>Recent &amp; Scheduled Signals (&le; 1 hr or Auto-Scheduled) (${recentLive.length})</h3>
          <p class="muted">Shows signals started within the last 1 hour, plus any signal actively running in automatic verification mode.</p>
        </div>
        <button type="button" id="select-live-group">Select recent &amp; scheduled (${recentLive.length})</button>
      </div>
      <div class="cards-stack">${recentLive.map(renderSignalCard).join('')}</div>
    `;
  }

  if (olderManual.length) {
    html += `
      <div class="signal-section-header older-header">
        <div>
          <h3>Older &amp; Completed Signals (&gt; 1 hr, Manual / Finished) (${olderManual.length})</h3>
          <p class="muted">Signals older than 1 hour that are no longer in auto-scheduled mode. Select checkboxes to re-parse, process, or delete.</p>
        </div>
        <button type="button" id="select-older-group">Select all older (${olderManual.length})</button>
      </div>
      <div class="cards-stack">${olderManual.map(renderSignalCard).join('')}</div>
    `;
  }

  container.innerHTML = html;

  document.querySelectorAll('.signal-select').forEach(x => {
    x.onchange = () => {
      if (x.checked) selectedSignals.add(+x.dataset.id);
      else selectedSignals.delete(+x.dataset.id);
    };
  });

  document.querySelectorAll('[data-toggle-id]').forEach(hdr => {
    hdr.onclick = () => {
      const id = +hdr.dataset.toggleId;
      const card = document.querySelector(`.signal-card[data-card-id="${id}"]`);
      const body = card.querySelector('.signal-card-body');
      const chev = card.querySelector('.chevron');
      if (expandedSignals.has(id)) {
        expandedSignals.delete(id);
        card.classList.remove('open');
        body.style.display = 'none';
        chev.textContent = '▼';
      } else {
        expandedSignals.add(id);
        card.classList.add('open');
        body.style.display = 'block';
        chev.textContent = '▲';
        loadCardChart(id, false);
      }
    };
  });

  document.querySelectorAll('.chart-refresh-btn[data-chart-id]').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const id = +btn.dataset.chartId;
      busy(btn, 'Fetching...', async () => {
        await loadCardChart(id, true);
        await loadSignals();
      });
    };
  });

  document.querySelectorAll('[data-delete-id]').forEach(btn => {
    btn.onclick = e => {
      e.stopPropagation();
      const id = +btn.dataset.deleteId;
      if (!confirm(`Delete signal #${id}? This removes its parsed record, schedule, and verification history.`)) return;
      busy(btn, 'Deleting...', async () => {
        await api(`/api/signals/${id}`, { method: 'DELETE' });
        selectedSignals.delete(id);
        expandedSignals.delete(id);
        $('#process-result').textContent = `Deleted signal #${id}.`;
        await loadSignals();
      });
    };
  });

  const btnLive = $('#select-live-group');
  if (btnLive) btnLive.onclick = () => { recentLive.forEach(x => selectedSignals.add(x.id)); renderSignalTable(data); };
  const btnOlder = $('#select-older-group');
  if (btnOlder) btnOlder.onclick = () => { olderManual.forEach(x => selectedSignals.add(x.id)); renderSignalTable(data); };

  expandedSignals.forEach(id => {
    if (data.some(x => x.id === id)) loadCardChart(id, false);
  });
}

async function openSignalInManagement(signalId) {
  sf = { category: '', channel: '', status: '', notification_filter: 'all' };
  expandedSignals.add(Number(signalId));
  document.querySelectorAll('nav button,.view').forEach(x => x.classList.remove('active'));
  document.querySelector('[data-view="signals"]').classList.add('active');
  $('#signals').classList.add('active');
  $('#page-title').textContent = document.querySelector('[data-view="signals"]').textContent;
  await loadSignals();
  const card = document.querySelector(`.signal-card[data-card-id="${signalId}"]`);
  if (card) {
    card.scrollIntoView({ behavior: 'smooth', block: 'center' });
    card.style.boxShadow = '0 0 0 2px var(--accent)';
    setTimeout(() => { card.style.boxShadow = ''; }, 2200);
  }
}

async function reviewParse(id) {
  let panel = $('#parse-review'), dialog = $('#parse-review-dialog');
  panel.innerHTML = '<p class="muted">Loading parsed signal review...</p>';
  if (!dialog.open) dialog.showModal();
  try {
    let x = await api('/api/signals/' + id + '/parse-detail'), s = x.signal;
    const extraEntriesStr = Array.isArray(s.additional_entries) ? s.additional_entries.join(', ') : '';
    const targetsStr = Array.isArray(s.targets) ? s.targets.join(', ') : '';

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
          <h3>Manual correction / review</h3>
          <p style="font-size:12px"><b>Status:</b> ${esc(s.parsing_status)} &bull; <b>Confidence:</b> ${esc(s.parsing_confidence)}</p>
          <form id="modal-fix-form" class="form-grid" style="grid-template-columns:1fr 1fr;gap:8px">
            <label>Symbol<input name="symbol" value="${esc(s.symbol || '')}" placeholder="e.g. NIFTY"></label>
            <label>Direction
              <select name="direction">
                <option value="">—</option>
                <option value="BUY" ${s.direction === 'BUY' ? 'selected' : ''}>BUY</option>
                <option value="SELL" ${s.direction === 'SELL' ? 'selected' : ''}>SELL</option>
              </select>
            </label>
            <label>Entry 1<input name="entry_price" type="number" step="any" value="${esc(s.entry_price ?? '')}"></label>
            <label>Extra Entries (comma)<input name="additional_entries" value="${esc(extraEntriesStr)}" placeholder="e.g. 290, 300"></label>
            <label>Stop Loss<input name="stop_loss" type="number" step="any" value="${esc(s.stop_loss ?? '')}"></label>
            <label>Targets (comma)<input name="targets" value="${esc(targetsStr)}" placeholder="e.g. 340, 360"></label>
            <label>Option Type
              <select name="option_type">
                <option value="">None / Futures</option>
                <option value="CE" ${s.option_type === 'CE' ? 'selected' : ''}>CE</option>
                <option value="PE" ${s.option_type === 'PE' ? 'selected' : ''}>PE</option>
              </select>
            </label>
            <label>Strike Price<input name="strike_price" type="number" step="any" value="${esc(s.strike_price ?? '')}"></label>
            <label style="grid-column:span 2">Expiry Date<input name="expiry_date" value="${esc(s.expiry_date || '')}" placeholder="e.g. 26NOV2026"></label>
            <button type="submit" class="primary" style="grid-column:span 2">Save Manual Fix</button>
          </form>
        </article>
      </div>
      <p class="muted" style="font-size:12px;margin:10px 0"><b>Parser note:</b> ${esc(s.parsing_explanation || '—')}</p>
      <h3>Re-parse history</h3>
      ${table(x.history, ['parsed_at', 'run_type', 'status', 'confidence', 'explanation'])}
    `;

    $('#modal-jump-signal').onclick = () => {
      dialog.close();
      openSignalInManagement(s.id);
    };

    $('#modal-reparse-btn').onclick = () => busy($('#modal-reparse-btn'), 'Re-parsing...', async () => {
      await api(`/api/signals/${s.id}/reparse`, { method: 'POST' });
      await reviewParse(s.id);
      if ($('#notifications').classList.contains('active')) await notificationLog();
    });

    $('#modal-fix-form').onsubmit = async e => {
      e.preventDefault();
      const fd = Object.fromEntries(new FormData(e.target));
      const parseNumList = str => String(str || '').split(',').map(v => v.trim()).filter(Boolean).map(Number).filter(n => !isNaN(n));
      const hasManualEntry = fd.entry_price !== '';
      const payload = {
        symbol: (fd.symbol || '').trim().toUpperCase() || null,
        direction: fd.direction || null,
        entry_price: hasManualEntry ? Number(fd.entry_price) : null,
        additional_entries: parseNumList(fd.additional_entries),
        stop_loss: fd.stop_loss !== '' ? Number(fd.stop_loss) : null,
        targets: parseNumList(fd.targets),
        option_type: fd.option_type || null,
        strike_price: fd.strike_price !== '' ? Number(fd.strike_price) : null,
        expiry_date: (fd.expiry_date || '').trim().toUpperCase() || null,
        entry_mode: hasManualEntry ? 'limit' : (s.entry_mode || 'limit'),
        manual_review_status: 'resolved',
      };
      const btn = e.target.querySelector('button[type="submit"]');
      await busy(btn, 'Saving...', async () => {
        await api(`/api/signals/${s.id}`, { method: 'POST', body: JSON.stringify(payload) });
        await reviewParse(s.id);
        if ($('#notifications').classList.contains('active')) await notificationLog();
      });
    };
  } catch (e) {
    panel.innerHTML = `<p class="red">Could not load parse review: ${esc(e.message)}</p>`;
  }
}
async function notificationLog() {
  let q = new URLSearchParams({ limit: $('#notification-limit').value }),
    parsingStatus = $('#notification-parsing-status')?.value || '',
    noticeType = $('#notification-log-type')?.value || 'all',
    channel = $('#notification-channel').value,
    category = $('#notification-category').value,
    dateFrom = $('#notification-date-from').value,
    dateTo = $('#notification-date-to').value;
  if (parsingStatus) q.set('parsing_status', parsingStatus);
  if (noticeType && noticeType !== 'all') q.set('notification_filter', noticeType);
  if (channel) q.set('channel', channel);
  if (category) q.set('category', category);
  if (dateFrom) q.set('date_from', dateFrom);
  if (dateTo) q.set('date_to', dateTo);

  let [items, syncs, filters] = await Promise.all([
    api('/api/notifications?' + q),
    api('/api/sync/logs'),
    api('/api/notification-filters')
  ]);

  let channelSelect = $('#notification-channel'), categorySelect = $('#notification-category');
  if (channelSelect.options.length === 1) {
    channelSelect.innerHTML = '<option value="">All channels</option>' + filters.channels.map(x => `<option>${esc(x)}</option>`).join('');
    categorySelect.innerHTML = '<option value="">All categories</option>' + filters.categories.map(x => `<option>${esc(x)}</option>`).join('');
    channelSelect.value = channel;
    categorySelect.value = category;
  }

  const parseArr = v => {
    if (Array.isArray(v)) return v;
    try { const p = JSON.parse(v || '[]'); return Array.isArray(p) ? p : []; } catch (_) { return []; }
  };

  const host = $('#notifications-table');
  if (!items.length) {
    host.innerHTML = '<p class="muted">No notifications match the selected filters.</p>';
  } else {
    host.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Actions</th>
            <th>Msg ID</th>
            <th>Sent Time</th>
            <th>Channel</th>
            <th>Category</th>
            <th>Parse Status</th>
            <th>Parse Issue / Explanation</th>
            <th>Parsed Summary</th>
            <th>Verification</th>
            <th>Raw Message</th>
          </tr>
        </thead>
        <tbody>
          ${items.map(x => {
            const pStatus = x.parsing_status || 'unparsed';
            const pCls = pStatus === 'parsed' || pStatus === 'manually_corrected' ? 'p-ok' : 'p-warn';
            const tList = parseArr(x.targets);
            const eList = [x.entry_price, ...parseArr(x.additional_entries)].filter(v => v != null);
            const parsedSummary = x.signal_id
              ? `${x.symbol || '—'} ${x.direction || ''} | Entry: ${eList.join('/') || '—'} | SL: ${x.stop_loss ?? '—'} | TP: ${tList.join('/') || '—'}`
              : 'Not parsed';
            const dupTag = x.is_duplicate ? ' <span class="pill badge-orange">Dup</span>' : '';
            const actionsHtml = x.signal_id
              ? `<div style="display:flex;gap:5px">
                   <button type="button" class="ch-btn" data-review-sig="${x.signal_id}">Review / Fix</button>
                   <button type="button" class="ch-btn" data-goto-sig="${x.signal_id}">#${x.signal_id} &rarr;</button>
                 </div>`
              : '<span class="muted">—</span>';
            return `
              <tr>
                <td>${actionsHtml}</td>
                <td>${esc(x.id)}${dupTag}</td>
                <td>${esc(fmtShortTime(x.notification_time || x.received_at))}</td>
                <td>${esc(x.channel || '')}</td>
                <td>${esc(x.category || '')}</td>
                <td><span class="parse-pill ${pCls}">${esc(pStatus)}</span></td>
                <td style="max-width:280px;white-space:normal;font-size:11px;color:var(--muted)">${esc(x.parsing_explanation || '—')}</td>
                <td style="font-size:11px">${esc(parsedSummary)}</td>
                <td>${esc(x.verification_status || '—')}</td>
                <td class="message" title="${esc(x.message)}">${esc(x.message)}</td>
              </tr>`;
          }).join('')}
        </tbody>
      </table>`;

    host.querySelectorAll('[data-review-sig]').forEach(btn => {
      btn.onclick = () => reviewParse(+btn.dataset.reviewSig);
    });
    host.querySelectorAll('[data-goto-sig]').forEach(btn => {
      btn.onclick = () => openSignalInManagement(+btn.dataset.gotoSig);
    });
  }

  $('#notification-sync-history').innerHTML = table(syncs, ['started_at', 'finished_at', 'status', 'imported_rows', 'duplicate_rows', 'skipped_rows', 'failed_rows', 'detail']);
  let last = items[0];
  $('#notification-status').textContent = last ? `Showing ${items.length} stored messages — newest imported ${last.imported_at}` : 'No notifications found for this filter.';
}

async function overview() {
  let x = await api('/api/overview'), m = x.metrics;
  $('#metrics').innerHTML = Object.entries({
    Notifications: m.notifications,
    'Parsed signals': m.parsed,
    Pending: m.pending,
    'Target hits': m.target_hits,
    'Stop-loss hits': m.stop_loss_hits,
    'Win rate': m.win_rate + '%',
    'P&L (INR)': fmtPnl(m.pnl_inr, 'INR'),
    'P&L (USD)': fmtPnl(m.pnl_usd, 'USD'),
  }).map(([k, v]) => `<div class="metric"><span>${k}</span><b>${v}</b></div>`).join('');
  $('#categories').innerHTML = bars(x.categories);
  $('#channels').innerHTML = bars(x.channels);
  $('#recent-signals').innerHTML = table(x.recent_signals, ['id', 'channel', 'symbol', 'direction', 'entry_price', 'verification_status']);
}

async function loadSignals() {
  let f = await api('/api/filters' + (sf.category ? '?category=' + encodeURIComponent(sf.category) : ''));
  $('#category-filters').innerHTML = f.categories.map(c => `<button class="category-filter ${c === sf.category ? 'active' : ''}" data-cat="${esc(c)}">${esc(c)}</button>`).join('');
  let s = $('#channel-filter');
  s.innerHTML = '<option value="">All channels</option>' + f.channels.map(c => `<option>${esc(c)}</option>`).join('');
  s.value = sf.channel;
  $('#verification-filter').value = sf.status;
  $('#notification-type-filter').value = sf.notification_filter;
  document.querySelectorAll('[data-cat]').forEach(b => b.onclick = () => {
    sf.category = sf.category === b.dataset.cat ? '' : b.dataset.cat;
    sf.channel = '';
    loadSignals();
  });
  let q = new URLSearchParams(sf);
  [...q].forEach(([k, v]) => !v && q.delete(k));
  let data = await api('/api/signals' + (q.size ? '?' + q : ''));
  renderSignalTable(data);

  let selectCurrent = () => {
    data.forEach(x => selectedSignals.add(x.id));
    renderSignalTable(data);
  };
  $('#select-all').onclick = selectCurrent;
  $('#select-filtered').onclick = selectCurrent;
  $('#clear-selection').onclick = () => {
    selectedSignals.clear();
    renderSignalTable(data);
  };
  $('#reparse-selected').onclick = () => busy($('#reparse-selected'), 'Re-parsing...', async () => {
    let r = await api('/api/signals/reparse', { method: 'POST', body: JSON.stringify({ signal_ids: [...selectedSignals] }) }),
      counts = r.results.reduce((a, x) => (a[x.status] = (a[x.status] || 0) + 1, a), {});
    $('#process-result').textContent = `Re-parsed ${r.results.length} selected signals — ${Object.entries(counts).map(([k, v]) => `${k}: ${v}`).join(' • ')}`;
    loadSignals();
  });
  $('#delete-selected').onclick = () => {
    const ids = [...selectedSignals];
    if (!ids.length) {
      $('#process-result').textContent = 'Select at least one signal to delete.';
      return;
    }
    if (!confirm(`Delete ${ids.length} selected signal(s)? This cannot be undone.`)) return;
    busy($('#delete-selected'), 'Deleting...', async () => {
      const r = await api('/api/signals/delete', { method: 'POST', body: JSON.stringify({ signal_ids: ids }) });
      ids.forEach(id => { selectedSignals.delete(id); expandedSignals.delete(id); });
      $('#process-result').textContent = `Deleted ${r.deleted} signal(s).`;
      loadSignals();
    });
  };
  $('#process-selected').onclick = () => busy($('#process-selected'), 'Processing...', async () => {
    let r = await api('/api/signals/process', { method: 'POST', body: JSON.stringify({ signal_ids: [...selectedSignals] }) }),
      counts = r.results.reduce((a, x) => (a[x.status] = (a[x.status] || 0) + 1, a), {});
    $('#process-result').textContent = `Processed ${r.results.length} signals — ${Object.entries(counts).map(([k, v]) => `${k}: ${v}`).join(' • ')}`;
    selectedSignals.clear();
    loadSignals();
  });
}

async function integrations() {
  let a = await api('/api/integrations'), by = Object.fromEntries(a.map(x => [x.provider, x]));
  [['google_sheets', $('#sheets-form')], ['binance', $('#binance-config')], ['twelve_data', $('#twelve-form')]].forEach(([id, f]) =>
    Object.entries(by[id]?.config || {}).forEach(([k, v]) => f[k] && (f[k].value = Array.isArray(v) ? v.join(', ') : v))
  );
}

async function coverage() {
  $('#coverage').innerHTML = table(await api('/api/market/coverage'), ['provider', 'symbol', 'exchange', 'interval', 'candles', 'earliest', 'latest', 'last_retrieved']);
}

const PROVIDER_INTERVALS = {
  angel_one: [
    ['ONE_MINUTE', '1 minute (ONE_MINUTE)'],
    ['THREE_MINUTE', '3 minutes (THREE_MINUTE)'],
    ['FIVE_MINUTE', '5 minutes (FIVE_MINUTE)'],
    ['TEN_MINUTE', '10 minutes (TEN_MINUTE)'],
    ['FIFTEEN_MINUTE', '15 minutes (FIFTEEN_MINUTE)'],
    ['THIRTY_MINUTE', '30 minutes (THIRTY_MINUTE)'],
    ['ONE_HOUR', '1 hour (ONE_HOUR)'],
    ['ONE_DAY', '1 day (ONE_DAY)'],
  ],
  twelve_data: [
    ['1min', '1 minute (1min)'],
    ['5min', '5 minutes (5min)'],
    ['15min', '15 minutes (15min)'],
    ['1h', '1 hour (1h)'],
    ['4h', '4 hours (4h)'],
    ['1day', '1 day (1day)'],
  ],
  binance: [
    ['1m', '1 minute (1m)'],
    ['3m', '3 minutes (3m)'],
    ['5m', '5 minutes (5m)'],
    ['15m', '15 minutes (15m)'],
    ['30m', '30 minutes (30m)'],
    ['1h', '1 hour (1h)'],
    ['2h', '2 hours (2h)'],
    ['4h', '4 hours (4h)'],
    ['1d', '1 day (1d)'],
  ],
};

let aoCatalogTimer = null;
let aoAutoRefreshed = false;

function syncResolvedSelection() {
  const provider = $('#candle-provider').value;
  const interval = $('#candle-interval').value;
  const symInput = $('#resolved-symbol');
  const exchInput = $('#resolved-exchange');
  const preview = $('#resolved-preview');
  if (provider === 'angel_one') {
    const sel = $('#ao-contract');
    const opt = sel.options[sel.selectedIndex];
    const token = opt ? opt.value : '';
    const exchange = opt?.dataset?.exchange || ($('#ao-category').value.split('|')[0] || 'NFO');
    const label = opt?.dataset?.label || '';
    symInput.value = token;
    exchInput.value = exchange;
    preview.textContent = token
      ? `Ready — Provider: Angel One | Exchange: ${exchange} | Contract: ${label} | Token: ${token} | Interval: ${interval}`
      : 'No valid Angel One contract selected. Click "Refresh Angel One Master" above.';
  } else if (provider === 'twelve_data') {
    const sym = $('#td-symbol').value;
    symInput.value = sym;
    exchInput.value = 'FOREX';
    preview.textContent = `Ready — Provider: Twelve Data | Symbol: ${sym} | Interval: ${interval}`;
  } else {
    const sym = $('#bn-symbol').value;
    symInput.value = sym;
    exchInput.value = 'USD-M';
    preview.textContent = `Ready — Provider: Binance USD-M | Symbol: ${sym} | Interval: ${interval}`;
  }
}

async function loadAngelCatalog(resetUnderlying = false, resetExpiry = false) {
  const [exchange, instrument_type] = $('#ao-category').value.split('|');
  const params = new URLSearchParams({ exchange, instrument_type });
  if (!resetUnderlying && $('#ao-underlying').value) params.set('underlying', $('#ao-underlying').value);
  if (!resetExpiry && $('#ao-expiry').value) params.set('expiry', $('#ao-expiry').value);
  if ($('#ao-filter').value.trim()) params.set('q', $('#ao-filter').value.trim());
  try {
    const data = await api('/api/angel-one/catalog?' + params.toString());
    const uSel = $('#ao-underlying');
    const eSel = $('#ao-expiry');
    const cSel = $('#ao-contract');
    if (!data.total_instruments && !data.contracts.length) {
      if (!aoAutoRefreshed) {
        aoAutoRefreshed = true;
        uSel.innerHTML = '<option value="">Downloading Angel One master...</option>';
        cSel.innerHTML = '<option value="">Fetching live contracts from Angel One...</option>';
        const res = await api('/api/angel-one/instruments/refresh', { method: 'POST' });
        $('#candle-result').textContent = `Loaded ${res.instruments} active contracts from Angel One.`;
        return loadAngelCatalog(true, true);
      }
      uSel.innerHTML = '<option value="">Master not downloaded</option>';
      eSel.innerHTML = '<option value="">—</option>';
      cSel.innerHTML = '<option value="">Click "Refresh Angel One Master" first</option>';
      syncResolvedSelection();
      return;
    }
    uSel.innerHTML = data.underlyings.length
      ? data.underlyings.map(u => `<option value="${esc(u)}">${esc(u)}</option>`).join('')
      : '<option value="">No underlyings</option>';
    if (data.selected_underlying) uSel.value = data.selected_underlying;
    eSel.innerHTML = data.expiries.length
      ? data.expiries.map(e => `<option value="${esc(e)}">${esc(e)}</option>`).join('')
      : '<option value="">Spot / No Expiry</option>';
    if (data.selected_expiry) eSel.value = data.selected_expiry;
    cSel.innerHTML = data.contracts.length
      ? data.contracts.map(c => {
          const meta = [c.expiry, c.lot_size ? `Lot ${c.lot_size}` : ''].filter(Boolean).join(' • ');
          return `<option value="${esc(c.instrument_token)}" data-exchange="${esc(c.exchange_segment)}" data-label="${esc(c.trading_symbol)}">${esc(c.trading_symbol)} (${esc(meta || c.instrument_token)})</option>`;
        }).join('')
      : '<option value="">No matching contracts</option>';
  } catch (err) {
    $('#ao-contract').innerHTML = `<option value="">Error: ${esc(err.message)}</option>`;
  }
  syncResolvedSelection();
}

async function loadTwelveCustomSymbols() {
  try {
    const list = await api('/api/integrations');
    const td = list.find(x => x.provider === 'twelve_data');
    const defaults = new Set(['XAU/USD', 'WTI/USD', 'XBR/USD', 'XAG/USD', 'EUR/USD', 'GBP/USD', 'USD/JPY', 'AUD/USD', 'USD/CAD', 'USD/CHF', 'NZD/USD']);
    const extras = (td?.config?.symbols || []).filter(s => s && !defaults.has(s.toUpperCase()));
    $('#td-custom-group').innerHTML = extras.map(s => `<option value="${esc(s)}">${esc(s)}</option>`).join('');
  } catch (_) {}
}

function updateProviderUI() {
  const provider = $('#candle-provider').value;
  document.querySelectorAll('.ao-field').forEach(el => el.style.display = provider === 'angel_one' ? 'grid' : 'none');
  document.querySelectorAll('.td-field').forEach(el => el.style.display = provider === 'twelve_data' ? 'grid' : 'none');
  document.querySelectorAll('.bn-field').forEach(el => el.style.display = provider === 'binance' ? 'grid' : 'none');
  const intSel = $('#candle-interval');
  const prev = intSel.value;
  const opts = PROVIDER_INTERVALS[provider] || PROVIDER_INTERVALS.binance;
  intSel.innerHTML = opts.map(([val, label]) => `<option value="${val}">${label}</option>`).join('');
  if (opts.some(([val]) => val === prev)) intSel.value = prev;
  if (provider === 'angel_one') {
    loadAngelCatalog(false, false);
  } else {
    if (provider === 'twelve_data') loadTwelveCustomSymbols();
    syncResolvedSelection();
  }
}

async function initMarketView() {
  await coverage();
  updateProviderUI();
}

let defaultSlState = [];

function renderDefaultSlCategories() {
  const host = $('#default-sl-categories');
  if (!host) return;
  host.innerHTML = defaultSlState.map((cat, cIdx) => {
    const isUsd = cat.sizing_mode === 'usd';
    const shortUnit = cat.unit === 'pips' ? 'pips' : cat.unit === 'pct' ? '%' : 'pts';
    return `
    <div class="sl-cat-card" data-cat-idx="${cIdx}">
      <div class="sl-cat-header">
        <div>
          <h3>${esc(cat.title)}</h3>
          <span class="muted" style="font-size:11px">SL/TP Unit: <b>${esc(cat.unit_label)}</b> &bull; Sizing: <b>${isUsd ? 'Fixed USD ($) Capital' : 'Lots × Lot Size'}</b></span>
        </div>
        <div class="sl-cat-fallback-grid">
          <label>Fallback SL (${esc(shortUnit)})<input type="number" step="any" min="0" placeholder="—" data-cat-field="category_default" data-cat-idx="${cIdx}" value="${esc(cat.category_default)}"></label>
          <label>TP1 (${esc(shortUnit)})<input type="number" step="any" min="0" placeholder="—" data-cat-field="tp1" data-cat-idx="${cIdx}" value="${esc(cat.tp1)}"></label>
          <label>TP2 (${esc(shortUnit)})<input type="number" step="any" min="0" placeholder="—" data-cat-field="tp2" data-cat-idx="${cIdx}" value="${esc(cat.tp2)}"></label>
          <label>TP3 (${esc(shortUnit)})<input type="number" step="any" min="0" placeholder="—" data-cat-field="tp3" data-cat-idx="${cIdx}" value="${esc(cat.tp3)}"></label>
          ${isUsd
            ? `<label>USD ($) / Trade<input type="number" step="any" min="0" placeholder="100" data-cat-field="usd_amount" data-cat-idx="${cIdx}" value="${esc(cat.usd_amount)}"></label>`
            : `<label>Default Lots<input type="number" step="any" min="0" placeholder="1" data-cat-field="lots" data-cat-idx="${cIdx}" value="${esc(cat.lots)}"></label>
               <label>Fallback Lot Size<input type="number" step="any" min="0" placeholder="Auto" data-cat-field="lot_size" data-cat-idx="${cIdx}" value="${esc(cat.lot_size)}"></label>`
          }
        </div>
      </div>
      <div class="sl-symbols-grid">
        ${cat.symbols.map((item, sIdx) => `
          <div class="sl-sym-item">
            <div class="sl-sym-top">
              <b>${esc(item.label)}</b>
              <span>${esc(item.hint || shortUnit)}</span>
            </div>
            <div class="sl-mini-grid">
              <label>SL<input type="number" step="any" min="0" placeholder="—" data-sym-field="sl" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.sl)}"></label>
              <label>TP1<input type="number" step="any" min="0" placeholder="—" data-sym-field="tp1" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.tp1)}"></label>
              <label>TP2<input type="number" step="any" min="0" placeholder="—" data-sym-field="tp2" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.tp2)}"></label>
              <label>TP3<input type="number" step="any" min="0" placeholder="—" data-sym-field="tp3" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.tp3)}"></label>
              ${isUsd
                ? `<label class="span-2">USD ($)<input type="number" step="any" min="0" placeholder="Cat. default" data-sym-field="usd_amount" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.usd_amount)}"></label>`
                : `<label>Lots<input type="number" step="any" min="0" placeholder="Cat." data-sym-field="lots" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.lots)}"></label>
                   <label>Lot Size<input type="number" step="any" min="0" placeholder="Auto" data-sym-field="lot_size" data-cat-idx="${cIdx}" data-sym-idx="${sIdx}" value="${esc(item.lot_size)}"></label>`
              }
            </div>
          </div>
        `).join('')}
      </div>
      <div class="sl-add-row">
        <input type="text" placeholder="Symbol (e.g. RELIANCE)" data-add-sym="${cIdx}">
        ${cat.key === 'MCX' ? `<select data-add-mcx-type="${cIdx}"><option value="FUT">Futures</option><option value="OPT">Options (CE/PE)</option></select>` : ''}
        <input type="number" step="any" min="0" placeholder="SL (${esc(shortUnit)})" data-add-sl="${cIdx}" style="width:105px">
        <input type="number" step="any" min="0" placeholder="TP1" data-add-tp1="${cIdx}" style="width:85px">
        <input type="number" step="any" min="0" placeholder="TP2" data-add-tp2="${cIdx}" style="width:85px">
        <input type="number" step="any" min="0" placeholder="TP3" data-add-tp3="${cIdx}" style="width:85px">
        ${isUsd
          ? `<input type="number" step="any" min="0" placeholder="USD ($)" data-add-usd="${cIdx}" style="width:105px">`
          : `<input type="number" step="any" min="0" placeholder="Lots" data-add-lots="${cIdx}" style="width:80px">
             <input type="number" step="any" min="0" placeholder="Lot Size" data-add-lotsize="${cIdx}" style="width:95px">`
        }
        <button type="button" data-add-btn="${cIdx}">+ Add Symbol</button>
      </div>
    </div>
  `;
  }).join('');

  host.querySelectorAll('[data-cat-field]').forEach(inp => {
    inp.oninput = () => {
      defaultSlState[+inp.dataset.catIdx][inp.dataset.catField] = inp.value;
    };
  });
  host.querySelectorAll('[data-sym-field]').forEach(inp => {
    inp.oninput = () => {
      defaultSlState[+inp.dataset.catIdx].symbols[+inp.dataset.symIdx][inp.dataset.symField] = inp.value;
    };
  });
  host.querySelectorAll('[data-add-btn]').forEach(btn => {
    btn.onclick = () => {
      const cIdx = +btn.dataset.addBtn;
      const cat = defaultSlState[cIdx];
      const symInput = host.querySelector(`[data-add-sym="${cIdx}"]`);
      const rawSym = (symInput.value || '').trim().toUpperCase().replace(/\s+/g, '');
      if (!rawSym) return;
      let key = rawSym;
      let label = rawSym;
      if (cat.key === 'MCX') {
        const mType = host.querySelector(`[data-add-mcx-type="${cIdx}"]`).value;
        key = `${rawSym}:${mType}`;
        label = `${rawSym} (${mType === 'OPT' ? 'Options CE/PE' : 'Futures'})`;
      }
      const newVals = {
        sl: host.querySelector(`[data-add-sl="${cIdx}"]`)?.value || '',
        tp1: host.querySelector(`[data-add-tp1="${cIdx}"]`)?.value || '',
        tp2: host.querySelector(`[data-add-tp2="${cIdx}"]`)?.value || '',
        tp3: host.querySelector(`[data-add-tp3="${cIdx}"]`)?.value || '',
        lots: host.querySelector(`[data-add-lots="${cIdx}"]`)?.value || '',
        lot_size: host.querySelector(`[data-add-lotsize="${cIdx}"]`)?.value || '',
        usd_amount: host.querySelector(`[data-add-usd="${cIdx}"]`)?.value || '',
      };
      const existing = cat.symbols.find(x => x.key === key);
      if (existing) {
        Object.assign(existing, newVals);
      } else {
        cat.symbols.push({ key, label, hint: '', ...newVals });
      }
      renderDefaultSlCategories();
    };
  });
}

async function loadDefaultSlSettings() {
  const res = await api('/api/default-sl');
  defaultSlState = res.categories || [];
  renderDefaultSlCategories();
}

function numOrNull(v) {
  if (v === '' || v == null) return null;
  const n = Number(v);
  return isNaN(n) ? null : n;
}

function show(view) {
  document.querySelectorAll('nav button,.view').forEach(x => x.classList.remove('active'));
  document.querySelector(`[data-view="${view}"]`).classList.add('active');
  $('#' + view).classList.add('active');
  $('#page-title').textContent = document.querySelector(`[data-view="${view}"]`).textContent;
  ({
    overview,
    notifications: notificationLog,
    integrations,
    signals: loadSignals,
    market: initMarketView,
    analytics: async () => {
      const data = await api('/api/analytics/channels');
      const formatted = data.map(r => ({
        Channel: r.channel,
        'Total Signals': r.total,
        'Today Signals': r.today_total || 0,
        'Today TP Hit': r.today_tp || 0,
        'Today SL Hit': r.today_sl || 0,
        'Trades Triggered': r.trades_triggered || 0,
        'All-Time TP': r.targets || 0,
        'All-Time SL': r.stops || 0,
        'Total P&L (INR)': fmtPnl(r.pnl_inr, 'INR'),
        'Total P&L (USD)': fmtPnl(r.pnl_usd, 'USD'),
        'Today P&L (INR)': fmtPnl(r.today_pnl_inr, 'INR'),
        'Today P&L (USD)': fmtPnl(r.today_pnl_usd, 'USD'),
      }));
      $('#analytics-table').innerHTML = table(formatted, [
        'Channel', 'Total Signals', 'Today Signals', 'Today TP Hit', 'Today SL Hit',
        'Trades Triggered', 'All-Time TP', 'All-Time SL',
        'Total P&L (INR)', 'Total P&L (USD)', 'Today P&L (INR)', 'Today P&L (USD)',
      ]);
    },
    logs: async () => $('#logs-table').innerHTML = table(await api('/api/logs'), ['created_at', 'severity', 'source', 'message']),
    settings: async () => {
      let x = await api('/api/settings');
      Object.entries(x).forEach(([k, v]) => $('#settings-form')[k] && ($('#settings-form')[k].value = v));
      await loadDefaultSlSettings();
    }
  }[view] || (() => {}))();
}

$('#close-parse-review').onclick = () => $('#parse-review-dialog').close();
document.querySelectorAll('nav button').forEach(b => b.onclick = () => show(b.dataset.view));

$('#refresh-signals').onclick = () => busy($('#refresh-signals'), 'Refreshing...', loadSignals);
$('#channel-filter').onchange = e => { sf.channel = e.target.value; loadSignals(); };
$('#verification-filter').onchange = e => { sf.status = e.target.value; loadSignals(); };
$('#notification-type-filter').onchange = e => { sf.notification_filter = e.target.value; loadSignals(); };
$('#clear-signal-filters').onclick = () => { sf = { category: '', channel: '', status: '', notification_filter: 'primary' }; loadSignals(); };

$('#refresh-notifications').onclick = () => busy($('#refresh-notifications'), 'Refreshing...', notificationLog);
['#notification-limit', '#notification-parsing-status', '#notification-log-type', '#notification-channel', '#notification-category', '#notification-date-from', '#notification-date-to'].forEach(id => {
  if ($(id)) $(id).onchange = notificationLog;
});
$('#clear-notification-filters').onclick = () => {
  ['#notification-parsing-status', '#notification-channel', '#notification-category', '#notification-date-from', '#notification-date-to'].forEach(id => {
    if ($(id)) $(id).value = '';
  });
  if ($('#notification-log-type')) $('#notification-log-type').value = 'all';
  $('#notification-limit').value = '50';
  notificationLog();
};
setInterval(() => {
  if ($('#notifications').classList.contains('active') && !$('#parse-review-dialog')?.open && !document.querySelector('#notifications-table td.excel-cell-selected')) {
    notificationLog();
  }
}, 5000);
$('#sheets-form').onsubmit = async e => {
  e.preventDefault();
  let d = Object.fromEntries(new FormData(e.target));
  let b = e.submitter || e.target.querySelector('button');
  await busy(b, 'Saving...', async () => {
    await api('/api/integrations/google_sheets', {
      method: 'PUT',
      body: JSON.stringify({
        config: { apps_script_url: d.apps_script_url, worksheets: d.worksheets.split(',').map(x => x.trim()).filter(Boolean) },
        secrets: { access_token: d.access_token }
      })
    });
    await api('/api/settings', { method: 'PUT', body: JSON.stringify({ sync_interval_seconds: d.sync_interval_seconds, google_sheets_auto_sync: d.auto_sync }) });
    $('#sheets-result').textContent = 'Saved.';
  });
};
$('#sheets-test').onclick = () => busy($('#sheets-test'), 'Syncing...', async () => {
  try {
    $('#sheets-result').textContent = 'Contacting Google Sheets...';
    $('#sheets-result').textContent = JSON.stringify(await api('/api/sync', { method: 'POST' }));
  } catch (e) {
    $('#sheets-result').textContent = e.message;
  }
});
$('#sheets-history').onclick = $('#sheets-test').onclick;

$('#binance-config').onsubmit = async e => {
  e.preventDefault();
  let b = e.submitter || e.target.querySelector('button');
  await busy(b, 'Saving...', async () => {
    await api('/api/integrations/binance', { method: 'PUT', body: JSON.stringify({ config: Object.fromEntries(new FormData(e.target)), secrets: {} }) });
    $('#binance-result').textContent = 'Saved.';
  });
};
$('#binance-test').onclick = () => busy($('#binance-test'), 'Testing...', async () => {
  try {
    $('#binance-result').textContent = 'Testing Binance endpoint...';
    $('#binance-result').textContent = JSON.stringify(await api('/api/binance/test', { method: 'POST' }));
  } catch (e) {
    $('#binance-result').textContent = e.message;
  }
});

$('#twelve-form').onsubmit = async e => {
  e.preventDefault();
  let d = Object.fromEntries(new FormData(e.target));
  await api('/api/integrations/twelve_data', {
    method: 'PUT',
    body: JSON.stringify({
      config: { base_url: d.base_url, symbols: d.symbols.split(',').map(x => x.trim()).filter(Boolean), timezone: d.timezone, outputsize: 5000 },
      secrets: { api_key: d.api_key }
    })
  });
  $('#twelve-result').textContent = 'Saved; API key is masked.';
};

$('#angel-form').onsubmit = async e => {
  e.preventDefault();
  await api('/api/integrations/angel_one', { method: 'PUT', body: JSON.stringify({ config: {}, secrets: Object.fromEntries(new FormData(e.target)) }) });
  $('#angel-result').textContent = 'Saved; credentials are masked.';
};
$('#angel-test').onclick = async () => {
  try { $('#angel-result').textContent = JSON.stringify(await api('/api/angel-one/test', { method: 'POST' })); }
  catch (e) { $('#angel-result').textContent = e.message; }
};
$('#master-refresh').onclick = async () => {
  try { $('#angel-result').textContent = JSON.stringify(await api('/api/angel-one/instruments/refresh', { method: 'POST' })); }
  catch (e) { $('#angel-result').textContent = e.message; }
};

$('#market-master-refresh').onclick = () => busy($('#market-master-refresh'), 'Refreshing master...', async () => {
  try {
    const res = await api('/api/angel-one/instruments/refresh', { method: 'POST' });
    $('#candle-result').textContent = `Instrument master updated (${res.instruments} contracts).`;
    await loadAngelCatalog(true, true);
  } catch (e) {
    $('#candle-result').textContent = e.message;
  }
});

$('#candle-provider').onchange = updateProviderUI;
$('#ao-category').onchange = () => { $('#ao-filter').value = ''; loadAngelCatalog(true, true); };
$('#ao-underlying').onchange = () => { $('#ao-filter').value = ''; loadAngelCatalog(false, true); };
$('#ao-expiry').onchange = () => loadAngelCatalog(false, false);
$('#ao-filter').oninput = () => { clearTimeout(aoCatalogTimer); aoCatalogTimer = setTimeout(() => loadAngelCatalog(false, false), 250); };
$('#ao-contract').onchange = syncResolvedSelection;
$('#td-symbol').onchange = syncResolvedSelection;
$('#bn-symbol').onchange = syncResolvedSelection;
$('#candle-interval').onchange = syncResolvedSelection;

$('#candle-form').onsubmit = async e => {
  e.preventDefault();
  syncResolvedSelection();
  let d = Object.fromEntries(new FormData(e.target)),
    url = d.provider === 'twelve_data' ? '/api/twelve-data/candles' : d.provider === 'angel_one' ? '/api/angel-one/candles' : '/api/candles/fetch',
    body = d.provider === 'twelve_data'
      ? { symbol: d.symbol, interval: d.interval, start: d.start, end: d.end }
      : d.provider === 'angel_one'
        ? { exchange: d.exchange, instrument_token: d.symbol, interval: d.interval, start: d.start, end: d.end }
        : { provider: 'binance', symbol: d.symbol, timeframe: d.interval, start: d.start, end: d.end },
    b = e.submitter || e.target.querySelector('button');
  await busy(b, 'Downloading...', async () => {
    try {
      $('#candle-result').textContent = `Downloading ${d.symbol} ${d.interval} candles — please wait.`;
      $('#candle-result').textContent = JSON.stringify(await api(url, { method: 'POST', body: JSON.stringify(body) }));
      if (d.provider === 'twelve_data') {
        let u = await api('/api/twelve-data/usage');
        $('#twelve-usage').textContent = `Twelve Data credits: ${u.daily_used}/${u.daily_allowance} today; ${u.minute_used}/${u.minute_allowance} this minute.`;
      }
      coverage();
    } catch (x) {
      $('#candle-result').textContent = x.message;
    }
  });
};

$('#instrument-form').onsubmit = async e => {
  e.preventDefault();
  let x = await api('/api/angel-one/instruments?' + new URLSearchParams(Object.fromEntries(new FormData(e.target))));
  $('#instruments').innerHTML = table(x, ['trading_symbol', 'instrument_token', 'exchange_segment', 'instrument_type', 'expiry', 'strike', 'lot_size']);
};
$('#parse-preview').onclick = async () => $('#parse-output').textContent = JSON.stringify(await api('/api/parse', { method: 'POST', body: JSON.stringify({ message: $('#parse-message').value }) }), null, 2);
$('#settings-form').onsubmit = async e => {
  e.preventDefault();
  await api('/api/settings', { method: 'PUT', body: JSON.stringify(Object.fromEntries(new FormData(e.target))) });
  alert('Settings saved');
};

$('#save-default-sl').onclick = () => busy($('#save-default-sl'), 'Saving...', async () => {
  const rules = {};
  for (const cat of defaultSlState) {
    const syms = {};
    for (const item of cat.symbols) {
      syms[item.key] = {
        sl: numOrNull(item.sl),
        tp1: numOrNull(item.tp1),
        tp2: numOrNull(item.tp2),
        tp3: numOrNull(item.tp3),
        lots: numOrNull(item.lots),
        lot_size: numOrNull(item.lot_size),
        usd_amount: numOrNull(item.usd_amount),
      };
    }
    rules[cat.key] = {
      category_default: numOrNull(cat.category_default),
      tp1: numOrNull(cat.tp1),
      tp2: numOrNull(cat.tp2),
      tp3: numOrNull(cat.tp3),
      lots: numOrNull(cat.lots) || 1,
      lot_size: numOrNull(cat.lot_size),
      usd_amount: numOrNull(cat.usd_amount) || 100,
      symbols: syms,
    };
  }
  await api('/api/default-sl', { method: 'PUT', body: JSON.stringify({ rules }) });
  $('#default-sl-result').textContent = 'Default SL, TP1–TP3, and Position Sizing rules saved.';
  await loadDefaultSlSettings();
});

$('#apply-default-sl-existing').onclick = () => busy($('#apply-default-sl-existing'), 'Applying...', async () => {
  const res = await api('/api/default-sl/apply-missing', { method: 'POST' });
  $('#default-sl-result').textContent = `Checked ${res.checked} signals — applied Default SL/TPs to ${res.updated} signal(s).`;
});

$('#backup').onclick = async () => $('#backup-result').textContent = JSON.stringify(await api('/api/backup', { method: 'POST' }));

{
  let f = $('#candle-form'), now = new Date(),
    end = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 15, 30),
    start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 9, 15),
    localFmt = d => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  if (!f.start.value) f.start.value = localFmt(start);
  if (!f.end.value) f.end.value = localFmt(end);
}
overview();
/* Excel-style Rectangular & Multi-Column Selection for Tables */
/* Excel-style Rectangular & Multi-Column Selection with Right-Click & Ctrl+C Copy */
(() => {
  let activeTable = null;
  let anchor = null; // { r, c }
  let isDragging = false;
  let dragMode = 'cell'; // 'cell' | 'col'
  let baseSelection = new Set();
  let selectedCells = new Set(); // keys: "r,c"

  function closeContextMenu() {
    document.querySelectorAll('.excel-context-menu').forEach(el => el.remove());
  }

  function showCopyToast(msg) {
    document.querySelectorAll('.excel-copy-toast').forEach(el => el.remove());
    const toast = document.createElement('div');
    toast.className = 'excel-copy-toast';
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 2200);
  }

  function updateFloatingBar() {
    document.querySelectorAll('.excel-floating-bar').forEach(el => el.remove());
    if (!activeTable || !selectedCells.size) return;

    const rowsSet = new Set();
    const colsSet = new Set();
    selectedCells.forEach(k => {
      const [r, c] = k.split(',');
      rowsSet.add(r);
      colsSet.add(c);
    });

    const bar = document.createElement('div');
    bar.className = 'excel-floating-bar';
    Object.assign(bar.style, {
      position: 'fixed', bottom: '18px', right: '22px', zIndex: '99998',
      background: '#141c31', border: '1px solid var(--accent)', borderRadius: '8px',
      padding: '7px 10px', display: 'flex', alignItems: 'center', gap: '8px',
      boxShadow: '0 8px 24px rgba(0, 0, 0, 0.5)', fontSize: '12px'
    });
    bar.innerHTML = `
      <span style="color:var(--muted)"><b>${rowsSet.size}</b> row(s) &times; <b>${colsSet.size}</b> col(s)</span>
      <button type="button" class="primary" data-fcopy="plain" style="padding:5px 10px;font-size:12px">Copy (Ctrl+C)</button>
      <button type="button" data-fcopy="headers" style="padding:5px 9px;font-size:12px">+ Headers</button>
      <button type="button" data-fcopy="clear" style="padding:5px 8px;font-size:12px">&times;</button>
    `;
    bar.querySelector('[data-fcopy="plain"]').onclick = () => performCopy(false);
    bar.querySelector('[data-fcopy="headers"]').onclick = () => performCopy(true);
    bar.querySelector('[data-fcopy="clear"]').onclick = clearSelection;
    document.body.appendChild(bar);
  }

  function clearSelection() {
    closeContextMenu();
    if (activeTable) {
      activeTable.querySelectorAll('.excel-cell-selected').forEach(td => td.classList.remove('excel-cell-selected'));
      activeTable.querySelectorAll('.excel-col-active').forEach(th => th.classList.remove('excel-col-active'));
    }
    selectedCells.clear();
    baseSelection.clear();
    activeTable = null;
    anchor = null;
    updateFloatingBar();
  }

  function renderSelection(table) {
    if (!table) return;
    table.classList.add('excel-selectable');
    const bodyRows = Array.from(table.querySelectorAll('tbody tr'));
    const activeCols = new Set();

    bodyRows.forEach((tr, rIdx) => {
      Array.from(tr.children).forEach((td, cIdx) => {
        const key = `${rIdx},${cIdx}`;
        if (selectedCells.has(key)) {
          td.classList.add('excel-cell-selected');
          activeCols.add(cIdx);
        } else {
          td.classList.remove('excel-cell-selected');
        }
      });
    });

    table.querySelectorAll('thead th').forEach((th, cIdx) => {
      th.classList.toggle('excel-col-active', activeCols.has(cIdx));
    });

    updateFloatingBar();
  }

  function rectCells(r1, c1, r2, c2) {
    const out = new Set();
    const minR = Math.min(r1, r2), maxR = Math.max(r1, r2);
    const minC = Math.min(c1, c2), maxC = Math.max(c1, c2);
    for (let r = minR; r <= maxR; r++) {
      for (let c = minC; c <= maxC; c++) {
        out.add(`${r},${c}`);
      }
    }
    return out;
  }

  function colCells(table, c1, c2) {
    const rowCount = table.querySelectorAll('tbody tr').length;
    return rectCells(0, c1, Math.max(0, rowCount - 1), c2);
  }

  function escapeTsvCell(raw) {
    const txt = String(raw ?? '').trim();
    if (/[\n\r\t"]/.test(txt)) {
      return '"' + txt.replace(/"/g, '""') + '"';
    }
    return txt;
  }

  function performCopy(includeHeaders = false) {
    closeContextMenu();
    if (!activeTable || !selectedCells.size) return;

    const bodyRows = Array.from(activeTable.querySelectorAll('tbody tr'));
    const headCells = Array.from(activeTable.querySelectorAll('thead th'));
    const byRow = new Map();
    const allCols = new Set();

    selectedCells.forEach(key => {
      const [r, c] = key.split(',').map(Number);
      if (!byRow.has(r)) byRow.set(r, new Set());
      byRow.get(r).add(c);
      allCols.add(c);
    });

    const sortedCols = Array.from(allCols).sort((a, b) => a - b);
    const sortedRows = Array.from(byRow.keys()).sort((a, b) => a - b);
    const lines = [];

    if (includeHeaders && headCells.length) {
      lines.push(sortedCols.map(c => escapeTsvCell(headCells[c]?.innerText || '')).join('\t'));
    }

    for (const r of sortedRows) {
      const rowCols = byRow.get(r);
      lines.push(
        sortedCols
          .map(c => (rowCols.has(c) ? escapeTsvCell(bodyRows[r]?.children[c]?.innerText || '') : ''))
          .join('\t')
      );
    }

    const tsv = lines.join('\n');

    const ta = document.createElement('textarea');
    ta.value = tsv;
    ta.setAttribute('readonly', '');
    Object.assign(ta.style, { position: 'fixed', left: '-9999px', top: '0', opacity: '0' });
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    let copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (_) {}
    document.body.removeChild(ta);

    if (!copied && navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(tsv).catch(() => {});
    }

    showCopyToast(`Copied ${sortedRows.length} row(s) × ${sortedCols.length} col(s)`);
  }

  document.addEventListener('mousedown', e => {
    if (e.target.closest('.excel-context-menu') || e.target.closest('.excel-floating-bar')) return;
    closeContextMenu();

    if (e.button === 2) {
      const td = e.target.closest('.table-wrap td');
      const th = e.target.closest('.table-wrap th');
      const cell = td || th;
      if (!cell) return;
      const table = cell.closest('table');
      if (!table || !table.querySelector('tbody')) return;

      if (th) {
        const cIdx = Array.from(th.parentElement.children).indexOf(th);
        const alreadyInCol = activeTable === table && selectedCells.has(`0,${cIdx}`);
        if (!alreadyInCol) {
          if (activeTable && activeTable !== table) clearSelection();
          activeTable = table;
          anchor = { r: 0, c: cIdx };
          selectedCells = colCells(table, cIdx, cIdx);
          renderSelection(table);
        }
      } else if (td) {
        const tr = td.parentElement;
        const rIdx = Array.from(tr.parentElement.children).indexOf(tr);
        const cIdx = Array.from(tr.children).indexOf(td);
        if (activeTable !== table || !selectedCells.has(`${rIdx},${cIdx}`)) {
          if (activeTable && activeTable !== table) clearSelection();
          activeTable = table;
          anchor = { r: rIdx, c: cIdx };
          selectedCells = new Set([`${rIdx},${cIdx}`]);
          renderSelection(table);
        }
      }
      return;
    }

    if (e.button !== 0) return;
    if (e.target.closest('button, a, input, select, textarea')) return;

    const td = e.target.closest('.table-wrap td');
    const th = e.target.closest('.table-wrap th');
    const cell = td || th;

    if (!cell) {
      clearSelection();
      return;
    }

    const table = cell.closest('table');
    if (!table || !table.querySelector('tbody')) return;

    if (activeTable && activeTable !== table) {
      clearSelection();
    }
    activeTable = table;
    isDragging = true;

    if (th) {
      dragMode = 'col';
      const cIdx = Array.from(th.parentElement.children).indexOf(th);
      if (e.shiftKey && anchor) {
        selectedCells = new Set([...baseSelection, ...colCells(table, anchor.c, cIdx)]);
      } else if (e.ctrlKey || e.metaKey) {
        anchor = { r: 0, c: cIdx };
        const colSet = colCells(table, cIdx, cIdx);
        const allPresent = Array.from(colSet).every(k => selectedCells.has(k));
        if (allPresent) colSet.forEach(k => selectedCells.delete(k));
        else colSet.forEach(k => selectedCells.add(k));
        baseSelection = new Set(selectedCells);
      } else {
        anchor = { r: 0, c: cIdx };
        selectedCells = colCells(table, cIdx, cIdx);
        baseSelection = new Set(selectedCells);
      }
      renderSelection(table);
      return;
    }

    dragMode = 'cell';
    const tr = td.parentElement;
    const rIdx = Array.from(tr.parentElement.children).indexOf(tr);
    const cIdx = Array.from(tr.children).indexOf(td);

    if (e.shiftKey && anchor) {
      selectedCells = new Set([...baseSelection, ...rectCells(anchor.r, anchor.c, rIdx, cIdx)]);
    } else if (e.ctrlKey || e.metaKey) {
      anchor = { r: rIdx, c: cIdx };
      const key = `${rIdx},${cIdx}`;
      if (selectedCells.has(key)) selectedCells.delete(key);
      else selectedCells.add(key);
      baseSelection = new Set(selectedCells);
    } else {
      anchor = { r: rIdx, c: cIdx };
      selectedCells = new Set([`${rIdx},${cIdx}`]);
      baseSelection = new Set();
    }
    renderSelection(table);
  });

  document.addEventListener('mouseover', e => {
    if (!isDragging || !activeTable || !anchor) return;
    const cell = e.target.closest('.table-wrap td, .table-wrap th');
    if (!cell || cell.closest('table') !== activeTable) return;

    if (dragMode === 'col' || cell.tagName === 'TH') {
      const cIdx = Array.from(cell.parentElement.children).indexOf(cell);
      selectedCells = new Set([...baseSelection, ...colCells(activeTable, anchor.c, cIdx)]);
    } else {
      const tr = cell.parentElement;
      const rIdx = Array.from(tr.parentElement.children).indexOf(tr);
      const cIdx = Array.from(tr.children).indexOf(cell);
      selectedCells = new Set([...baseSelection, ...rectCells(anchor.r, anchor.c, rIdx, cIdx)]);
    }
    renderSelection(activeTable);
  });

  document.addEventListener('mouseup', () => {
    isDragging = false;
  });

  document.addEventListener('contextmenu', e => {
    if (!activeTable || !selectedCells.size) return;
    if (!e.target.closest('.table-wrap')) return;
    e.preventDefault();
    closeContextMenu();

    const menu = document.createElement('div');
    menu.className = 'excel-context-menu';
    Object.assign(menu.style, {
      position: 'fixed', zIndex: '100000', background: '#141c31',
      border: '1px solid #3a5280', borderRadius: '8px', padding: '5px',
      minWidth: '205px', boxShadow: '0 10px 28px rgba(0, 0, 0, 0.55)',
      display: 'grid', gap: '3px'
    });
    menu.innerHTML = `
      <button type="button" data-mact="copy" style="width:100%;border:0;background:transparent;color:var(--text);text-align:left;padding:8px 10px;border-radius:5px;font-size:12px;display:flex;justify-content:space-between;align-items:center;gap:14px">Copy selected cells <span style="color:var(--muted);font-size:11px">Ctrl+C</span></button>
      <button type="button" data-mact="copy-headers" style="width:100%;border:0;background:transparent;color:var(--text);text-align:left;padding:8px 10px;border-radius:5px;font-size:12px;display:flex;justify-content:space-between;align-items:center;gap:14px">Copy with column headers <span style="color:var(--muted);font-size:11px">TSV</span></button>
      <button type="button" data-mact="clear" style="width:100%;border:0;background:transparent;color:var(--text);text-align:left;padding:8px 10px;border-radius:5px;font-size:12px;display:flex;justify-content:space-between;align-items:center;gap:14px">Clear selection <span style="color:var(--muted);font-size:11px">Esc</span></button>
    `;
    document.body.appendChild(menu);

    const x = Math.min(e.clientX, window.innerWidth - menu.offsetWidth - 10);
    const y = Math.min(e.clientY, window.innerHeight - menu.offsetHeight - 10);
    menu.style.left = Math.max(8, x) + 'px';
    menu.style.top = Math.max(8, y) + 'px';

    menu.querySelector('[data-mact="copy"]').onclick = () => performCopy(false);
    menu.querySelector('[data-mact="copy-headers"]').onclick = () => performCopy(true);
    menu.querySelector('[data-mact="clear"]').onclick = clearSelection;
  });

  document.addEventListener('keydown', e => {
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName)) return;
    if (e.key === 'Escape' && selectedCells.size) {
      clearSelection();
      return;
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c' && activeTable && selectedCells.size) {
      e.preventDefault();
      performCopy(false);
    }
  });
})();