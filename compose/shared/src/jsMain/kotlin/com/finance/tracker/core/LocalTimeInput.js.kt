package com.finance.tracker.core

actual fun instantForLocalDateTime(value: String, timeZoneId: String): String? = js("""(() => {
  try {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null;
    const base = Date.parse(value + ':00Z');
    if (!Number.isFinite(base) || new Date(base).toISOString().slice(0, 16) !== value) return null;
    const formatter = new Intl.DateTimeFormat('en-US', { timeZone: timeZoneId, year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit', hourCycle:'h23' });
    const parts = timestamp => Object.fromEntries(formatter.formatToParts(new Date(timestamp)).map(p => [p.type, p.value]));
    const wall = timestamp => { const p=parts(timestamp); return p.year+'-'+p.month+'-'+p.day+'T'+p.hour+':'+p.minute; };
    const offsets = new Set();
    for(let step=-8; step<=8; step++) {
      const timestamp=base+step*21600000, p=parts(timestamp);
      const utc = Date.parse(p.year+'-'+p.month+'-'+p.day+'T'+p.hour+':'+p.minute+':'+p.second+'Z');
      offsets.add(utc-timestamp);
    }
    const candidates = [...offsets].map(offset=>base-offset).filter(timestamp=>wall(timestamp)===value);
    return candidates.length===1 ? new Date(candidates[0]).toISOString().replace('.000Z','Z') : null;
  } catch (_) { return null; }
})()""")
