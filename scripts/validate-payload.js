#!/usr/bin/env node
// Validates a payload file with the renderer's own validator: the cross-language contract check.
// Usage: scripts/validate-payload.js <file.json>...
const fs = require('node:fs');
const FL = require('../assets/flights/lib.js');
let bad = 0;
for (const f of process.argv.slice(2)) {
  const errs = FL.validate(JSON.parse(fs.readFileSync(f, 'utf8')));
  if (errs.length) { bad = 1; console.error(f + ': ' + errs.join('; ')); }
}
process.exit(bad);
