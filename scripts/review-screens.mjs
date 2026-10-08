#!/usr/bin/env node
// Renders payload fixtures the way the plugin publishes them (payload inlined into index.html,
// gen = now - age) and screenshots them at the Show 5 size with headless Chrome.
// Frames are taken over the DevTools protocol after a real wait: Chrome's --screenshot fires at
// first paint whatever --timeout or --virtual-time-budget say, which froze fades and caught icons
// before they had decoded.
// Usage: node scripts/review-screens.mjs [fixture age_s t_ms[,t_ms...]]  (no args = the review set)
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.env.OUT || path.join(root, 'build/screens');
// VIEW=WxH renders at another viewport (default: the Show 5's 960x480).
const [vw, vh] = (process.env.VIEW || '960x480').split('x').map(Number);
fs.mkdirSync(out, { recursive: true });
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'review-screens-'));
// EXTRA_CSS / SUFFIX: render a layout variant without touching the stylesheet (for mock-ups).
const page = fs.readFileSync(path.join(root, 'assets/flights/index.html'), 'utf8').replace('</head>', `<style>${process.env.EXTRA_CSS || ''}</style></head>`);
const base = pathToFileURL(path.join(root, 'assets/flights')).href + '/';
const names = JSON.parse(fs.readFileSync(path.join(root, 'fixtures/payload/index.json'), 'utf8'));

// [fixture, age seconds, frame times in ms after load]
let shots = names.map((n) => [n, 0, [3000]]);
shots.push(['normal-4', 0, [9500, 17500]], ['normal-4', 45, [3000]], ['transition', 0, [100, 400, 700, 1500]], ['normal-5', 0, [9500]]);
if (process.argv.length === 5) shots = [[process.argv[2], Number(process.argv[3]), process.argv[4].split(',').map(Number)]];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const port = 9300 + Math.floor(Math.random() * 600);
const chrome = spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
  '--headless=new', '--disable-gpu', '--hide-scrollbars', '--allow-file-access-from-files',
  '--window-size=960,480', '--remote-debugging-port=' + port, '--user-data-dir=' + path.join(tmp, 'profile'), 'about:blank']);
try {
  let wsUrl;
  for (let i = 0; i < 50 && !wsUrl; i++) {
    await sleep(200);
    try { wsUrl = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((x) => x.type === 'page')?.webSocketDebuggerUrl; } catch { /* Chrome not listening yet */ }
  }
  if (!wsUrl) throw new Error('Chrome did not open its debugging port');
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let id = 0; const pending = new Map(); let onLoad = null;
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) { pending.get(d.id)(d.result); pending.delete(d.id); }
    if (d.method === 'Page.loadEventFired' && onLoad) onLoad();
  };
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  await send('Page.enable');
  // --window-size includes headless window chrome, which left a 960×393 viewport; pin the Show's size.
  await send('Emulation.setDeviceMetricsOverride', { width: vw, height: vh, deviceScaleFactor: 1, mobile: false });

  for (const [name, age, times] of shots) {
    const p = JSON.parse(fs.readFileSync(path.join(root, `fixtures/payload/${name}.json`), 'utf8'));
    for (const t of times) { // one fresh load per frame, so t is measured from that page's load
      p.gen = Date.now() - age * 1000;
      const html = page.replace('<head>', `<head><base href="${base}">`).replace('/*PAYLOAD*/', () => JSON.stringify(p).replace(/</g, '\\u003c'));
      const f = path.join(tmp, name + '.html');
      fs.writeFileSync(f, html);
      const loaded = new Promise((r) => { onLoad = r; });
      await send('Page.navigate', { url: pathToFileURL(f).href });
      await loaded;
      await sleep(t);
      const { data } = await send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(path.join(out, `${name}${age ? '-age' + age : ''}-t${t}${process.env.SUFFIX || ''}.png`), Buffer.from(data, 'base64'));
    }
  }
  ws.close();
} finally {
  chrome.kill();
}
console.log(fs.readdirSync(out).filter((f) => f.endsWith('.png')).length + ' screens');
