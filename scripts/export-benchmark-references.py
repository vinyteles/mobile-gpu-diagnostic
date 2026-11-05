"""Export benchmark answers/evidence for the frozen RAG questions. Requires pyarrow."""

import argparse
import hashlib
import json
from pathlib import Path

import pyarrow.parquet as pq


def export_references(source_root: Path, destination: Path) -> None:
    """Join benchmark references to fixtures by exact ID and verify question text.

    Args:
        source_root: Local cemig-mobile checkout containing downloaded benchmark parquet files.
        destination: JSON snapshot read by build-replay-fixtures.mjs.
    """
    # 1. Select the same 30 questions used by the mobile parity fixtures.
    fixture = json.loads((source_root / 'src/ai/mobile-rag-plus/__tests__/fixtures/retrieval.json').read_text())
    questions = {item['id']: item['question'] for item in fixture['questions']}
    references = {}

    # 2. Match IDs across both benchmark categories, keeping original answers and evidence.
    for category in ('normas_regulamentadoras', 'documentos_internos'):
        relative = Path('downloaded-assets/benchmark_mobile') / category / 'train-00000-of-00001.parquet'
        source = source_root / relative
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        for row in pq.read_table(source).to_pylist():
            identifier = row['id']
            if identifier not in questions:
                continue
            if row['question'] != questions[identifier]:
                raise ValueError(f'Question text mismatch for {identifier}')
            if identifier in references or not row['answer']:
                raise ValueError(f'Duplicate or empty reference for {identifier}')
            references[identifier] = {
                'answer': row['answer'],
                'evidence': row['evidence'],
                'sourceFile': row['source_file'],
                'sourceId': row['source_id'],
                'category': category,
                'benchmarkPath': str(relative),
                'benchmarkSha256': digest,
                'dataset': 'UFGCEMIGONA/benchmark_mobile',
                'note': 'Original benchmark reference, not an on-device generated answer; human-reviewed variant is not applied.',
            }

    # 3. Fail instead of silently omitting a benchmark answer.
    if set(references) != set(questions):
        raise ValueError(f'Missing references: {sorted(set(questions) - set(references))}')
    destination.write_text(json.dumps(references, ensure_ascii=False, indent=2) + '\n')
    print(f'Exported {len(references)} exact ID/text matches to {destination}')


def main() -> None:
    """Read local paths and write the reproducible reference snapshot."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path('../cemig-mobile'), help='cemig-mobile checkout with benchmark downloads')
    parser.add_argument('--output', type=Path, default=Path('scripts/benchmark-references.json'), help='reference snapshot destination')
    arguments = parser.parse_args()
    export_references(arguments.source_root, arguments.output)


if __name__ == '__main__':
    main()
