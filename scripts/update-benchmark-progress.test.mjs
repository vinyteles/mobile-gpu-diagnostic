import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { collectProgress, updateReadme } from './update-benchmark-progress.mjs';

const cpu = 'lfm3-q4km-cpu-replay';
const gpu = 'gemma-mixed48-gpu-replay';
const mixedCpu = 'gemma-mixed48-cpu-replay';
const baseReadme = readFileSync(new URL('../README.md', import.meta.url), 'utf8');
const row = (id, completed, status = 'running') => ({ id, completed, total: 120, status });
const session = (configurations, receivedAtMs = 1) => ({
  purpose: 'benchmark', latest: { smokeTest: false, receivedAtMs, configurations },
});
const cell = (text, number) => text.split('\n').find(line =>
  line.startsWith(`| [${number} ·`) || line.startsWith(`| ${number} ·`)).split('|')[6].trim();
const withCell = (number, value) => baseReadme.split('\n').map(line => {
  if (!line.startsWith(`| [${number} ·`) && !line.startsWith(`| ${number} ·`)) return line;
  const parts = line.split('|'); parts[6] = ` ${value} `; return parts.join('|');
}).join('\n');

test('excludes synthetic and unclassified runs', () => {
  const synthetic = session([row(cpu, 120, 'completed')]); synthetic.purpose = 'synthetic-test';
  const smoke = session([row(cpu, 120, 'completed')]); smoke.latest.smokeTest = true;
  const missingFlag = session([row(cpu, 120, 'completed')]); delete missingFlag.latest.smokeTest;
  assert.equal(collectProgress([synthetic, smoke, missingFlag]).size, 0);
});

test('takes the best individual checkpoint rather than adding runs or using aggregate counts', () => {
  const first = session([row(cpu, 27)]); first.latest.completedAnswers = 120;
  const result = collectProgress([first, session([row(cpu, 27)], 2), session([row(cpu, 0, 'cancelled')], 3)]);
  assert.equal(result.get(6).count, 27);
  assert.equal(result.get(6).complete, false);
});

test('recognizes configuration 8 inside another configuration’s cumulative report', () => {
  const result = collectProgress([session([row(gpu, 120, 'completed'), row(mixedCpu, 120, 'completed')])]);
  assert.equal(result.get(1).complete, true);
  assert.equal(result.get(8).complete, true);
});

test('maps live configurations 11 and 16 without treating wire profile 110 as configuration 10', () => {
  const result = collectProgress([session([
    row('lfm3-q4km-vulkan-mobileragplus-tools-v1', 120, 'completed'),
    row('lfm3-tuned-q4-vulkan-f6-tools-v1', 37),
  ])]);
  assert.equal(result.get(11).complete, true);
  assert.equal(result.get(16).count, 37);
  assert.equal(result.has(10), false);
});

test('rejects malformed counts and incomplete configurations labelled complete', () => {
  for (const record of [row(cpu, 121), row(cpu, -1), row(cpu, 119, 'completed'), { ...row(cpu, 30), total: 30 }]) {
    assert.throws(() => collectProgress([session([record])]), /Invalid progress/);
  }
  assert.throws(() => collectProgress([]), /empty or malformed/);
  assert.throws(() => collectProgress({}), /empty or malformed/);
});

test('preserves completed progress when the index only exposes a newer partial run', () => {
  const original = withCell(6, '✅ 120/120');
  assert.equal(updateReadme(original, collectProgress([session([row(cpu, 27)])]), 'later'), original);
});

test('does not change timestamps or create edits when progress is unchanged', () => {
  const original = withCell(6, '⏳ 27/120 (partial)');
  assert.equal(updateReadme(original, collectProgress([session([row(cpu, 27)])]), 'later'), original);
  assert.equal(updateReadme(original, new Map(), 'later'), original);
});

test('changes only the progress cell and date, retaining configuration 10 and every APK link', () => {
  const original = withCell(6, '— No server result');
  const changed = updateReadme(original, collectProgress([session([row(cpu, 28)])]), '2026-11-07T22:00:00Z');
  assert.equal(cell(changed, 6), '⏳ 28/120 (partial)');
  assert.equal(cell(changed, 10), cell(original, 10));
  assert.deepEqual([...changed.matchAll(/https:[^)]+\.apk/g)].map(m => m[0]),
    [...original.matchAll(/https:[^)]+\.apk/g)].map(m => m[0]));
  const before = original.split('\n'), after = changed.split('\n');
  assert.equal(before.length, after.length);
  assert.equal(before.filter((line, i) => line !== after[i]).length, 2);
});

test('rejects an incomplete README table before writing', () => {
  const missing = baseReadme.split('\n').filter(line => !line.startsWith('| [16 ·')).join('\n');
  assert.throws(() => updateReadme(missing, new Map(), 'later'), /all 16/);
});
