/** Freeze real MobileRAG+ prompts. Usage: node scripts/build-replay-fixtures.mjs [cemig-mobile path]. */
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

// 1. Read the exact parity prompts and their export provenance.
const sourceRoot = resolve(process.argv[2] ?? '../cemig-mobile');
const sourcePath = 'src/ai/mobile-rag-plus/__tests__/fixtures/retrieval.json';
const raw = readFileSync(join(sourceRoot, sourcePath));
const source = JSON.parse(raw);
const references = JSON.parse(readFileSync(new URL('./benchmark-references.json', import.meta.url)));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// 2. Attach historical outputs as unscored comparisons, never as ground truth.
const historical = new Map();
const answerDirectory = 'downloaded-assets/benchmark_mobile/answers';
for (const name of readdirSync(join(sourceRoot, answerDirectory)).filter(name => /^mobilerag_plus-\d+\.jsonl$/.test(name)).sort()) {
  const bytes = readFileSync(join(sourceRoot, answerDirectory, name));
  for (const line of bytes.toString().split('\n').filter(line => line.trim())) {
    const item = JSON.parse(line);
    historical.set(item.question_id, {
      answer: item.answer,
      source: `${answerDirectory}/${name}`,
      sourceSha256: sha256(bytes),
      contextHits: item.context_hits,
      note: 'Historical generated output, not ground truth. Exact context equivalence has not been established.',
    });
  }
}

// 3. Preserve the already-fitted prompt verbatim; never run fitting per model.
const questions = source.questions.map(item => ({
  id: item.id,
  question: item.question,
  prompt: item.fitted.prompt,
  promptSha256: sha256(item.fitted.prompt),
  expectedInputTokensWithoutChatTemplate: item.fitted.system_tokens + item.fitted.prompt_tokens,
  sources: item.documents.slice(0, item.fitted.kept).map((document, index) => ({
    ...document,
    text: index === 0 && item.fitted.first_document_cut ? item.fitted.first_document_text : document.text,
  })),
  reference: references[item.id],
  historicalOutput: historical.get(item.id) ?? null,
}));
if (questions.length !== 30 || new Set(questions.map(q => q.id)).size !== 30) throw new Error('Expected 30 unique fixture questions');
for (const item of questions) {
  if (!item.reference?.answer || !item.prompt || !item.sources.length || !item.prompt.includes(item.question)) throw new Error(`Incomplete fixture: ${item.id}`);
}
const result = {
  schemaVersion: 1,
  sourcePath,
  sourceSha256: sha256(raw),
  sourceMetadata: source.meta,
  systemPrompt: source.meta.system_prompt,
  scope: 'Frozen Python parity fixtures validated by the mobile pipeline; not captured phone benchmark answers. Retrieval is not rerun.',
  qualityPolicy: 'Benchmark references and evidence are evaluation-only and never sent to the model. Citation presence/range is structural only; semantic correctness is unscored pending review.',
  questions,
};
const destination = resolve(dirname(fileURLToPath(import.meta.url)), '../app/src/main/assets/rag-replay.json');
writeFileSync(destination, JSON.stringify(result, null, 2) + '\n');
console.log(`${questions.length} prompts; ${questions.filter(q => q.historicalOutput).length} historical outputs; ${destination}`);
