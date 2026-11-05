/** Select only unfinished GPU questions from the supplied diagnostic export. */
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const input = process.argv[2] ?? 'reports/rag-1793807018113/report.json';
const bytes = readFileSync(input);
const report = JSON.parse(bytes);
const fixtureBytes = readFileSync('app/src/main/assets/rag-replay.json');
const hash = value => createHash('sha256').update(value).digest('hex');
if (hash(fixtureBytes) !== report.metadata.fixtureSha256) throw new Error('Fixture differs from previous run');
const fixture = JSON.parse(fixtureBytes);
const gpu = report.results.find(r => r.id === 'gemma-mixed48-gpu-replay');
if (!gpu || gpu.modelSha256 !== report.metadata.models.find(m => m.id === 'gemma-mixed48').sha256) throw new Error('Missing/mismatched GPU result');
const completed = new Set(gpu.answers.filter(a => a.status === 'completed').map(a => a.questionId));
const remaining = fixture.questions.filter(q => !completed.has(q.id)).map(q => q.id);
if (completed.size !== 10 || remaining.length !== 20) throw new Error('Expected the supplied 10-complete/20-remaining snapshot');
writeFileSync('app/src/main/assets/gpu-continuation.json', JSON.stringify({
  sourceRunId: report.metadata.runId,
  sourceReportSha256: hash(bytes),
  fixtureSha256: hash(fixtureBytes),
  modelSha256: gpu.modelSha256,
  completedQuestionIds: [...completed],
  selectedQuestionIds: remaining,
  note: 'Only these 20 questions run. Earlier answers remain in the source report; new sessions are timed independently.',
}, null, 2) + '\n');
console.log(`Selected ${remaining.length} unfinished GPU questions`);
