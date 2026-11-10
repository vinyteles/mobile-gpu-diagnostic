import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

export const INDEX_URL = 'https://huggingface.co/datasets/vinyteles/mobilebench-index-v2/resolve/main/sessions.json';

// README numbers differ from wire profiles. Configuration 10 is an external APK.
export const CONFIGURATIONS = new Map([
  ['gemma-mixed48-gpu-replay', 1],
  ['lfm3-q4km-vulkan-replay', 2],
  ['lfm3-mixed4-litert-gpu-replay', 3],
  ['gemma-base-mixed248-gpu-replay', 4],
  ['gemma-base-q8-vulkan-replay', 5],
  ['lfm3-q4km-cpu-replay', 6],
  ['gemma-int8-cpu-replay', 7],
  ['gemma-mixed48-cpu-replay', 8],
  ['gemma-base-mixed248-cpu-replay', 9],
  ['lfm3-q4km-vulkan-mobileragplus-tools-v1', 11],
  ['lfm3-tuned-q4km-vulkan-mobileragplus-tools-v1', 12],
  ['lfm3-q4km-vulkan-f6-tools-v1', 13],
  ['lfm3-tuned-q4km-vulkan-f6-tools-v1', 14],
  ['lfm3-tuned-q4-vulkan-mobileragplus-tools-v1', 15],
  ['lfm3-tuned-q4-vulkan-f6-tools-v1', 16],
]);

/** Select the best individual checkpoint per configuration; never sum separate runs. */
export function collectProgress(sessions) {
  if (!Array.isArray(sessions) || sessions.length === 0) {
    throw new Error('The public session index is empty or malformed; keeping the README unchanged.');
  }
  const best = new Map();
  for (const session of sessions) {
    if (session.purpose !== 'benchmark' || session.latest?.smokeTest !== false) continue;
    const latest = session.latest;
    if (!Array.isArray(latest.configurations)) throw new Error('Missing configuration progress.');
    for (const record of latest.configurations) {
      const number = CONFIGURATIONS.get(record.id);
      if (!number) continue;
      const count = record.completed;
      if (record.total !== 120 || !Number.isInteger(count) || count < 0 || count > 120 ||
          (record.status === 'completed' && count !== 120)) {
        throw new Error(`Invalid progress for configuration ${number}; keeping the README unchanged.`);
      }
      const candidate = {
        count,
        complete: record.status === 'completed',
        status: record.status,
        receivedAt: latest.receivedAtMs ?? 0,
      };
      const previous = best.get(number);
      if (!previous || Number(candidate.complete) > Number(previous.complete) ||
          (candidate.complete === previous.complete && (count > previous.count ||
            (count === previous.count && candidate.receivedAt > previous.receivedAt)))) {
        best.set(number, candidate);
      }
    }
  }
  return best;
}

function progressLabel(progress) {
  if (progress.complete) return '✅ 120/120';
  if (progress.count > 0) return `⏳ ${progress.count}/120 (partial)`;
  if (progress.status === 'skipped') return '⏭️ 0/120 (skipped)';
  if (progress.status === 'error') return '⚠️ 0/120 (error)';
  if (['cancelled', 'stopped'].includes(progress.status)) return '⏸️ 0/120 (stopped)';
  return '⏳ 0/120';
}

/** Update only Progress cells and the change date; retain earlier higher checkpoints. */
export function updateReadme(readme, progress, changedAt) {
  if (!readme.includes('| Configuration name | Model | RAG | Hardware | Backend | Progress |')) {
    throw new Error('Expected the README Progress column.');
  }
  const seen = new Set();
  let changed = false;
  const lines = readme.split('\n').map(line => {
    const match = line.match(/^\| \[?(\d+) ·/);
    if (!match) return line;
    const number = Number(match[1]);
    const cells = line.split('|').slice(1, -1).map(cell => cell.trim());
    if (cells.length !== 8 || number < 1 || number > 16 || seen.has(number)) {
      throw new Error('Unexpected README configuration table.');
    }
    seen.add(number);
    const next = progress.get(number);
    if (number === 10 || !next) return line;
    const current = cells[5];
    const oldCount = Number(current.match(/(\d+)\/120/)?.[1] ?? 0);
    if (next.count < oldCount || (current.startsWith('✅') && !next.complete)) return line;
    cells[5] = progressLabel(next);
    if (cells[5] === current) return line;
    changed = true;
    return `| ${cells.join(' | ')} |`;
  });
  if (seen.size !== 16) throw new Error('Expected all 16 configurations in the README.');
  if (!changed) return readme;
  const marker = /^<!-- benchmark-progress-updated -->.*$/m;
  const result = lines.join('\n');
  if (!marker.test(result)) throw new Error('Missing progress change-date marker.');
  return result.replace(marker, `<!-- benchmark-progress-updated -->Progress last changed: ${changedAt}.`);
}

/** Read the public relay mirror, then write only when the displayed progress changes. */
async function main() {
  // 1. Fetch and validate the index before touching the README.
  const response = await fetch(INDEX_URL, {
    headers: { 'Cache-Control': 'no-cache' },
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`Session index returned HTTP ${response.status}; README unchanged.`);
  const progress = collectProgress(await response.json());

  // 2. Preserve the document and existing checkpoints; change only progress cells.
  const path = new URL('../README.md', import.meta.url);
  const original = await readFile(path, 'utf8');
  const updated = updateReadme(original, progress, new Date().toISOString());
  if (updated === original) {
    console.log('No progress changes; no commit needed.');
    return;
  }
  await writeFile(path, updated);
  console.log('README progress updated from the public relay session index.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
