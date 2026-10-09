// Pure renderer logic: no DOM, no clock reads (callers pass nowMs). Tested with
// `node --test`; loaded in the page as window.FL. See docs/payload.md.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.FL = factory();
})(this, function () {
  'use strict';

  const VERSION = 2;
  const DR_CAP_S = 60; // dead-reckoning stops here; the blip then looks stale (local feed: 21% of fixes are >30 s old, 5% >60 s)
  const STALE_FETCH_S = 120; // Java's `st` flag covers failures; this covers a dead publisher
  const SWEEP_MS = 15000; // decorative; 8 s felt too fast
  const LEVEL_MS = 1.5; // |vr| below this (~300 fpm) reads as level: baro_rate is noisy at cruise

  const UNITS = {
    av: { dist: 'nm', df: 1852, alt: 'ft', af: 0.3048, spd: 'kt', sf: 1852 / 3600 },
    met: { dist: 'km', df: 1000, alt: 'm', af: 1, spd: 'km/h', sf: 1 / 3.6 },
    imp: { dist: 'mi', df: 1609.344, alt: 'ft', af: 0.3048, spd: 'mph', sf: 0.44704 },
  };

  const isNum = (n) => typeof n === 'number' && Number.isFinite(n);
  const isStr = (s) => typeof s === 'string';
  const optNum = (n) => n === null || n === undefined || isNum(n);
  const optStr = (s) => s === null || s === undefined || isStr(s);

  function validate(p) {
    const e = [];
    if (!p || typeof p !== 'object') return ['payload is not an object'];
    if (p.v !== VERSION) e.push('v must be ' + VERSION);
    if (!isNum(p.gen)) e.push('gen must be a number');
    if (!(p.fa === null || (isNum(p.fa) && p.fa >= 0))) e.push('fa must be null or >= 0');
    if (typeof p.st !== 'boolean') e.push('st must be boolean');
    if (p.src !== 'local' && p.src !== 'net' && p.src !== 'mix') e.push('src must be local|net|mix');
    if (!(isNum(p.r) && p.r > 0)) e.push('r must be > 0');
    if (!UNITS[p.u]) e.push('u must be av|met|imp');
    if (!(p.clock === undefined || p.clock === '12' || p.clock === '24')) e.push('clock must be 12|24');
    if (!optStr(p.ft)) e.push('ft must be a string');
    if (!optStr(p.msg)) e.push('msg must be a string');
    if (!(p.idle === undefined || typeof p.idle === 'boolean')) e.push('idle must be boolean');
    if (!Array.isArray(p.ac) || p.ac.length > 5) e.push('ac must be an array of at most 5');
    else p.ac.forEach((a, i) => aircraftErrors(a, 'ac[' + i + ']', e));
    if (!(p.pn === undefined || (Number.isInteger(p.pn) && p.pn >= 0))) e.push('pn must be an integer >= 0');
    if (p.gone !== undefined) {
      if (!Array.isArray(p.gone) || p.gone.length > 5) e.push('gone must be an array of at most 5');
      else p.gone.forEach((a, i) => aircraftErrors(a, 'gone[' + i + ']', e));
    }
    if (!Array.isArray(p.ap) || p.ap.length > 8) e.push('ap must be an array of at most 8');
    else
      p.ap.forEach((a, i) => {
        if (!(isStr(a && a.c) && isNum(a.x) && isNum(a.y))) e.push('ap[' + i + '] needs c, x, y');
      });
    return e;
  }

  function aircraftErrors(a, at, e) {
    if (!a || typeof a !== 'object') return e.push(at + ' is not an object');
    if (!isStr(a.id) || !a.id) e.push(at + '.id required');
    ['x', 'y', 'a'].forEach((k) => isNum(a[k]) || e.push(at + '.' + k + ' must be a number'));
    if (!(a.pv === undefined || (Number.isInteger(a.pv) && a.pv >= 0))) e.push(at + '.pv must be an integer >= 0');
    ['alt', 'gs', 'trk', 'vr'].forEach((k) => optNum(a[k]) || e.push(at + '.' + k + ' bad type'));
    ['cs', 'rg', 'ty', 'al', 'an', 'o', 'd'].forEach((k) => optStr(a[k]) || e.push(at + '.' + k + ' bad type'));
    if (a.tr !== undefined) {
      const ok = Array.isArray(a.tr) && a.tr.every((t) => Array.isArray(t) && t.length === 3 && t.every(isNum));
      if (!ok) e.push(at + '.tr must be [[age,x,y],...]');
    }
  }

  // Keep the device locale/time zone; override only its hour cycle when requested.
  function fmtClock(date, clock, locale) {
    const options = { hour: 'numeric', minute: '2-digit' };
    if (clock === '12') options.hour12 = true;
    if (clock === '24') {
      options.hour = '2-digit';
      options.hourCycle = 'h23'; // midnight is 00, never 24
    }
    return date.toLocaleTimeString(locale || [], options);
  }

  // Seconds since the observation, at nowMs.
  const ageNow = (ac, p, nowMs) => ac.a + (nowMs - p.gen) / 1000;

  // Dead-reckoned position in metres E/N of home. `stale` once the cap is reached (`over`: how long ago).
  function position(ac, p, nowMs) {
    const age = Math.max(0, ageNow(ac, p, nowMs));
    const dt = isNum(ac.gs) && isNum(ac.trk) ? Math.min(age, DR_CAP_S) : 0;
    const rad = ((ac.trk || 0) * Math.PI) / 180;
    return {
      x: ac.x + (ac.gs || 0) * dt * Math.sin(rad),
      y: ac.y + (ac.gs || 0) * dt * Math.cos(rad),
      stale: age > DR_CAP_S,
      over: Math.max(0, age - DR_CAP_S), // seconds past the cap, so the dimming can ease in
    };
  }

  // Trail as [x, y] points oldest first, ending at the live position.
  function trail(ac, p, nowMs) {
    const pts = (ac.tr || []).map((t) => [t[1], t[2]]);
    const now = position(ac, p, nowMs);
    pts.push([now.x, now.y]);
    return pts;
  }

  const polar = (x, y) => ({ dist: Math.hypot(x, y), brg: ((Math.atan2(x, y) * 180) / Math.PI + 360) % 360 });
  const CARD = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  const cardinal = (brg) => CARD[Math.round(brg / 45) % 8];

  function trend(vr) {
    if (!isNum(vr) || Math.abs(vr) < LEVEL_MS) return 'level';
    return vr > 0 ? 'up' : 'down';
  }

  const group = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');

  function fmtDist(m, u) {
    const v = m / UNITS[u].df;
    return (v < 10 ? v.toFixed(1) : String(Math.round(v))) + ' ' + UNITS[u].dist;
  }
  function fmtAlt(m, u) {
    if (!isNum(m)) return null;
    const v = m / UNITS[u].af;
    const step = UNITS[u].alt === 'ft' ? 100 : 10;
    return group(Math.round(v / step) * step) + ' ' + UNITS[u].alt;
  }
  const fmtSpeed = (ms, u) => (isNum(ms) ? Math.round(ms / UNITS[u].sf) + ' ' + UNITS[u].spd : null);

  // Round ring spacing (in display units) so there are 2-3 labelled rings.
  function ringPlan(r, u) {
    const rd = r / UNITS[u].df;
    const step = [1, 2, 5, 10, 25, 50, 100].find((s) => rd / s <= 3.5) || 100;
    const rings = [];
    for (let k = 1; k * step <= rd + 1e-9; k++) rings.push({ m: k * step * UNITS[u].df, label: String(k * step) });
    if (rings.length) rings[rings.length - 1].label += ' ' + UNITS[u].dist;
    return rings;
  }

  const sweepAngle = (nowMs) => ((nowMs % SWEEP_MS) / SWEEP_MS) * 2 * Math.PI;

  // The featured aircraft's id: Java's choice (`ft`, docs/payload.md "How the list and the featured
  // aircraft are chosen"), else the first listed so an older payload still shows something.
  function featuredId(p) {
    if (!p.ac.length) return null;
    return p.ft && p.ac.some((a) => a.id === p.ft) ? p.ft : p.ac[0].id;
  }

  // Pushes points (entries may be null) apart to at least `gap` pixels, moving none more than `max`
  // from where it started. Pure and deterministic: same input, same output.
  function spread(pts, gap, max) {
    const out = pts.map((p) => (p ? { x: p.x, y: p.y } : null));
    for (let it = 0; it < 24; it++) {
      for (let i = 0; i < out.length; i++) {
        for (let j = i + 1; j < out.length; j++) {
          const a = out[i], b = out[j];
          if (!a || !b) continue;
          let dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
          if (d >= gap) continue;
          if (d < 1e-6) { const t = (i * 2.399 + j) % (2 * Math.PI); dx = Math.cos(t); dy = Math.sin(t); d = 1; }
          const push = (gap - Math.hypot(b.x - a.x, b.y - a.y)) / 2 + 0.01;
          a.x -= (dx / d) * push; a.y -= (dy / d) * push;
          b.x += (dx / d) * push; b.y += (dy / d) * push;
        }
      }
      out.forEach((q, k) => { // keep each within `max` of its true spot
        if (!q) return;
        const dx = q.x - pts[k].x, dy = q.y - pts[k].y, d = Math.hypot(dx, dy);
        if (d > max) { q.x = pts[k].x + (dx / d) * max; q.y = pts[k].y + (dy / d) * max; }
      });
    }
    return out.map((q, k) => q || pts[k]);
  }

  // 'nodata' | 'stale' | 'empty' | 'ok', with the calm line to show for it.
  function status(p, nowMs) {
    if (!p) return { kind: 'nodata', text: 'Waiting for data' };
    if (p.msg) return { kind: 'msg', text: p.msg }; // Java has something to say instead of aircraft
    // Java paused fetching (screensaver hidden): show the quiet sky whatever the clock says, because the
    // document is replaced within seconds of the screensaver coming back.
    if (p.idle) return { kind: 'empty', text: 'Looking for aircraft\u2026' };
    const fetchAge = p.fa === null ? Infinity : p.fa + (nowMs - p.gen) / 1000;
    if (p.st || fetchAge > STALE_FETCH_S) {
      const min = Math.max(1, Math.round(fetchAge / 60));
      return { kind: 'stale', text: isFinite(fetchAge) ? 'No new data · ' + min + ' min' : 'No data yet' };
    }
    if (!p.ac.length) return { kind: 'empty', text: 'No aircraft within ' + fmtDist(p.r, p.u).replace(/\.0 /, ' ') };
    return { kind: 'ok', text: '' };
  }

  // Live per-aircraft view model; index of the closest one is `closest`.
  function makeRow(ac, i, p, nowMs) {
    const pos = position(ac, p, nowMs);
    const pl = polar(pos.x, pos.y);
    return {
      n: i + 1,
      ac,
      pos,
      dist: pl.dist,
      brg: pl.brg,
      inRange: pl.dist <= p.r,
      name: ac.cs || ac.rg || ac.id.toUpperCase(),
      type: typeLabel(ac.ty, 24),
      alt: fmtAlt(ac.alt, p.u) || '',
      trend: trend(ac.vr),
      distText: fmtDist(pl.dist, p.u) + ' ' + cardinal(pl.brg),
    };
  }

  function rows(p, nowMs) {
    const list = p.ac.map((ac, i) => makeRow(ac, i, p, nowMs));
    let closest = -1;
    list.forEach((r, i) => {
      if (r.inRange && (closest < 0 || r.dist < list[closest].dist)) closest = i;
    });
    return { list, closest };
  }


  // How the display changed since the previous publish, for fading instead of switching. The page
  // keeps no state, so Java says where each aircraft was (`pn`, `pv`, `gone`; docs/payload.md).
  // Slots: 0 is the featured band, 1.. the list rows (without the featured aircraft). The list is
  // bottom-aligned, so a row's place is counted in rows from the bottom (`k`). Returns null when
  // nothing changed. `moves[i]` is for the i-th list row: `changed` when it is new or in a different
  // place than before. `ghosts` are old contents to fade out where they were: aircraft that left, and
  // rows that moved (at their old place). `oldFeatured` is the aircraft that had the band, if it is
  // still known (it is in the list or in `gone`).
  function transition(p) {
    if (!Number.isInteger(p.pn)) return null;
    const ft = featuredId(p);
    const list = p.ac.filter((a) => a.id !== ft);
    const n = list.length;
    const ghosts = (p.gone || []).filter((g) => g.pv >= 1).map((g) => ({ ac: g, k: p.pn - g.pv }));
    const moves = list.map((a, i) => {
      if (!Number.isInteger(a.pv)) return { id: a.id, changed: true };
      if (a.pv === 0) return { id: a.id, changed: true }; // was the band, now a list row
      const was = p.pn - a.pv;
      const changed = was !== n - 1 - i;
      if (changed) ghosts.push({ ac: a, k: was });
      return { id: a.id, changed };
    });
    const feat = p.ac.find((a) => a.id === ft);
    const featChanged = !!feat && feat.pv !== 0; // a different aircraft is in the band now
    const oldFeatured = featChanged ? p.ac.concat(p.gone || []).find((a) => a.pv === 0) || null : null;
    const any = featChanged || ghosts.length > 0 || moves.some((m) => m.changed);
    return any ? { moves, ghosts, featChanged, oldFeatured } : null;
  }

  // ICAO type designator -> readable name for common types. Our own table of facts; unknown codes
  // simply keep showing the code.
  const TYPE_NAME = {
    A318: 'Airbus A318', A319: 'Airbus A319', A320: 'Airbus A320', A321: 'Airbus A321', A19N: 'Airbus A319neo',
    A20N: 'Airbus A320neo', A21N: 'Airbus A321neo', A220: 'Airbus A220', BCS1: 'Airbus A220-100', BCS3: 'Airbus A220-300',
    A306: 'Airbus A300-600', A310: 'Airbus A310', A332: 'Airbus A330-200', A333: 'Airbus A330-300', A338: 'Airbus A330-800',
    A339: 'Airbus A330-900', A342: 'Airbus A340-200', A343: 'Airbus A340-300', A345: 'Airbus A340-500', A346: 'Airbus A340-600',
    A359: 'Airbus A350-900', A35K: 'Airbus A350-1000', A388: 'Airbus A380-800',
    B712: 'Boeing 717', B732: 'Boeing 737-200', B733: 'Boeing 737-300', B734: 'Boeing 737-400', B735: 'Boeing 737-500',
    B736: 'Boeing 737-600', B737: 'Boeing 737-700', B738: 'Boeing 737-800', B739: 'Boeing 737-900', B37M: 'Boeing 737 MAX 7',
    B38M: 'Boeing 737 MAX 8', B39M: 'Boeing 737 MAX 9', B3XM: 'Boeing 737 MAX 10', B744: 'Boeing 747-400', B748: 'Boeing 747-8',
    B752: 'Boeing 757-200', B753: 'Boeing 757-300', B762: 'Boeing 767-200', B763: 'Boeing 767-300', B764: 'Boeing 767-400',
    B772: 'Boeing 777-200', B77L: 'Boeing 777-200LR/F', B773: 'Boeing 777-300', B77W: 'Boeing 777-300ER', B778: 'Boeing 777-8',
    B779: 'Boeing 777-9', B788: 'Boeing 787-8', B789: 'Boeing 787-9', B78X: 'Boeing 787-10', MD11: 'McDonnell Douglas MD-11',
    MD82: 'McDonnell Douglas MD-82', MD83: 'McDonnell Douglas MD-83', MD88: 'McDonnell Douglas MD-88', MD90: 'McDonnell Douglas MD-90',
    DC10: 'McDonnell Douglas DC-10', E135: 'Embraer ERJ-135', E145: 'Embraer ERJ-145', E170: 'Embraer E170', E75L: 'Embraer E175',
    E75S: 'Embraer E175', E175: 'Embraer E175', E190: 'Embraer E190', E195: 'Embraer E195', E290: 'Embraer E190-E2',
    E295: 'Embraer E195-E2', E50P: 'Embraer Phenom 100', E55P: 'Embraer Phenom 300', CRJ1: 'Bombardier CRJ-100', CRJ2: 'Bombardier CRJ-200',
    CRJ7: 'Bombardier CRJ-700', CRJ9: 'Bombardier CRJ-900', CRJX: 'Bombardier CRJ-1000', DH8A: 'Dash 8-100', DH8B: 'Dash 8-200',
    DH8C: 'Dash 8-300', DH8D: 'Dash 8 Q400', AT43: 'ATR 42-300', AT45: 'ATR 42-500', AT72: 'ATR 72', AT75: 'ATR 72-500',
    AT76: 'ATR 72-600', SF34: 'Saab 340', JS41: 'Jetstream 41', B190: 'Beechcraft 1900', B350: 'Beechcraft King Air 350',
    BE20: 'Beechcraft King Air 200', BE9L: 'Beechcraft King Air 90', BE58: 'Beechcraft Baron 58', BE36: 'Beechcraft Bonanza A36',
    BE35: 'Beechcraft Bonanza', BE33: 'Beechcraft Debonair', C130: 'Lockheed C-130 Hercules', C30J: 'Lockheed C-130J', C17: 'Boeing C-17 Globemaster',
    C150: 'Cessna 150', C152: 'Cessna 152', C172: 'Cessna 172 Skyhawk', C177: 'Cessna 177 Cardinal', C182: 'Cessna 182 Skylane',
    C206: 'Cessna 206', C208: 'Cessna 208 Caravan', C210: 'Cessna 210 Centurion', C310: 'Cessna 310', C340: 'Cessna 340',
    C421: 'Cessna 421', P28A: 'Piper Cherokee', P28R: 'Piper Arrow', PA24: 'Piper Comanche', PA28: 'Piper Cherokee',
    PA32: 'Piper Cherokee Six', PA34: 'Piper Seneca', PA44: 'Piper Seminole', PA46: 'Piper Malibu', PC12: 'Pilatus PC-12',
    SR20: 'Cirrus SR20', SR22: 'Cirrus SR22', S22T: 'Cirrus SR22T', M20P: 'Mooney M20', M20T: 'Mooney M20 Turbo',
    TB20: 'Socata TB 20', DA40: 'Diamond DA40', DA42: 'Diamond DA42', DA62: 'Diamond DA62', C25A: 'Cessna CitationJet 2',
    C25B: 'Cessna CitationJet 3', C510: 'Cessna Citation Mustang', C525: 'Cessna CitationJet', C550: 'Cessna Citation II',
    C560: 'Cessna Citation V', C56X: 'Cessna Citation Excel', C680: 'Cessna Citation Sovereign', C68A: 'Cessna Citation Latitude',
    C700: 'Cessna Citation Longitude', C750: 'Cessna Citation X', GLF2: 'Gulfstream II', GLF3: 'Gulfstream III',
    GLF4: 'Gulfstream IV', GLF5: 'Gulfstream V', GLF6: 'Gulfstream G650', GLEX: 'Bombardier Global Express', G280: 'Gulfstream G280',
    G150: 'Gulfstream G150', GALX: 'Gulfstream G200', CL30: 'Bombardier Challenger 300', CL35: 'Bombardier Challenger 350',
    CL60: 'Bombardier Challenger 600', LJ35: 'Learjet 35', LJ40: 'Learjet 40', LJ45: 'Learjet 45', LJ55: 'Learjet 55',
    LJ60: 'Learjet 60', LJ75: 'Learjet 75', FA7X: 'Dassault Falcon 7X', FA8X: 'Dassault Falcon 8X', F900: 'Dassault Falcon 900',
    F2TH: 'Dassault Falcon 2000', F2TS: 'Dassault Falcon 2000S', FA5X: 'Dassault Falcon 5X', FA50: 'Dassault Falcon 50', FA20: 'Dassault Falcon 20', H25B: 'Hawker 800', HDJT: 'Honda HondaJet',
    R22: 'Robinson R22', R44: 'Robinson R44', R66: 'Robinson R66', EC35: 'Airbus H135', EC45: 'Airbus H145', B06: 'Bell 206 JetRanger',
    B407: 'Bell 407', B412: 'Bell 412', B429: 'Bell 429', A109: 'Agusta A109', S76: 'Sikorsky S-76', H60: 'Sikorsky UH-60 Black Hawk',
    K35T: 'Boeing KC-135 Stratotanker', K35R: 'Boeing KC-135 Stratotanker', KC10: 'McDonnell Douglas KC-10 Extender', K46: 'Boeing KC-46 Pegasus',
    B52: 'Boeing B-52 Stratofortress', B1: 'Rockwell B-1 Lancer', P8: 'Boeing P-8 Poseidon', E3TF: 'Boeing E-3 Sentry', E6: 'Boeing E-6 Mercury',
    C5M: 'Lockheed C-5M Galaxy', C5: 'Lockheed C-5 Galaxy', V22: 'Bell Boeing V-22 Osprey', T38: 'Northrop T-38 Talon', F16: 'F-16 Fighting Falcon',
    F35: 'F-35 Lightning II', F18H: 'F/A-18 Hornet', A10: 'Fairchild A-10 Thunderbolt II',
    AS50: 'Airbus H125', AS55: 'Eurocopter AS355', EXPL: 'MD Explorer',
  };
  const typeName = (ty) => (ty && TYPE_NAME[String(ty).toUpperCase()]) || null;
  // Full name when it fits, else the code. `max` is in characters.
  const typeLabel = (ty, max) => { const n = typeName(ty); return n && n.length <= max ? n : ty || ''; };

  // "ATL → MCO" when both ends are known, "ATL → " (an open arrow) when only the origin is (the
  // destination is withheld for airlines whose destinations run stale), else null. Never a blank.
  const routeText = (ac) => (ac.o && ac.d ? ac.o + ' \u2192 ' + ac.d : ac.o ? ac.o + ' \u2192 ' : null);

  // A "~" id is an mlat/TIS-B target with no real ICAO address: nothing can be looked up for it.
  const isUnidentified = (ac) => ac.id.charAt(0) === '~';

  // Spotlight card. Identity first (callsign), then the route when it is known; the rest goes in
  // the facts line. A missing field is skipped, never shown as a blank.
  function spotlight(row, p) {
    const ac = row.ac;
    const route = routeText(ac);
    const rest = [ac.rg, row.distText, row.alt].filter(Boolean); // distance and bearing, as in the list
    // Full type name where the line has room (about 68 characters), else the ICAO code.
    const room = 68 - [ac.an, ...rest].filter(Boolean).join(' · ').length - 3 - (row.trend === 'level' ? 0 : 2); // the arrow follows the altitude
    const facts = [ac.an || (isUnidentified(ac) ? 'Position only' : null), typeLabel(ac.ty, room), ...rest].filter(Boolean).join(' · ');
    return { n: row.n, name: row.name, route, unconfirmed: !!route && !ac.d, facts, trend: row.trend, chip: null, hue: chipHue(ac.al) };
  }

  // Rough brand hues for the common carriers; anything else gets a stable hash hue.
  const BRAND_HUE = {
    DAL: 352, AAL: 205, UAL: 225, SWA: 45, JBU: 200, ASA: 165, NKS: 58, FFT: 140,
    FDX: 275, UPS: 30, AAY: 25, HAL: 320, SKW: 190, RPA: 215, ACA: 0, WJA: 170,
  };

  function chipHue(code) {
    if (code && BRAND_HUE[code] !== undefined) return BRAND_HUE[code];
    let h = 0;
    for (const c of code || '') h = (h * 31 + c.charCodeAt(0)) % 360;
    return h;
  }

  // Icon for an ICAO type code: exact families first, then helicopters by prefix, else a generic jet.
  const ICON_BY_TYPE = {};
  const family = (key, codes) => codes.split(' ').forEach((c) => { ICON_BY_TYPE[c] = key; });
  family('a320', 'A318 A319 A320 A321 A19N A20N A21N BCS1 BCS3 A220');
  family('a330', 'A306 A310 A332 A333 A338 A339 A359 A35K A350');
  family('a340', 'A342 A343 A345 A346');
  family('a380', 'A388 A380');
  family('a4', 'B752 B753 B757');
  family('b737', 'B731 B732 B733 B734 B735 B736 B737 B738 B739 B37M B38M B39M B3XM');
  family('b747', 'B741 B742 B743 B744 B748 B74D B74R B74S');
  family('b767', 'B762 B763 B764 B767');
  family('b777', 'B772 B773 B77L B77W B778 B779 B777');
  family('b787', 'B788 B789 B78X B787');
  family('md11', 'MD11 DC10 DC87 DC85');
  family('f100', 'MD80 MD81 MD82 MD83 MD87 MD88 MD90 B712 B717 F100 F70 F28 DC93 DC95');
  family('crjx', 'CRJ1 CRJ2 CRJ7 CRJ9 CRJX');
  family('erj', 'E135 E145 E140 E45X E35L E50P E55P');
  family('e195', 'E170 E175 E190 E195 E75L E75S E290 E295 E390 E19L');
  family('dh8a', 'DH8A DH8B DH8C DH8D AT43 AT44 AT45 AT46 AT72 AT73 AT75 AT76 SF34 JS31 JS32 JS41 SB20 F50 F27');
  family('beechcraft', 'B190 B350 BE20 BE30 BE9L BE9T BE99 BE10 BE58 BE55 BE60 BE76 PA34 PA44 PA31 PA27 DA42 DA62 C310 C340 C402 C414 C421 AC90 AC95 MU2');
  family('c130', 'C130 C30J L100 A400 C17');
  family('b767', 'K35T K35R KC10 K46 E3TF E6');
  family('c130', 'C5M C5');
  family('b737', 'P8');
  family('cessna', 'C150 C152 C162 C170 C172 C175 C177 C180 C182 C185 C188 C195 C206 C207 C208 C210 C72R C82R P28A P28B P28R P28T P32R PA18 PA22 PA24 PA28 PA32 PA46 PC12 SR20 SR22 S22T M20P M20T M20J BE33 BE35 BE36 TB20 RV6 RV7 RV8 RV9 RV10 AA5 DA20 DA40 G115 DR40 COL4 L4 PA11 J3 BL8 C77R');
  family('glf5', 'GLF2 GLF3 GLF4 GLF5 GLF6 GLEX G150 G280 G200 GALX CL30 CL35 CL60 CHAL GL5T GL7T GA7C GA8C');
  family('learjet', 'LJ23 LJ24 LJ25 LJ28 LJ31 LJ35 LJ36 LJ40 LJ45 LJ55 LJ60 LJ70 LJ75 C500 C501 C510 C525 C25A C25B C25C C25M C550 C551 C560 C56X C680 C68A C700 C750 H25A H25B H25C HDJT PRM1 E55P BE40 FA10 FA20 FA50 ASTR WW24 SJ30');
  family('fa7x', 'FA7X FA8X FA6X F900 F2TH F2TS FA5X');
  family('heavyfreighter', 'B74F B74X A124 A225 AN12 IL76 B77F B748F');
  const HELI = /^(R22|R44|R66|EC\d\d|AS\d\d|A109|A119|A139|A169|B06|B206|B212|B222|B230|B407|B412|B429|B47[GJ]|H60|S61|S64|S70|S76|S92|MD52|MD60|MD90H|EXPL|H500|H47|NH90|UH1|BK17|KA2\d|MI\d|H125|H130|H135|H145|H160|H175|AW\d\d|CH\d\d|HU\d\d|MH\d\d|OH\d\d|TH\d\d|H269|H369|G2CA|ROT)/;
  function iconFor(ty) {
    if (!ty) return 'a0';
    const t = String(ty).toUpperCase();
    return ICON_BY_TYPE[t] || (HELI.test(t) ? 'a7' : 'a0');
  }
  // Altitude ramp for the scope: cool when low, warm then violet when high, as other trackers do.
  // Linear in RGB between stops; unknown altitude is a neutral grey-blue.
  const ALT_TOP_M = 12192; // 40,000 ft: the top of the ramp
  // Most traffic near a home is low, so the low end gets the most colour change.
  const ALT_STOPS = [
    [0, [79, 209, 197]], [914, [123, 211, 106]], [1829, [229, 216, 74]], // 0, 3k, 6k ft
    [3048, [255, 154, 60]], [6096, [255, 92, 122]], [ALT_TOP_M, [194, 107, 255]], // 10k, 20k, 40k
  ];
  function altColor(altM) {
    if (!isNum(altM)) return '#8fa3b8';
    const a = Math.max(0, Math.min(ALT_TOP_M, altM));
    let k = 1;
    while (a > ALT_STOPS[k][0]) k++;
    const [a0, c0] = ALT_STOPS[k - 1], [a1, c1] = ALT_STOPS[k], t = (a - a0) / (a1 - a0);
    return '#' + c0.map((v, i) => Math.round(v + (c1[i] - v) * t).toString(16).padStart(2, '0')).join('');
  }

  // Primary brand colours for common carriers (approximate: brand guides
  // vary by year). Anything else gets a stable colour from its code.
  const BRAND_COLOR = {
    DAL: '#C01933', AAL: '#0078D2', UAL: '#0033A0', SWA: '#304CB2', JBU: '#003876', ASA: '#01426A',
    NKS: '#FFEC00', FFT: '#248B68', HAL: '#5B2C83', SKW: '#00539B', ENY: '#0078D2', PDT: '#0078D2',
    JIA: '#0078D2', EDV: '#C01933', FDX: '#4D148C', UPS: '#351C15', ACA: '#F01428', WJA: '#00A3AD',
    BAW: '#075AAA', VIR: '#E10A0A', DLH: '#05164D', AFR: '#002157', KLM: '#00A1DE', UAE: '#D71920',
    QTR: '#5C0632', SIA: '#00266B', ANA: '#13448F', JAL: '#CC0000', QFA: '#E40000', EZY: '#FF6600',
    RYR: '#073590', AMX: '#0B2343', CLX: '#E4002B', GTI: '#00529B',
    CMP: '#0072CE', AVA: '#E20612', LAN: '#1B0088', LPE: '#1B0088', AZU: '#003DA5', GLO: '#FF7020',
    ICE: '#003B6F', MXY: '#00B5E2',
  };
  function airlineColor(code) {
    if (!code) return null;
    return BRAND_COLOR[code] || 'hsl(' + chipHue(code) + ',75%,62%)';
  }
  // Colour for icons on the dark display: the brand colour, with dark ones (relative luminance under
  // 0.12) mixed 25 % toward white so the hue still reads. Light brand colours are used as they are.
  function iconColor(code) {
    const c = airlineColor(code);
    const m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(c || '');
    if (!m || luminance(c) >= 0.12) return c;
    const mix = (h) => Math.round(parseInt(h, 16) * 0.75 + 255 * 0.25).toString(16).padStart(2, '0');
    return '#' + mix(m[1]) + mix(m[2]) + mix(m[3]);
  }
  // Relative luminance (0..1) of #rrggbb or an hsl() string, for choosing light or dark text/rims.
  function luminance(color) {
    let r, g, b;
    const hx = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color || '');
    if (hx) { [r, g, b] = [hx[1], hx[2], hx[3]].map((v) => parseInt(v, 16) / 255); } else { return 0.6; }
    const lin = (v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  }

  return {
    VERSION, ALT_TOP_M, altColor, iconFor, airlineColor, iconColor, luminance, BRAND_COLOR, typeName, typeLabel, DR_CAP_S, UNITS,
    validate, ageNow, position, trail, polar, cardinal, trend,
    fmtClock, fmtDist, fmtAlt, fmtSpeed, ringPlan, sweepAngle, spread, featuredId, status, rows, makeRow, transition, spotlight, routeText, isUnidentified, chipHue,
  };
});
