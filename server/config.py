"""Service settings: conservative defaults, optionally overridden by a TOML file.

Example override file (see server/deploy/config.example.toml):

    data_dir = "/home/me/mobilebench-upload/var"
    [limits]
    max_upload_bytes = 33554432
"""

import dataclasses
import logging
import logging.handlers
import os
import sys
import tomllib
from pathlib import Path

HOME = Path.home()


@dataclasses.dataclass(frozen=True)
class Limits:
    """Size, rate and quota bounds for anonymous clients. Byte values are exact bytes."""

    max_upload_bytes: int = 64 * 1024 * 1024
    max_uncompressed_bytes: int = 512 * 1024 * 1024
    max_member_bytes: int = 256 * 1024 * 1024
    max_members: int = 64
    max_runs_per_session: int = 32
    # Registration: per client address and service-wide.
    registrations_per_ip_per_hour: int = 20
    registrations_per_day: int = 60
    max_sessions: int = 500
    # Uploads: the app sends a cumulative snapshot after every completed question, plus stop/final ones.
    uploads_per_session_per_hour: int = 1200
    uploads_per_ip_per_hour: int = 3600
    upload_bytes_per_session_per_day: int = 6 * 1024 * 1024 * 1024
    # Status/feed reads per client address.
    reads_per_ip_per_hour: int = 7200
    # Disk: pending (not yet published) spool bytes, and space always left free on the volume.
    max_pending_bytes_per_session: int = 1024 * 1024 * 1024
    max_spool_bytes: int = 8 * 1024 * 1024 * 1024
    min_free_disk_bytes: int = 5 * 1024 * 1024 * 1024
    # Concurrency: bodies being received and ZIPs being validated at the same time.
    concurrent_receives: int = 8
    concurrent_validations: int = 2
    body_idle_timeout_s: float = 60.0
    body_total_timeout_s: float = 900.0


@dataclasses.dataclass(frozen=True)
class Settings:
    """Runtime settings shared by the API and worker processes."""

    namespace: str = "vinyteles"
    repo_prefix: str = "mobilebench-relay"
    index_repo_name: str = "mobilebench-index-v2"
    token_file: Path = HOME / ".config/mobile-benchmark-upload/hf-token"
    data_dir: Path = HOME / "mobilebench-upload/var"
    log_dir: Path = HOME / "mobilebench-upload/logs"
    endpoint_file: Path = HOME / "mobilebench-upload/run/endpoint.txt"
    host: str = "127.0.0.1"
    port: int = 18765
    # Only these peers may set the client address header (the local cloudflared connector).
    trusted_proxies: tuple[str, ...] = ("127.0.0.1", "::1")
    client_ip_header: str = "cf-connecting-ip"
    # Worker pacing.
    min_latest_interval_s: float = 90.0
    worker_threads: int = 3
    retry_base_s: float = 30.0
    retry_max_s: float = 900.0
    index_interval_s: float = 120.0
    poll_interval_s: float = 2.0
    limits: Limits = Limits()

    @property
    def database_path(self) -> Path:
        return self.data_dir / "relay.sqlite3"

    @property
    def spool_dir(self) -> Path:
        return self.data_dir / "spool"

    @property
    def index_repo(self) -> str:
        return f"{self.namespace}/{self.index_repo_name}"


def load_settings(path: Path | None = None) -> Settings:
    """Read settings from a TOML file, falling back to MOBILEBENCH_CONFIG and then defaults.

    Args:
        path: Optional TOML path. Unknown keys are rejected so typos do not pass silently.
    Returns:
        Frozen settings with directories created (mode 0700).
    """
    # 1. Locate and parse the optional override file.
    path = path or (Path(os.environ["MOBILEBENCH_CONFIG"]) if os.environ.get("MOBILEBENCH_CONFIG") else None)
    raw = tomllib.loads(path.read_text()) if path else {}

    # 2. Build typed settings; paths become Path objects and lists become tuples.
    limits = Limits(**raw.pop("limits", {}))
    for key in ("token_file", "data_dir", "log_dir", "endpoint_file"):
        if key in raw:
            raw[key] = Path(raw[key]).expanduser()
    if "trusted_proxies" in raw:
        raw["trusted_proxies"] = tuple(raw["trusted_proxies"])
    settings = Settings(limits=limits, **raw)

    # 3. Create private runtime directories.
    for directory in (settings.data_dir, settings.spool_dir, settings.log_dir, settings.endpoint_file.parent):
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    return settings


def configure_logging(settings: Settings, name: str) -> None:
    """Log to a rotating file and stderr. Callers never log secrets, tokens or signed URLs.

    Args:
        settings: Provides the log directory.
        name: Process name; the file is <log_dir>/<name>.log.
    """
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    file_handler = logging.handlers.RotatingFileHandler(settings.log_dir / f"{name}.log", maxBytes=10 * 1024 * 1024, backupCount=5)
    stream_handler = logging.StreamHandler(sys.stderr)
    stream_handler.setLevel(logging.WARNING)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for handler in (file_handler, stream_handler):
        handler.setFormatter(formatter)
        root.addHandler(handler)
    # Third-party HTTP loggers can print request URLs (including signed upload URLs); keep them quiet.
    for noisy in ("httpx", "httpx2", "huggingface_hub", "urllib3", "uvicorn.access"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
