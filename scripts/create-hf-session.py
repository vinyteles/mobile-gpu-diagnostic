"""Create one public Hugging Face dataset repository for one RTL session."""

import argparse
import json
import re
from datetime import (
    datetime,
    timezone,
)
from pathlib import Path

from huggingface_hub import (
    HfApi,
    get_token,
)


def create_session(batch: int, device: str, output: Path, private: bool = False) -> dict:
    """Create a session under the locally authenticated user's account.

    Args:
        batch: APK batch number, 1 through 5.
        device: Planned phone/SoC label, recorded as supplied and normalized in the repo name.
        output: Local session manifest destination; contains no credentials.
        private: Use a private dataset instead of the public collaboration default.
    Returns:
        Repository and experiment identifiers for the APK and monitor.
    """
    # 1. Identify the owner without sharing account tokens between participants.
    if not get_token():
        raise RuntimeError('Run hf auth login first.')
    api = HfApi(endpoint='https://huggingface.co')
    owner = api.whoami()['name']
    timestamp = datetime.now(timezone.utc)
    slug = re.sub(r'[^a-z0-9-]+', '-', device.lower()).strip('-')[:24]
    if not slug:
        raise ValueError('Supply a phone or SoC label.')
    repository = f"{owner}/mobilebench-{timestamp.strftime('%Y%m%d-%H%M%S')}-b{batch}-{slug}"

    # 2. Create the session explicitly; leave reference data untouched until the phone uploads it.
    api.create_repo(repository, repo_type='dataset', private=private)
    manifest = {'repository': repository, 'url': f'https://huggingface.co/datasets/{repository}',
                'batch': batch, 'plannedDevice': device, 'createdAt': timestamp.isoformat(),
                'source': 'https://github.com/vinyteles/mobile-gpu-diagnostic', 'status': 'planned'}
    readme = f'''---
pretty_name: {json.dumps(f'Mobile benchmark session {batch} on {device}', ensure_ascii=False)}
---
# Mobile benchmark session

- APK batch: {batch}
- Planned device: {device}
- Created: {timestamp.isoformat()}
- Source and instructions: https://github.com/vinyteles/mobile-gpu-diagnostic

Results have not been uploaded yet. `latest.json` will track progress; `runs/<id>/latest.zip`
will contain the cumulative report. Reports include questions, retrieved context, answers,
reference answers and device/time/memory metrics. Synthetic test runs are labelled explicitly.

Compare phone timings only with matching hardware, runtime, model and prompt settings.
'''
    api.upload_file(repo_id=repository, repo_type='dataset', path_in_repo='README.md',
                    path_or_fileobj=readme.encode(), commit_message='Describe benchmark session')

    # 3. Save a shareable manifest; the app receives a token scoped to this repository separately.
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    return manifest


def main() -> None:
    """Create a public session with the current HF login and print only non-secret identifiers."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch', type=int, choices=range(1, 6), required=True)
    parser.add_argument('--device', required=True)
    parser.add_argument('--manifest', type=Path, default=Path('reports/session.json'))
    parser.add_argument('--private', action='store_true')
    args = parser.parse_args()
    print(json.dumps(create_session(args.batch, args.device, args.manifest, args.private), indent=2))


if __name__ == '__main__':
    main()
