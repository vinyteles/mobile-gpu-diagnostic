"""Watch Hugging Face checkpoints and download verified ZIPs for later review."""

import argparse
import hashlib
import json
import re
import shutil
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import (
    datetime,
    timezone,
)
from pathlib import Path

from huggingface_hub import (
    HfApi,
    get_token,
    hf_hub_download,
)
from huggingface_hub.errors import EntryNotFoundError


def watch(repository: str, output: Path, interval: int, hours: float, include_smoke: bool, once: bool) -> None:
    """Poll repository commits and keep a local copy of each run's latest report.

    Args:
        repository: Public or private dataset repository in owner/name form.
        output: Local evidence directory; tokens are never written here.
        interval: Poll interval in seconds.
        hours: Maximum monitor lifetime; zero means one poll when once is set.
        include_smoke: Include synthetic validation runs instead of waiting for phone inference.
        once: Stop after one poll, for inspection and integration testing.
    """
    # 1. Use the existing local HF login when available; public sessions can be watched without authentication.
    if not re.fullmatch(r'[A-Za-z0-9][\w.-]*/[A-Za-z0-9][\w.-]*', repository) or '..' in repository:
        raise ValueError('Use owner/repository')
    token = get_token() or False
    api = HfApi(endpoint='https://huggingface.co', token=token)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    deadline = time.monotonic() + hours * 3600
    last_revision = None
    last_progress = None
    last_message = None

    # 2. Read progress and ZIP at the same immutable commit, so concurrent uploads cannot mix them.
    while True:
        now = datetime.now(timezone.utc).isoformat()
        try:
            info = api.repo_info(repository, repo_type='dataset')
            if info.sha != last_revision:
                path = hf_hub_download(repository, 'latest.json', repo_type='dataset', revision=info.sha,
                                       token=token, endpoint='https://huggingface.co')
                progress = json.loads(Path(path).read_text())
                if progress.get('smokeTest') and not include_smoke:
                    message = 'Waiting for real phone results; latest upload is synthetic validation.'
                else:
                    run = progress['cloudRunId']
                    if not re.fullmatch(r'[A-Za-z0-9_-]+', run) or progress['archivePath'] != f'runs/{run}/latest.zip':
                        raise ValueError('Invalid report path in progress metadata.')
                    archive = hf_hub_download(repository, progress['archivePath'], repo_type='dataset', revision=info.sha,
                                              token=token, endpoint='https://huggingface.co')
                    if hashlib.sha256(Path(archive).read_bytes()).hexdigest() != progress['archiveSha256']:
                        raise ValueError('Report ZIP checksum mismatch.')
                    run_dir = output / run
                    run_dir.mkdir(exist_ok=True)
                    shutil.copyfile(archive, run_dir / 'latest-report.zip')
                    (run_dir / 'progress.json').write_text(json.dumps(progress, indent=2, ensure_ascii=False) + '\n')
                    (output / 'latest.json').write_text(json.dumps({'observedAt': now, 'commit': info.sha, 'progress': progress}, indent=2, ensure_ascii=False) + '\n')
                    last_progress = progress
                    message = f"{progress['batch']} | {progress['completedAnswers']} answers | {progress.get('queue', {}).get('status', 'unknown')} | {run}"
                last_revision = info.sha
            elif last_progress:
                age = int(time.time() - last_progress['updatedAtMs'] / 1000)
                state = last_progress.get('queue', {}).get('status')
                message = ('No new upload for more than 3 minutes; network, app, or RTL session may have stopped.'
                           if age > 180 and state == 'running' else 'No new checkpoint; monitoring continues.')
            else:
                message = 'Waiting for real phone results.'
        except EntryNotFoundError:
            message = 'Connected; waiting for the first phone checkpoint.'
        except Exception as error:
            # Exception URLs can contain signed download credentials; log only the type.
            message = f'Poll failed ({type(error).__name__}); retrying.'

        # 3. Record state changes without pretending this process sends chat notifications.
        if message != last_message:
            line = f'{now} {message}'
            print(line, flush=True)
            with (output / 'events.log').open('a') as stream:
                stream.write(line + '\n')
            last_message = message
        (output / 'monitor-status.json').write_text(json.dumps({'checkedAt': now, 'repository': repository, 'message': message}, indent=2) + '\n')
        if once or time.monotonic() >= deadline:
            break
        time.sleep(interval)


def main() -> None:
    """Read watcher settings; authentication comes from the existing HF login."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', action='append', help='owner/repository; repeat for parallel sessions')
    parser.add_argument('--sessions-file', type=Path, help='JSON list of repositories or session manifest objects')
    parser.add_argument('--output', type=Path, default=Path('reports/hf-monitor'))
    parser.add_argument('--interval', type=int, default=60)
    parser.add_argument('--hours', type=float, default=24)
    parser.add_argument('--include-smoke', action='store_true')
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    if args.interval < 15 or args.hours < 0:
        parser.error('Use interval >= 15 seconds and hours >= 0.')
    repositories = args.repository or []
    if args.sessions_file:
        sessions = json.loads(args.sessions_file.read_text())
        repositories.extend(item if isinstance(item, str) else item['repository'] for item in sessions)
    repositories = list(dict.fromkeys(repositories))
    if not repositories:
        parser.error('Supply --repository owner/name or --sessions-file.')
    with ThreadPoolExecutor(max_workers=min(len(repositories), 16)) as executor:
        futures = [executor.submit(watch, repo, args.output / repo.replace('/', '--'), args.interval,
                                   args.hours, args.include_smoke, args.once) for repo in repositories]
        for future in futures:
            future.result()


if __name__ == '__main__':
    main()
