#!/usr/bin/env node
// Emits update-manifest.json (matching com.sieve.app.update.UpdateManifest) from a built APK.
// Usage:
//   node tools/generate-update-manifest.mjs --apk <path> --versionCode <n> --versionName <v> \
//        --apkUrl <url> [--changelog <text>] [--minSdk 26] [--out update-manifest.json]
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : def;
}

if (process.argv.includes('--help') || process.argv.length <= 2) {
  console.log(
    'Usage: node generate-update-manifest.mjs --apk <path> --versionCode <n> --versionName <v> --apkUrl <url> [--changelog <text>] [--minSdk 26] [--out update-manifest.json]',
  );
  process.exit(process.argv.includes('--help') ? 0 : 1);
}

const apk = arg('apk');
const versionCode = Number.parseInt(arg('versionCode'), 10);
const versionName = arg('versionName');
const apkUrl = arg('apkUrl');
const changelog = arg('changelog', '');
const minSdk = Number.parseInt(arg('minSdk', '26'), 10);
const out = arg('out', 'update-manifest.json');

if (!apk || !Number.isInteger(versionCode) || !versionName || !apkUrl) {
  console.error('Missing required args (--apk, --versionCode, --versionName, --apkUrl). Run with --help.');
  process.exit(1);
}

let bytes;
try {
  bytes = readFileSync(apk);
} catch {
  console.error(`Cannot read APK: ${apk}`);
  process.exit(1);
}

const sha256 = createHash('sha256').update(bytes).digest('hex');
const manifest = { versionCode, versionName, apkUrl, sha256, minSdk, changelog };
writeFileSync(out, JSON.stringify(manifest, null, 2) + '\n');
console.log(`Wrote ${out} (versionCode=${versionCode}, sha256=${sha256})`);
