"""Registration must reject oversized streams before buffering unbounded request data."""

import asyncio

import pytest
from starlette.requests import Request

from server.api import (
    ApiError,
    read_json,
)


def test_chunked_registration_stops_reading_at_limit():
    """Stop receiving after the first chunk that would cross the registration limit."""
    calls = 0

    async def receive():
        nonlocal calls
        calls += 1
        assert calls <= 3, "The server read beyond its JSON body bound."
        return {"type": "http.request", "body": b"x" * 8192, "more_body": True}

    # 1. Supply a stream with no Content-Length and no end-of-body marker.
    request = Request({"type": "http", "method": "POST", "path": "/v1/sessions", "headers": []}, receive)
    with pytest.raises(ApiError) as failure:
        asyncio.run(read_json(request))

    # 2. The third chunk crosses 16 KiB; no further read is allowed.
    assert failure.value.status == 413
    assert calls == 3


def test_declared_oversize_registration_never_reads_body():
    """Reject a declared oversized body before any request-body allocation."""
    async def receive():
        pytest.fail("An oversized declared body must not be read.")

    request = Request({"type": "http", "method": "POST", "path": "/v1/sessions",
                       "headers": [(b"content-length", b"1000000000")]}, receive)
    with pytest.raises(ApiError) as failure:
        asyncio.run(read_json(request))
    assert failure.value.status == 413
