// DOM + canvas drawing. All state derives from the payload and Date.now(), so a
// document recreated by a publish looks identical to one that kept running.
(function () {
  'use strict';
  const FRAME_MS = 1000 / 30; // deliberate 30 fps cap: calm motion, less heat
  const ARROW = { up: '▲', down: '▼', level: '' };

  // A route's arrow is drawn, not typed: the character's font fallback differs between devices (and
  // after system updates), and on the Echo it came out much thinner than the surrounding text.
  const routeEl = (tag, cls, text) => {
    const node = el(tag, cls);
    text.split(' \u2192 ').forEach((part, i) => {
      if (i) {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'rarrow');
        svg.setAttribute('viewBox', '0 0 20 12');
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.setAttribute('d', 'M1 6H18M12.5 1L18 6L12.5 11');
        svg.appendChild(path);
        node.appendChild(svg);
      }
      node.appendChild(document.createTextNode(part));
    });
    return node;
  };

  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text; // textContent only: payload strings are untrusted
    return e;
  };

  function readPayload() {
    try {
      const raw = document.getElementById('payload').textContent.trim();
      if (!raw || raw.startsWith('/*')) return { p: null, err: null }; // unsubstituted slot
      const p = JSON.parse(raw);
      const errs = FL.validate(p);
      return errs.length ? { p: null, err: errs[0] } : { p, err: null };
    } catch (e) {
      return { p: null, err: 'unreadable payload' };
    }
  }

  const { p: P, err: ERR } = readPayload();
  const $ = (id) => document.getElementById(id);

  const ROW_PITCH = 80; // list row height plus gap, in --px units (style.css)
  const FADE_MS = 1000; // a change of content fades in over the old one instead of switching
  const STALE_MS = 3000; // an old position eases to its dim look instead of switching to it
  let t0 = performance.now(); // reset at the first painted frame (see frame())

  function rowEl(r) {
    const li = el('li', (r.pos.stale ? 'old' : ''));
    // The page is rebuilt each second, so the easing is a CSS animation started `over` seconds in.
    if (r.pos.stale) li.style.animation = 'staledim ' + STALE_MS + 'ms ease-in-out ' + -Math.round(r.pos.over * 1000) + 'ms both';
    const lc = el('span', FL.isUnidentified(r.ac) ? 'lchip faint' : 'lchip');
    const lbg = FL.airlineColor(r.ac.al) || '#3a4a5c';
    lc.style.setProperty('--bg', lbg); // the airline colour is the chip's outline (style.css)
    const lic = el('canvas', 'inchip'); // type silhouette inside the chip, plain white (the altitude figure carries the colour)
    lic.width = lic.height = ICON_PX;
    const ltc = tinted(FL.iconFor(r.ac.ty), '#e8eef5', false);
    if (ltc) lic.getContext('2d').drawImage(ltc, 0, 0);
    lc.append(lic, document.createTextNode(r.name));
    const route = FL.routeText(r.ac) || '';
    const main = el('div', 'main');
    const l1 = el('div', 'name'), l2 = el('div', 'sub');
    l1.appendChild(lc);
    if (route) l1.appendChild(routeEl('span', r.ac.d ? 'rt' : 'rt unc', route)); // origin only: muted, see style.css
    l2.textContent = [r.ac.an, r.type].filter(Boolean).join(' · '); // airline and type
    if (!l2.textContent && FL.isUnidentified(r.ac)) l2.appendChild(el('span', 'unc', 'Position only')); // slanted like an open route: less than a full answer
    main.append(l1, l2);
    const side = el('div', 'side');
    const alt = el('div', 'alt', r.alt);
    if (r.ac.alt != null) alt.style.color = FL.altColor(r.ac.alt); // the scope's colour for this aircraft
    alt.appendChild(el('span', 'arrow', ARROW[r.trend]));
    side.append(alt, el('div', 'dist', r.distText));
    li.append(main, side);
    return li;
  }

  // The top band's content for one row: callsign chip (airline colour, type silhouette inside it in
  // the chip's own text colour so it always matches), route, and the facts line.
  function spotEl(row) {
    const s = FL.spotlight(row, P);
    const fac = row.ac;
    const head = el('div', 'head');
    const chip = el('span', FL.isUnidentified(fac) ? 'chip ident faint' : 'chip ident');
    const bg = FL.airlineColor(fac.al) || '#3a4a5c';
    chip.style.background = bg;
    const fg = FL.luminance(bg) > 0.25 ? '#0b0f14' : '#ffffff';
    chip.style.color = fg;
    const ic = el('canvas', 'inchip');
    ic.width = ic.height = ICON_PX;
    const t = tinted(FL.iconFor(fac.ty), fg, false);
    if (t) ic.getContext('2d').drawImage(t, 0, 0);
    chip.appendChild(ic);
    chip.appendChild(document.createTextNode(s.name));
    head.appendChild(chip);
    if (s.route) head.appendChild(routeEl('div', s.unconfirmed ? 'route unc' : 'route', s.route));
    const facts = el('div', 'facts');
    const LEAD = 'Position only';
    if (s.facts.startsWith(LEAD)) { facts.appendChild(el('span', 'unc', LEAD)); facts.appendChild(document.createTextNode(s.facts.slice(LEAD.length))); }
    else facts.textContent = s.facts;
    if (ARROW[s.trend]) facts.appendChild(el('span', 'arrow', ARROW[s.trend])); // altitude is the last fact: same climb/descent arrow as the list
    const body = el('div', 'spotbody');
    body.append(head, facts);
    return body;
  }

  // One aircraft and nothing else to list: the left side would be empty, so the featured aircraft
  // is shown there in large type (the band is then clock only). Same data as the band, same colours.
  function soloEl(row) {
    const s = FL.spotlight(row, P), fac = row.ac;
    const box = el('div', 'solobody');
    const chip = el('span', 'chip ident');
    const bg = FL.airlineColor(fac.al) || '#3a4a5c';
    const fg = FL.luminance(bg) > 0.25 ? '#0b0f14' : '#ffffff';
    chip.style.background = bg; chip.style.color = fg;
    const ic = el('canvas', 'inchip');
    ic.width = ic.height = ICON_PX;
    const t = tinted(FL.iconFor(fac.ty), fg, false);
    if (t) ic.getContext('2d').drawImage(t, 0, 0);
    chip.append(ic, document.createTextNode(s.name));
    box.appendChild(chip);
    if (s.route) box.appendChild(routeEl('div', 'sroute', s.route));
    const alt = el('div', 'salt', row.alt);
    if (fac.alt != null) alt.style.color = FL.altColor(fac.alt);
    alt.appendChild(el('span', 'arrow', ARROW[row.trend]));
    box.appendChild(alt);
    box.appendChild(el('div', 'sdist', row.distText));
    const who = [fac.an, row.type, fac.rg].filter(Boolean).join(' · ');
    if (who) box.appendChild(el('div', 'swho', who));
    return box;
  }

  function updateText(now) {
    const st = P ? FL.status(P, now) : { kind: 'nodata', text: ERR ? 'Display data error' : 'Waiting for data' };
    document.body.classList.toggle('stale', st.kind === 'stale');
    const { list, closest } = P ? FL.rows(P, now) : { list: [], closest: -1 };

    const featId = P ? FL.featuredId(P) : null;
    const rows = $('rows');
    rows.textContent = '';
    const tr = P ? FL.transition(P) : null;
    const elapsed = performance.now() - t0; // time since this document first painted
    const fading = tr && elapsed < FADE_MS;
    // The page is rebuilt each second, so a fade is re-applied with a negative delay: it carries on
    // from where it is rather than restarting. Content that did not change gets no animation.
    const fade = (e, name, fill) => {
      const a = name + ' ' + FADE_MS + 'ms ease-in-out ' + -Math.round(elapsed) + 'ms ' + fill;
      e.style.animation = e.style.animation ? e.style.animation + ', ' + a : a; // alongside a stale dimming
    };
    const shown = list.filter((r) => r.ac.id !== featId); // the featured aircraft has the top band
    shown.forEach((r, i) => {
      const li = rowEl(r);
      if (fading && tr.moves[i].changed) fade(li, 'fadein', 'backwards');
      rows.appendChild(li);
    });
    if (fading) {
      tr.ghosts.forEach((g) => { // what was in a place before fades out over what is there now
        const li = rowEl(FL.makeRow(g.ac, 0, P, now));
        li.classList.add('ghost');
        li.style.bottom = 'calc(' + g.k * ROW_PITCH + ' * var(--px))';
        fade(li, 'fadeout', 'both');
        rows.appendChild(li);
      });
    }

    // Own clock: keep the device's time zone and locale, with an optional hour-cycle override.
    const d = new Date(now);
    $('time').textContent = FL.fmtClock(d, P && P.clock);
    $('date').textContent = d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });

    const solo = list.length === 1 && P && !P.msg;
    document.body.classList.toggle('solo', solo);
    $('solo').textContent = '';
    if (solo) $('solo').appendChild(soloEl(list[0]));

    const spot = $('spot');
    spot.textContent = '';
    if (list.length) {
      const id = FL.featuredId(P);
      const body = spotEl(list.find((r) => r.ac.id === id));
      spot.appendChild(body);
      if (fading && tr.featChanged) { // a different aircraft took the band: the old one fades out over it
        fade(body, 'fadein', 'backwards');
        if (tr.oldFeatured) {
          const old = el('div', 'spotghost');
          old.appendChild(spotEl(FL.makeRow(tr.oldFeatured, 0, P, now)));
          fade(old, 'fadeout', 'both');
          spot.appendChild(old);
        }
      }
    } else if (st.kind !== 'ok' && st.kind !== 'empty') { // an empty sky is told in the calm panel below, not the band
      spot.appendChild(el('div', 'msg', st.text));
    }

    // Empty sky: a calm panel on the left instead of a blank half. The scope stays on the right.
    const calm = !!P && st.kind === 'empty';
    document.body.classList.toggle('empty', calm);
    if (calm) {
      const hr = d.getHours();
      document.querySelector('#calm .ctitle').textContent = hr >= 21 || hr < 5 ? 'Quiet night sky' : 'Quiet sky';
      document.querySelector('#calm .cdetail').textContent = st.text;
    }

    $('src').textContent = P ? (P.src === 'local' ? '● Local feed' : P.src === 'mix' ? '● Local + internet' : '● Internet feed') : '';
    const note = $('note');
    note.textContent = st.kind === 'stale' ? st.text : '';
  }

  // Calm panel: every 40 s a new little scene, derived from the clock so a page rebuild resumes it.
  // The order is shuffled in rounds of five (each scene once per round, never the same twice running)
  // and each scene gets its own height, direction and wiggle from a hash of its number. An airliner
  // leaves a long fading dotted trail; clouds fade in and out in place, never travelling with it.
  // Pure function of time: no state to keep.
  const SCENE_MS = 40000, N_SCENES = 5;
  const hash = (a, b) => { const x = Math.sin(a * 127.1 + b * 311.7 + 7.7) * 43758.5453; return x - Math.floor(x); };
  function sceneAt(idx) {
    const order = (rd) => [0, 1, 2, 3, 4].sort((p, q) => hash(rd, p) - hash(rd, q));
    const rd = Math.floor(idx / N_SCENES), o = order(rd);
    if (rd > 0 && o[0] === order(rd - 1)[N_SCENES - 1]) { const t = o[0]; o[0] = o[1]; o[1] = t; }
    return o[idx % N_SCENES];
  }
  // Paths give a position in the panel as fractions (x, y) plus an offset in --px.
  const PATHS = [
    (u, p) => ({ x: u, y: 0.3 + 0.4 * p.a, dx: 0, dy: 0 }),                                           // straight across
    (u, p) => ({ x: u, y: 0.3 + 0.4 * p.a + 0.2 * Math.sin(u * (4 + 3 * p.b) * Math.PI), dx: 0, dy: 0 }), // weaving
    (u, p) => {                                                                                       // one big loop
      const s = Math.min(1, Math.max(0, (u - (0.3 + 0.2 * p.a)) / 0.22)), th = (s * s * (3 - 2 * s)) * 2 * Math.PI;
      return { x: u, y: 0.88, dx: 30 * Math.sin(th), dy: -30 * (1 - Math.cos(th)) };
    },
    (u, p) => {                                                                                       // cruise, then climb out or dive away
      const s = Math.max(0, (u - 0.45) / 0.55), q = 1.3 * s * s;
      return { x: u, y: p.b > 0.5 ? 0.8 - q : 0.2 + q, dx: 0, dy: 0 };
    },
    null,                                                                                             // clouds only
  ];
  // One path, one fill: separate translucent circles would show their overlaps as seams.
  function puff(g, cx, cy, s, bits) {
    g.beginPath();
    bits.forEach(([dx, dy, rad]) => { g.moveTo(cx + (dx + rad) * s, cy + dy * s); g.arc(cx + dx * s, cy + dy * s, rad * s, 0, 2 * Math.PI); });
    g.fill();
  }
  const DRIFT_H = 110; // the sky panel's height in --px units
  function drift(now) {
    const c = $('drift'), dpr = window.devicePixelRatio || 1, r = c.getBoundingClientRect();
    if (!r.width) return;
    if (c.width !== Math.round(r.width * dpr)) { c.width = Math.round(r.width * dpr); c.height = Math.round(r.height * dpr); }
    const g = c.getContext('2d'), k = r.height / DRIFT_H, W = r.width / k; // draw in --px units
    g.setTransform(dpr * k, 0, 0, dpr * k, 0, 0);
    g.clearRect(0, 0, W, DRIFT_H);
    const idx = Math.floor(now / SCENE_MS), kind = sceneAt(idx), path = PATHS[kind];
    const u = (now % SCENE_MS) / SCENE_MS;
    const p = { a: hash(idx, 1), b: hash(idx, 2) }, flip = hash(idx, 3) < 0.5;
    const clouds = kind === 4 ? 3 + Math.floor(hash(idx, 4) * 2) : Math.floor(hash(idx, 4) * 4); // 0-3, a cloud-only scene 3-4
    for (let i = 0; i < clouds; i++) { // faint, behind the plane, fading in and out where they float
      const ph = (u + i / clouds) % 1, al = Math.pow(Math.sin(Math.PI * ph), 2);
      const s = 0.7 + 0.7 * hash(idx, 5 + i);
      const cx = 30 + ((i + hash(idx, 7 + i)) / clouds) * (W - 130) + (ph - 0.5) * 24; // one per slot across the width, so they spread out
      const cy = 20 + hash(idx, 9 + i) * 50;
      g.fillStyle = 'rgba(107,128,153,' + (0.24 * al).toFixed(3) + ')';
      puff(g, cx, cy, s, [[0, 0, 16], [18, -6, 20], [40, 0, 15], [22, 6, 17]]);
    }
    if (!path) return;
    const pt = (v) => {
      const q = path(v, p), fx = flip ? 1 - q.x : q.x;
      return { x: -30 + fx * (W + 60) + (flip ? -q.dx : q.dx), y: 10 + q.y * (DRIFT_H - 20) + q.dy };
    };
    const here = pt(u), before = pt(Math.max(0, u - 0.002)), after = pt(Math.min(1, u + 0.002));
    for (let i = 1; i <= 70; i++) { // a long dotted trail that fades slowly
      const v = u - i * 0.006;
      if (v < 0) break;
      const q = pt(v), f = 1 - i / 71;
      g.fillStyle = 'rgba(147,166,189,' + (0.55 * f * f).toFixed(3) + ')';
      g.beginPath(); g.arc(q.x, q.y, 0.8 + 1.5 * f, 0, 2 * Math.PI); g.fill();
    }
    const ic = tinted('b737', '#6b8099', false);
    if (!ic) return; // icon image still loading: the next frame tries again
    g.save();
    g.translate(here.x, here.y);
    g.rotate(Math.atan2(after.y - before.y, after.x - before.x) + Math.PI / 2);
    g.globalAlpha = 0.75;
    g.drawImage(ic, -19, -19, 38, 38);
    g.restore();
  }

  // ---- scope -------------------------------------------------------------
  const cv = $('scope');
  const ctx = cv.getContext('2d');
  let size = 0;
  function fit() {
    const dpr = window.devicePixelRatio || 1;
    const r = cv.getBoundingClientRect();
    size = r.width;
    cv.width = Math.round(r.width * dpr);
    cv.height = Math.round(r.height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  const DECLUTTER_MAX_FRAC = 0.035;
  const COL = { ring: '#6b8099', edge: '#a9bacd', text: '#dbe3ec', accent: '#5cc8ff', hot: '#ffcf5c', dim: '#8393a5' };

  // SVG icons are loaded once as images; each (icon, colour) is drawn once into a small canvas:
  // the icon's alpha as a mask, a dark rim for contrast, then the colour. Returns null until loaded.
  const IMG = {}, TINT = {}, ICON_PX = 96;
  function tinted(key, color, stale) {
    if (!ICONS[key]) return null;
    let img = IMG[key];
    if (!img) { img = IMG[key] = new Image(); img.onload = () => { lastText = 0; }; /* redraw the text now */ img.src = 'data:image/svg+xml;base64,' + btoa(ICONS[key]); }
    if (!img.complete || !img.naturalWidth) return null;
    const id = key + '|' + color + '|' + (stale ? 's' : '');
    if (TINT[id]) return TINT[id];
    const mk = () => { const c = document.createElement('canvas'); c.width = c.height = ICON_PX; return c; };
    const mask = mk(), m = mask.getContext('2d'), pad = 6, wh = ICON_PX - 2 * pad;
    m.drawImage(img, pad, pad, wh, wh);
    const out = mk(), o = out.getContext('2d');
    const rim = mk(), r = rim.getContext('2d'); // dark halo: the mask drawn around in a dark colour
    for (let a = 0; a < 8; a++) r.drawImage(mask, Math.cos(a * Math.PI / 4) * 2, Math.sin(a * Math.PI / 4) * 2);
    r.globalCompositeOperation = 'source-in'; r.fillStyle = FL.luminance(color) < 0.06 ? '#cdd8e3' : '#0b0f14'; r.fillRect(0, 0, ICON_PX, ICON_PX);
    o.drawImage(rim, 0, 0);
    const body = mk(), b = body.getContext('2d');
    b.drawImage(mask, 0, 0); b.globalCompositeOperation = 'source-in'; b.fillStyle = color; b.fillRect(0, 0, ICON_PX, ICON_PX);
    if (stale) o.globalAlpha = 0.55;
    o.drawImage(body, 0, 0);
    return (TINT[id] = out);
  }


  // Altitude key: a small gradient bar bottom-left, "0" to the top of the ramp in display units.
  function drawLegend(c, R) {
    const u = P ? P.u : 'av', w = size * 0.2, h = size * 0.022, x = size * 0.02, y = size * 0.93;
    const g = ctx.createLinearGradient(x, 0, x + w, 0);
    for (let k = 0; k <= 10; k++) g.addColorStop(k / 10, FL.altColor((k / 10) * FL.ALT_TOP_M));
    ctx.fillStyle = g; ctx.fillRect(x, y, w, h);
    ctx.fillStyle = COL.text; ctx.textBaseline = 'alphabetic';
    ctx.font = Math.round(size * 0.036) + 'px system-ui, sans-serif';
    // The top of the ramp as a round figure in the display units ("40k ft", "12 km"), not a
    // converted 12,190 m.
    const top = u === 'met' ? '12 km' : '40k ft';
    ctx.textAlign = 'left'; ctx.fillText('0', x, y - 4);
    ctx.textAlign = 'right'; ctx.fillText(top, x + w, y - 4);
    ctx.textBaseline = 'middle';
  }

  function drawScope(now) {
    const c = size / 2, margin = size * 0.07, R = c - margin;
    const r = P ? P.r : 15 * 1852;
    const k = R / r; // pixels per metre
    ctx.clearRect(0, 0, size, size);
    ctx.lineWidth = 2;
    ctx.font = Math.round(size * 0.038) + 'px system-ui, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';

    ctx.strokeStyle = COL.edge; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.arc(c, c, R, 0, 2 * Math.PI); ctx.stroke();
    ctx.lineWidth = 2;
    const rings = FL.ringPlan(r, P ? P.u : 'av');
    ctx.fillStyle = COL.text;
    rings.forEach((g, i) => {
      const gr = g.m * k;
      ctx.strokeStyle = COL.ring;
      ctx.beginPath(); ctx.arc(c, c, gr, 0, 2 * Math.PI); ctx.stroke();
      if (i === rings.length - 1 || rings.length < 3) ctx.fillText(g.label, c + gr * 0.707 + 4, c + gr * 0.707 + 8);
    });
    ctx.strokeStyle = COL.ring;
    ctx.beginPath(); ctx.moveTo(c - R, c); ctx.lineTo(c + R, c); ctx.moveTo(c, c - R); ctx.lineTo(c, c + R); ctx.stroke();
    ctx.fillStyle = COL.text;
    const o = R + margin * 0.5;
    ctx.fillText('N', c, c - o); ctx.fillText('S', c, c + o); ctx.fillText('E', c + o, c); ctx.fillText('W', c - o, c);

    // Sweep: decorative, driven by the wall clock only. It stops when the data is old, so a live
    // looking sweep never sits over frozen aircraft.
    const stopped = document.body.classList.contains('stale');
    const a = FL.sweepAngle(now) - Math.PI / 2;
    if (!stopped && ctx.createConicGradient) {
      const g = ctx.createConicGradient(a - 0.9, c, c);
      g.addColorStop(0, 'rgba(92,200,255,0)');
      g.addColorStop(0.14, 'rgba(92,200,255,0.16)');
      g.addColorStop(0.1401, 'rgba(92,200,255,0)');
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.moveTo(c, c); ctx.arc(c, c, R, a - 0.9, a); ctx.closePath(); ctx.fill();
    }
    if (!stopped) {
      ctx.strokeStyle = 'rgba(92,200,255,0.55)';
      ctx.beginPath(); ctx.moveTo(c, c); ctx.lineTo(c + R * Math.cos(a), c + R * Math.sin(a)); ctx.stroke();
    }

    // Home.
    ctx.strokeStyle = COL.text;
    ctx.beginPath(); ctx.moveTo(c - 6, c); ctx.lineTo(c + 6, c); ctx.moveTo(c, c - 6); ctx.lineTo(c, c + 6); ctx.stroke();

    drawLegend(c, R);
    if (!P) return;
    const px = (x) => c + x * k, py = (y) => c - y * k;

    // Label boxes already taken (x0..x1 at height y, half-height h), so an aircraft's label avoids
    // the home mark and the airport markers as well as the other labels.
    const placed = [{ x0: c - 10, x1: c + 10, y: c, h: 10 }];
    P.ap.forEach((ap) => {
      if (Math.hypot(ap.x, ap.y) > r) return;
      ctx.strokeStyle = COL.dim; ctx.fillStyle = COL.dim;
      ctx.strokeRect(px(ap.x) - 4, py(ap.y) - 4, 8, 8);
      ctx.fillText(ap.c, px(ap.x), py(ap.y) + 16);
      placed.push({ x0: px(ap.x) - 16, x1: px(ap.x) + 16, y: py(ap.y) + 8, h: 14 });
    });

    const { list, closest } = FL.rows(P, now);
    // Declutter: close aircraft are nudged apart, by at most DECLUTTER_MAX_FRAC of the scope, so an
    // icon stays on its true spot to within about a nautical mile and the list's distances agree
    // with the scope. Pairs closer than that overlap; their labels are separated instead (below).
    // Deterministic from the positions, so it needs no state and a recreated document looks the same.
    const shown = FL.spread(
      list.map((row) => ({ x: px(row.pos.x), y: py(row.pos.y) })), size * 0.13, size * DECLUTTER_MAX_FRAC)
      .map((q) => { // keep every icon centre on or inside the ring, so each listed aircraft is drawn
        const d = Math.hypot(q.x - c, q.y - c);
        return d > R ? { x: c + ((q.x - c) * R) / d, y: c + ((q.y - c) * R) / d } : q;
      });
    // Other aircraft's icons are obstacles too (an aircraft's own icon is skipped via `own`).
    shown.forEach((q, n) => placed.push({ x0: q.x - size * 0.055, x1: q.x + size * 0.055, y: q.y, h: size * 0.055, own: n }));
    const featId = FL.featuredId(P);
    const byAlt = list.map((row, i) => ({ row, i })).sort((u, v) => (v.row.ac.alt ?? -1) - (u.row.ac.alt ?? -1));
    byAlt.forEach(({ row, i }) => {
      const stale = row.pos.stale || document.body.classList.contains('stale');
      // Icon and trail share the altitude colour. Stale keeps it but dimmed (as the list rows do), so
      // it stays distinguishable from an unknown altitude, which is the bright neutral grey.
      const icol = FL.altColor(row.ac.alt);
      const col = icol;
      const pts = FL.trail(row.ac, P, now);
      ctx.lineWidth = 2;
      for (let j = 1; j < pts.length; j++) { // fading trail, older segments fainter
        ctx.strokeStyle = col;
        ctx.globalAlpha = (0.15 + 0.5 * (j / pts.length)) * (stale ? 0.55 : 1);
        ctx.beginPath(); ctx.moveTo(px(pts[j - 1][0]), py(pts[j - 1][1])); ctx.lineTo(px(pts[j][0]), py(pts[j][1])); ctx.stroke();
      }
      ctx.globalAlpha = 1;
      const x = shown[i].x, y = shown[i].y, is = size * 0.11; // icon box in px
      if (row.ac.id === featId) { // the aircraft in the top band: ringed so the eye can find it
        ctx.strokeStyle = COL.hot; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.arc(x, y, is * 0.62, 0, 2 * Math.PI); ctx.stroke();
      }
      ctx.save();
      ctx.translate(x, y);
      const tc = tinted(FL.iconFor(row.ac.ty), icol, stale);
      if (tc) {
        ctx.rotate(((row.ac.trk || 0) * Math.PI) / 180);
        ctx.drawImage(tc, -is / 2, -is / 2, is, is);
      } else { // icon image still decoding: a dot, so a blip is never missing
        ctx.beginPath(); ctx.arc(0, 0, is * 0.25, 0, 2 * Math.PI); ctx.fillStyle = icol; ctx.fill();
      }
      ctx.restore();
      // Callsign (else registration, else id) beside the icon, flipped to the left near the right edge.
      ctx.save();
      ctx.font = '600 ' + Math.round(size * 0.04) + 'px system-ui, sans-serif';
      const y0 = y - is * 0.1;
      const lw = ctx.measureText(row.name).width, lh = size * 0.045;
      const pad = 6; // breathing room either side, so touching labels count as colliding
      const lxFor = (lf) => x + (lf ? -1 : 1) * is * 0.58;
      const hits = (lf, yy) => {
        const lx = lxFor(lf);
        return placed.some((b) => b.own !== i && !(lf ? lx + pad < b.x0 || lx - lw - pad > b.x1 : lx + lw + pad < b.x0 || lx - pad > b.x1) && Math.abs(yy - b.y) < lh / 2 + (b.h || lh / 2));
      };
      // The usual side first (right, flipped left near the right edge), then the other side, each at
      // the natural height and then nudged a line down or up: the nearest clear spot wins. If none
      // is clear, the natural one stays.
      const usual = x > size * 0.6;
      let left = usual, ly = y0;
      search: for (const step of [0, 1, -1, 2, -2]) {
        for (const lf of [usual, !usual]) {
          if (!hits(lf, y0 + step * lh)) { left = lf; ly = y0 + step * lh; break search; }
        }
      }
      ctx.textAlign = left ? 'right' : 'left';
      const lx = lxFor(left);
      placed.push({ x0: left ? lx - lw : lx, x1: left ? lx : lx + lw, y: ly });
      ctx.lineWidth = 4; ctx.lineJoin = 'round'; ctx.strokeStyle = '#0b0f14'; ctx.fillStyle = COL.text;
      if (FL.isUnidentified(row.ac)) ctx.globalAlpha = 0.6; // a ~ target: its label steps back like its list row
      ctx.strokeText(row.name, lx, ly); ctx.fillText(row.name, lx, ly);
      ctx.restore();
    });
  }

  // ---- loop --------------------------------------------------------------
  let lastText = 0, lastFrame = 0, painted = false;
  function frame(t) {
    requestAnimationFrame(frame);
    if (t - lastFrame < FRAME_MS - 1) return;
    lastFrame = t;
    const now = Date.now();
    if (!painted) { painted = true; t0 = performance.now(); } // fades start when the page is first visible
    if (now - lastText >= 1000) { lastText = now; updateText(now); }
    if (document.body.classList.contains('empty')) drift(now);
    drawScope(now);
  }
  window.addEventListener('resize', fit);
  fit();
  requestAnimationFrame(frame);
})();
