"""Reproduce bundled retrieval support data and the pinned SQLite FTS5 source.

The immutable model/data download catalog is app/src/main/assets/retrieval-artifacts.json.
No Hugging Face credential is required. Generated support data must match tool-questions.json.
"""

import argparse
import ast
import hashlib
import io
import json
from pathlib import Path
import re
import sqlite3
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
RUNNER_URL = "https://huggingface.co/juniorrios/automobil-ultra-semnexo/resolve/aff14d91b1b8922e67d348afbbc783759477a641/inference.py"
RUNNER_SHA = "2a45a223a34c4e6917c1603fbc795aff7c8d031fcbd2040368ce9284992f968c"
SQLITE_URL = "https://www.sqlite.org/2026/sqlite-amalgamation-3530400.zip"
SQLITE_SHA3 = "628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e"
# Python's original SCR splitter; emitted offsets use UTF-16, like Kotlin/TypeScript strings.
SENTENCE_BOUNDARY = re.compile(r"\s*\n\s*|(?<=[.!?])\s+(?=[\"'“(\[*]?[A-ZÀ-Ý])")


def fetch(url: str, path: Path, expected: str, algorithm: str = "sha256") -> Path:
    """Download a public pinned file and verify its digest before use.

    Args:
        url: Immutable public artifact URL.
        path: Local cache destination.
        expected: Published or previously verified checksum.
        algorithm: hashlib algorithm name.
    Returns:
        Verified cache path.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with urllib.request.urlopen(url, timeout=120) as response:
            part = path.with_suffix(path.suffix + ".part")
            with part.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
        part.replace(path)
    assert hashlib.new(algorithm, path.read_bytes()).hexdigest() == expected, f"Checksum mismatch: {path}"
    return path


def sentence_spans(text: str) -> list[list[int]]:
    """Return nonblank SCR sentence spans as UTF-16 offsets into supplied text."""
    spans = []
    start = 0
    for match in SENTENCE_BOUNDARY.finditer(text):
        if text[start:match.start()].strip():
            spans.append([len(text[:start].encode("utf-16-le")) // 2,
                          len(text[:match.start()].encode("utf-16-le")) // 2])
        start = match.end()
    if text[start:].strip():
        spans.append([len(text[:start].encode("utf-16-le")) // 2, len(text.encode("utf-16-le")) // 2])
    return spans


def prepare(cache: Path, native_only: bool) -> None:
    """Prepare native sources and, optionally, reproduce bundled retrieval data.

    Args:
        cache: Directory for verified downloads.
        native_only: Skip retrieval support data when preparing native build dependencies.
    """
    # 1. Extract only the two required files from the checksum-verified SQLite archive.
    archive = fetch(SQLITE_URL, cache / "sqlite-amalgamation-3530400.zip", SQLITE_SHA3, "sha3_256")
    destination = ROOT / "third_party/sqlite"
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(archive.read_bytes())) as bundle:
        for name in ("sqlite3.c", "sqlite3.h"):
            (destination / name).write_bytes(bundle.read("sqlite-amalgamation-3530400/" + name))
    if native_only:
        return

    # 2. Extract source stopwords as data; do not execute upstream Python.
    runner = fetch(RUNNER_URL, cache / "f6-inference.py", RUNNER_SHA)
    module = ast.parse(runner.read_text())
    stopwords = next(ast.literal_eval(node.value) for node in module.body
                     if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name)
                     and target.id == "APP_STOPWORDS" for target in node.targets))
    (ASSETS / "f6-stopwords.json").write_text(json.dumps(sorted(stopwords), ensure_ascii=False))

    # 3. Preserve the exact MobileRAG+ sentence boundaries without changing its corpus.
    catalog = json.loads((ASSETS / "retrieval-artifacts.json").read_text())
    corpus = next(item for item in catalog if item["id"] == "mr-corpus")
    path = fetch(corpus["url"], cache / "corpus.sqlite", corpus["sha256"])
    with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as database:
        spans = {str(label): sentence_spans(text) for label, text in database.execute("SELECT id,document FROM documents")}
    (ASSETS / "mobilerag-sentence-spans.json").write_bytes(json.dumps(spans).encode())

    # 4. Fail if preprocessing drifts from the protocol delivered to the phones.
    fixture = json.loads((ASSETS / "tool-questions.json").read_text())
    for name, field in (("f6-stopwords.json", "f6StopwordsSha256"),
                        ("mobilerag-sentence-spans.json", "sentenceSpansSha256"),
                        ("retrieval-artifacts.json", "retrievalArtifactsSha256")):
        assert hashlib.sha256((ASSETS / name).read_bytes()).hexdigest() == fixture[field], name
    print("Pinned FTS5 sources and retrieval support data verified.")


def main() -> None:
    """Prepare reproducible local build inputs."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "reports/tool-assets-cache")
    parser.add_argument("--native-only", action="store_true")
    args = parser.parse_args()
    prepare(args.cache, args.native_only)


if __name__ == "__main__":
    main()
