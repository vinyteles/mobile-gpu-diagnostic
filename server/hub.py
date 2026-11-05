"""Hugging Face access for the worker. Only this module reads the credential file.

The token is re-read whenever the file's mtime/size changes, so replacing the file
(see DEPLOYMENT.md) takes effect without restarting the worker.
"""

import logging
from pathlib import Path

import httpx2
from huggingface_hub import (
    CommitOperationAdd,
    HfApi,
)
from huggingface_hub.errors import HfHubHTTPError

LOGGER = logging.getLogger("mobilebench.hub")


class HubError(Exception):
    """A sanitized Hub failure: no URLs, headers, bodies or tokens.

    Attributes:
        status: HTTP status when known, else None.
        retry_after_s: Server-requested delay when the Hub sent Retry-After.
        credential: True when the credential is missing, invalid or for the wrong account.
    """

    def __init__(self, message: str, status: int | None = None, retry_after_s: float | None = None,
                 credential: bool = False):
        super().__init__(message)
        self.status = status
        self.retry_after_s = retry_after_s
        self.credential = credential


class HubClient:
    """Create public dataset repositories and atomic multi-file commits under one namespace."""

    def __init__(self, token_file: Path, namespace: str):
        """Prepare a client; the token is loaded lazily.

        Args:
            token_file: File containing only the HF token (mode 0600).
            namespace: Account that must own the token and every repository written.
        """
        self.token_file = token_file
        self.namespace = namespace
        self.signature = None
        self.api = None

    def client(self) -> HfApi:
        """Return an authenticated API client, reloading the credential if its file changed.

        Returns:
            HfApi bound to the current token.
        Raises:
            HubError: credential missing, unreadable, or not for the expected namespace.
        """
        # 1. Detect replacement of the credential file.
        try:
            stat = self.token_file.stat()
        except OSError:
            raise HubError("credential file missing", credential=True) from None
        signature = (stat.st_mtime_ns, stat.st_size, stat.st_ino)
        if self.api is not None and signature == self.signature:
            return self.api

        # 2. Load and verify the account without ever logging the value.
        token = self.token_file.read_text().strip()
        if not token.startswith("hf_"):
            raise HubError("credential file does not contain an HF token", credential=True)
        api = HfApi(endpoint="https://huggingface.co", token=token, library_name="mobilebench-relay")
        identity = self.call(api.whoami)
        if identity.get("name") != self.namespace:
            raise HubError("credential belongs to an unexpected account", credential=True)
        self.api, self.signature = api, signature
        LOGGER.info("credential loaded for namespace %s", self.namespace)
        return api

    def ensure_repo(self, repository: str) -> None:
        """Create a public dataset repository if it does not exist.

        Args:
            repository: Server-generated "<namespace>/<name>" id.
        """
        self.check_namespace(repository)
        api = self.client()
        self.call(api.create_repo, repository, repo_type="dataset", private=False, exist_ok=True)

    def commit(self, repository: str, files: dict[str, bytes | Path], message: str) -> str:
        """Upload several files in one commit, so readers never see a mismatched JSON/ZIP pair.

        Args:
            repository: Server-generated dataset id.
            files: Repository path -> content (bytes) or local file path.
            message: Commit summary.
        Returns:
            Commit id.
        """
        self.check_namespace(repository)
        api = self.client()
        operations = [CommitOperationAdd(path_in_repo=path, path_or_fileobj=content) for path, content in files.items()]
        info = self.call(api.create_commit, repository, operations, commit_message=message, repo_type="dataset")
        return info.oid

    def check_namespace(self, repository: str) -> None:
        """Refuse any destination outside the configured namespace."""
        owner, _, name = repository.partition("/")
        if owner != self.namespace or not name.startswith("mobilebench-") or "/" in name:
            raise HubError("refusing repository outside the relay namespace/prefix")

    def call(self, function, *args, **kwargs):
        """Run a Hub call, converting exceptions into sanitized HubError values.

        Args:
            function: HfApi method.
            args: Positional arguments.
            kwargs: Keyword arguments.
        Returns:
            The function result.
        """
        try:
            return function(*args, **kwargs)
        except HfHubHTTPError as error:
            status = error.response.status_code if error.response is not None else None
            retry_after = None
            if error.response is not None and error.response.headers.get("retry-after", "").isdigit():
                retry_after = float(error.response.headers["retry-after"])
            raise HubError(f"Hub HTTP {status}", status=status, retry_after_s=retry_after,
                           credential=status in (401, 403)) from None
        except (httpx2.HTTPError, OSError, TimeoutError) as error:
            raise HubError(f"Hub transport error ({type(error).__name__})") from None
