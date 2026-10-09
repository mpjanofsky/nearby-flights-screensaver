const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const FL = require('./lib.js');

const FX = path.join(__dirname, '..', '..', 'fixtures', 'payload');
const load = (n) => JSON.parse(fs.readFileSync(path.join(FX, n + '.json'), 'utf8'));
const names = JSON.parse(fs.readFileSync(path.join(FX, 'index.json'), 'utf8'));

test('every golden fixture validates', () => {
  for (const n of names) assert.deepEqual(FL.validate(load(n)), [], n);
});

test('validate rejects bad payloads', () => {
  const p = load('normal-4');
  assert.match(FL.validate({ ...p, v: 1 })[0], /v must be/);
  assert.ok(FL.validate({ ...p, pn: -1 }).length);
  assert.ok(FL.validate({ ...p, ac: [{ ...p.ac[0], pv: 1.5 }] }).length);
  assert.ok(FL.validate({ ...p, ac: [...p.ac, ...p.ac] }).length);
  assert.ok(FL.validate({ ...p, ac: [{ id: 'x', x: 'n', y: 0, a: 0 }] }).length);
  assert.ok(FL.validate(null).length);
});

test('payload stays far below the 8 KiB target', () => {
  assert.ok(JSON.stringify(load('normal-4')).length < 8192);
});

test('dead-reckoning moves along the track and stops at the cap', () => {
  const p = load('normal-4');
  const ac = { id: 'a', x: 0, y: 0, a: 0, gs: 100, trk: 90 }; // due east, 100 m/s
  const at = (s) => FL.position(ac, { ...p, gen: 0 }, s * 1000);
  assert.equal(Math.round(at(10).x), 1000);
  assert.ok(Math.abs(at(10).y) < 1e-6);
  assert.equal(at(10).stale, false);
  assert.equal(Math.round(at(90).x), 6000); // capped at 60 s
  assert.equal(at(60).stale, false);
  assert.equal(at(90).stale, true);
});

test('no speed or track means the blip never moves', () => {
  const ac = { id: 'a', x: 5, y: 7, a: 0 };
  assert.deepEqual(FL.position(ac, { gen: 0 }, 20000), { x: 5, y: 7, stale: false, over: 0 });
});

test('a reload at a random moment matches continuous time', () => {
  // Same payload at the same wall-clock instant gives the same frame, however it got there.
  const p = load('normal-4');
  const t = p.gen + 12345;
  assert.deepEqual(FL.rows(p, t), FL.rows(JSON.parse(JSON.stringify(p)), t));
  assert.equal(FL.sweepAngle(t), FL.sweepAngle(t));
});

test('polar and cardinal', () => {
  assert.equal(Math.round(FL.polar(0, 100).brg), 0);
  assert.equal(Math.round(FL.polar(100, 0).brg), 90);
  assert.equal(Math.round(FL.polar(-100, 0).brg), 270);
  assert.equal(FL.cardinal(359), 'N');
  assert.equal(FL.cardinal(46), 'NE');
});

test('unit formatting', () => {
  assert.equal(FL.fmtDist(1852 * 4.24, 'av'), '4.2 nm');
  assert.equal(FL.fmtDist(1852 * 41.5, 'av'), '42 nm');
  assert.equal(FL.fmtAlt(34000 * 0.3048, 'av'), '34,000 ft');
  assert.equal(FL.fmtAlt(1234, 'met'), '1,230 m');
  assert.equal(FL.fmtAlt(null, 'av'), null);
  assert.equal(FL.fmtSpeed(210.9, 'av'), '410 kt');
  assert.equal(FL.fmtSpeed(undefined, 'av'), null);
});

test('trend has a level dead band', () => {
  assert.equal(FL.trend(0.2), 'level');
  assert.equal(FL.trend(null), 'level');
  assert.equal(FL.trend(3), 'up');
  assert.equal(FL.trend(-3), 'down');
});

test('ring plan gives 2-3 rings and labels the outer one', () => {
  const r15 = FL.ringPlan(15 * 1852, 'av');
  assert.deepEqual(r15.map((r) => r.label), ['5', '10', '15 nm']);
  assert.deepEqual(FL.ringPlan(50 * 1852, 'av').map((r) => r.label), ['25', '50 nm']);
  assert.ok(FL.ringPlan(5 * 1852, 'av').length >= 2);
});

test('status: ok, empty, stale, no data', () => {
  const now = (p) => p.gen + 1000;
  assert.equal(FL.status(load('normal-4'), now(load('normal-4'))).kind, 'ok');
  assert.equal(FL.status(load('empty'), now(load('empty'))).text, 'No aircraft within 15 nm');
  const s = load('stale');
  assert.equal(FL.status(s, now(s)).text, 'No new data · 3 min');
  assert.equal(FL.status(null, 0).kind, 'nodata');
  const idle = load('idle'); // paused: never stale, however old the document
  assert.equal(FL.status(idle, idle.gen + 3 * 3600 * 1000).kind, 'empty');
  assert.equal(FL.status(idle, idle.gen).text, 'Looking for aircraft\u2026');
  const dead = { ...load('normal-4'), fa: 1 }; // publisher died: fetch age grows past 120 s
  assert.equal(FL.status(dead, dead.gen + 300000).kind, 'stale');
  assert.equal(FL.status({ ...dead, fa: null }, dead.gen).text, 'No data yet');
});

test('rows: closest by live distance, out-of-range ignored, fallbacks filled', () => {
  const p = load('normal-4');
  const { list, closest } = FL.rows(p, p.gen);
  assert.equal(list.length, 4);
  assert.equal(closest, 0);
  const far = { ...p, r: 1000 };
  assert.equal(FL.rows(far, p.gen).closest, -1);
  const m = FL.rows(load('missing-fields'), p.gen).list;
  assert.equal(m[1].name, 'F00002'); // no callsign or reg: hex id
  assert.equal(m[1].alt, '');
  assert.equal(m[1].trend, 'level');
});

test('spotlight leads with the callsign; route only when known; no blanks', () => {
  const p = load('normal-4');
  const rows = FL.rows(p, p.gen).list;
  const first = FL.spotlight(rows[0], p);
  assert.equal(first.name, rows[0].name);
  assert.equal(first.route, 'ATL → MCO');
  assert.ok(first.facts.includes('Boeing 737-900')); // the type is in the facts, as a full name when it fits
  assert.equal(FL.spotlight(rows[3], p).route, null); // no route: the title is just the callsign
  const bare = FL.rows(load('missing-fields'), p.gen).list[1];
  const sp = FL.spotlight(bare, p);
  assert.equal(sp.name, 'F00002');
  assert.equal(sp.route, null);
  assert.equal(sp.chip, null);
  assert.ok(!/null|undefined/.test([sp.name, sp.route || '', sp.facts].join(' ')));
});

test('routeText: both ends, origin only, or nothing', () => {
  assert.equal(FL.routeText({ o: 'ATL', d: 'MCO' }), 'ATL \u2192 MCO');
  assert.equal(FL.routeText({ o: 'CVG', d: null }), 'CVG \u2192 ');
  assert.equal(FL.routeText({ o: null, d: 'PIE' }), null);
  assert.equal(FL.routeText({ o: null, d: null }), null);
});

test('route tiers: origin only is flagged unconfirmed; a ~ id is an unidentified target', () => {
  const p = load('route-tiers');
  const rows = FL.rows(p, p.gen).list;
  const sp = FL.spotlight(rows.find((r) => r.ac.cs === 'AAY928'), p);
  assert.equal(sp.route, 'CVG \u2192 ');
  assert.equal(sp.unconfirmed, true);
  assert.equal(FL.spotlight(rows.find((r) => r.ac.cs === 'DAL2507'), p).unconfirmed, false);
  const tilde = rows.find((r) => FL.isUnidentified(r.ac));
  assert.ok(FL.spotlight(tilde, p).facts.startsWith('Position only'));
});

test('sweep covers one turn', () => {
  assert.ok(Math.abs(FL.sweepAngle(7500) - Math.PI) < 1e-9);
});

test('trail ends at the live position', () => {
  const p = load('normal-4');
  const t = FL.trail(p.ac[0], p, p.gen + 5000);
  assert.ok(t.length >= 4);
  const pos = FL.position(p.ac[0], p, p.gen + 5000);
  assert.deepEqual(t[t.length - 1], [pos.x, pos.y]);
});

test('chipHue: brand table first, stable hash otherwise', () => {
  assert.equal(FL.chipHue('DAL'), 352);
  assert.equal(FL.chipHue('ZZZ'), FL.chipHue('ZZZ'));
  assert.equal(FL.chipHue(null), 0);
});

test('featuredId follows Java\'s ft, not list order; falls back to the first row', () => {
  const ac = (id) => ({ id });
  assert.equal(FL.featuredId({ ft: 'b2', ac: [ac('a1'), ac('b2')] }), 'b2');
  assert.equal(FL.featuredId({ ac: [ac('a1'), ac('b2')] }), 'a1');
  assert.equal(FL.featuredId({ ft: 'gone', ac: [ac('a1')] }), 'a1');
  assert.equal(FL.featuredId({ ac: [] }), null);
});

test('type names fall back to the code; icons map by family with a generic default', () => {
  assert.equal(FL.typeLabel('B738', 40), 'Boeing 737-800');
  assert.equal(FL.typeLabel('B738', 8), 'B738'); // no room: the code
  assert.equal(FL.typeLabel('ZZZZ', 40), 'ZZZZ'); // unknown type keeps its code
  assert.equal(FL.typeLabel(null, 40), '');
  assert.equal(FL.iconFor('B39M'), 'b737');
  assert.equal(FL.iconFor('C172'), 'cessna');
  assert.equal(FL.iconFor('EC35'), 'a7'); // helicopter by prefix
  assert.equal(FL.iconFor('ZZZZ'), 'a0');
  assert.equal(FL.iconFor(null), 'a0');
});

test('icon colours: dark brands are lifted, light ones kept', () => {
  assert.equal(FL.iconColor('NKS'), '#FFEC00');
  assert.notEqual(FL.iconColor('UAL'), '#0033A0');
  assert.ok(FL.luminance(FL.iconColor('UAL')) > FL.luminance('#0033A0'));
  assert.equal(FL.iconColor(null), null);
});

test('airline colours: brand table, stable fallback, light/dark choice', () => {
  assert.equal(FL.airlineColor('DAL'), '#C01933');
  assert.equal(FL.airlineColor(null), null);
  assert.equal(FL.airlineColor('QQQ'), FL.airlineColor('QQQ')); // stable for unknown carriers
  assert.ok(FL.luminance('#FFEC00') > 0.5 && FL.luminance('#05164D') < 0.06);
});

test('Copa has a brand colour, not a hashed one', () => {
  assert.equal(FL.airlineColor('CMP'), '#0072CE');
});

test('spread pushes close points apart, bounded, and leaves far or missing ones alone', () => {
  const out = FL.spread([{ x: 100, y: 100 }, { x: 102, y: 100 }, null, { x: 300, y: 300 }], 40, 30);
  assert.ok(Math.hypot(out[1].x - out[0].x, out[1].y - out[0].y) >= 39);
  assert.ok(Math.hypot(out[0].x - 100, out[0].y - 100) <= 30.001);
  assert.deepEqual(out[3], { x: 300, y: 300 });
  assert.equal(out[2], null);
  const same = FL.spread([{ x: 5, y: 5 }, { x: 5, y: 5 }], 20, 30); // identical points still separate
  assert.ok(Math.hypot(same[1].x - same[0].x, same[1].y - same[0].y) >= 19);
  assert.deepEqual(FL.spread([{ x: 1, y: 2 }], 20, 30), [{ x: 1, y: 2 }]);
});

test('altitude colour: ramp ends, clamping, interpolation, unknown', () => {
  assert.equal(FL.altColor(0), '#4fd1c5');
  assert.equal(FL.altColor(-50), '#4fd1c5'); // below ground level clamps
  assert.equal(FL.altColor(FL.ALT_TOP_M), '#c26bff');
  assert.equal(FL.altColor(99999), '#c26bff');
  assert.equal(FL.altColor(914), '#7bd36a'); // exactly a stop
  assert.notEqual(FL.altColor(2000), FL.altColor(2500));
  assert.equal(FL.altColor(null), '#8fa3b8');
});

test('transition: no previous publish, or nothing changed, means no animation', () => {
  assert.equal(FL.transition(load('normal-4')), null); // no pn
  const p = load('normal-4'); // 4 aircraft: 1 featured, 3 rows; same slots as before
  const same = { ...p, pn: 3, ac: p.ac.map((a, i) => ({ ...a, pv: a.id === p.ft ? 0 : [1, 2, 3][i - (i > 1 ? 1 : 0)] })) };
  assert.equal(FL.transition(same), null);
});

test('transition: moved, new, demoted and gone aircraft', () => {
  const t = FL.transition(load('transition'));
  assert.equal(t.featChanged, true); // a4d5e6 was in the list, now in the band
  assert.equal(t.oldFeatured.id, 'a1b2c3'); // fades out of the band; it is now the first list row
  // list rows now: a1b2c3 (was the band), 0c7f81 (was slot 3), ab12cd (new): all changed
  assert.deepEqual(t.moves.map((m) => m.changed), [true, true, true]);
  // old contents that fade out where they were: SKW5521 (gone, slot 2 of 3) and 0c7f81 (slot 3)
  assert.deepEqual(t.ghosts.map((g) => [g.ac.id, g.k]), [['f00009', 1], ['0c7f81', 0]]);
});

test('transition: a row that kept its place does not fade', () => {
  const p = { ...load('normal-4'), pn: 3 };
  const list = p.ac.filter((a) => a.id !== p.ft);
  p.ac = p.ac.map((a) => (a.id === p.ft ? { ...a, pv: 0 } : { ...a, pv: list.indexOf(a) + 1 }));
  assert.equal(FL.transition(p), null); // everything where it was
  p.ac = p.ac.map((a, i) => (a.id === list[2].id ? { ...a, pv: 1 } : a)); // one row changed place
  const t = FL.transition(p);
  assert.equal(t.moves.filter((m) => m.changed).length, 1);
  assert.equal(t.ghosts.length, 1);
});

test('transition: the featured aircraft staying put is not a swap', () => {
  const p = { ...load('normal-4'), pn: 3 };
  p.ac = p.ac.map((a) => (a.id === p.ft ? { ...a, pv: 0 } : { ...a, pv: 1 }));
  const t = FL.transition(p);
  assert.equal(t.featChanged, false);
});

test('status: a message from Java replaces the aircraft', () => {
  const p = { v: 2, gen: 0, fa: null, st: false, src: 'local', r: 27780, u: 'av', ac: [], ap: [], msg: 'Set your home latitude and longitude' };
  assert.deepEqual(FL.validate(p), []);
  assert.deepEqual(FL.status(p, 1000), { kind: 'msg', text: 'Set your home latitude and longitude' });
  assert.ok(FL.validate({ ...p, msg: 5 }).length > 0);
});


test('clock selection overrides the device locale and handles midnight and noon', () => {
  for (const [hour, minute, twelve, twentyFour] of [
    [0, 5, '12:05 AM', '00:05'], [12, 0, '12:00 PM', '12:00'],
    [13, 9, '1:09 PM', '13:09'], [23, 59, '11:59 PM', '23:59'],
  ]) {
    const d = new Date(2026, 0, 1, hour, minute);
    assert.equal(FL.fmtClock(d, '12', 'en-US'), twelve);
    assert.equal(FL.fmtClock(d, '24', 'en-US'), twentyFour);
  }
  const d = new Date(2026, 0, 1, 13, 9);
  assert.equal(FL.fmtClock(d, undefined, 'en-US'), d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }));
  assert.equal(FL.fmtClock(d, '12', 'en-GB'), '1:09 pm');
  assert.equal(FL.fmtClock(new Date(2026, 0, 1, 0, 5), '24', 'en-GB'), '00:05');
});

test('clock payload format is optional and restricted to 12 or 24', () => {
  const p = load('empty');
  for (const clock of ['12', '24']) assert.deepEqual(FL.validate({ ...p, clock }), []);
  for (const clock of ['invalid', 24, null]) assert.ok(FL.validate({ ...p, clock }).length);
});
