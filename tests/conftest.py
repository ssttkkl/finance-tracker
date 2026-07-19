"""Shared relational test policy."""
from __future__ import annotations

import os
from urllib.parse import urlparse

import pytest


def require_test_postgres_url() -> str | None:
    """Return a safe dedicated PostgreSQL test URL or fail in required mode."""
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") != "1":
        return url
    if not url:
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    name = urlparse(url).path.rsplit("/", 1)[-1]
    if not name.endswith("_test"):
        pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
    return url
