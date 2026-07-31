"""本机 FastAPI 应用装配与来源校验。"""
from __future__ import annotations

from contextlib import asynccontextmanager
from urllib.parse import urlparse

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError

from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.runtime import StorageError, storage_error
from ft.application.web_queries import CashLedgerQueryService
from ft.config import StorageSettings
from ft.web.serialization import error_payload


DEFAULT_WEB_ORIGIN = "http://127.0.0.1:5173"
_STORAGE_ERROR_CODES = frozenset({
    "storage.config",
    "storage.connect",
    "storage.schema",
    "storage.workspace",
    "storage.readonly",
    "storage.busy",
})
_STORAGE_ERROR_MESSAGES = {
    "storage.busy": "账本正被其他操作占用，请稍后重试。",
    "storage.readonly": "账本当前不可读取，请检查本机 API 配置后重试。",
    "storage.connect": "无法连接本机账本，请检查 API 和数据库连接后重试。",
    "storage.schema": "账本结构不可用，请检查本机 API 配置后重试。",
    "storage.workspace": "当前工作区不可用，请检查本机 API 配置后重试。",
    "storage.config": "账本配置无效，请检查本机 API 配置后重试。",
}


def validate_local_origin(origin: str) -> str:
    try:
        parsed = urlparse(origin)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Web 来源必须是带端口的本机 HTTP 地址。") from exc
    if (
        parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}
        or port is None or parsed.username or parsed.password or parsed.path not in {"", "/"}
        or parsed.query or parsed.fragment
    ):
        raise ValueError("Web 来源必须是带端口的本机 HTTP 地址。")
    return origin.rstrip("/")


def create_app(service, allowed_origin: str = DEFAULT_WEB_ORIGIN, lifespan=None) -> FastAPI:
    from ft.web.routes import cash_router

    allowed_origin = validate_local_origin(allowed_origin)
    app = FastAPI(
        title="Finance Tracker 本机账本浏览器",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[allowed_origin],
        allow_credentials=False,
        allow_methods=["GET"],
        allow_headers=["Accept", "Content-Type"],
    )

    @app.exception_handler(StorageError)
    def storage_failure(_request, exc: StorageError):
        code = exc.code if exc.code in _STORAGE_ERROR_CODES else "storage.connect"
        return JSONResponse(error_payload(code, _STORAGE_ERROR_MESSAGES[code]), status_code=503)

    @app.exception_handler(SQLAlchemyError)
    def sqlalchemy_failure(request, exc: SQLAlchemyError):
        return storage_failure(request, storage_error(exc, ""))

    @app.exception_handler(RelationalEngineError)
    def engine_failure(request, exc: RelationalEngineError):
        return storage_failure(request, StorageError(exc.code))

    app.include_router(cash_router(service))
    return app


def create_runtime_app():
    from ft.adapters.relational import create_session_factory, create_web_readonly_engine
    from ft.adapters.relational.dialect import RelationalEngineError
    from ft.adapters.relational.runtime import StorageError, validate_runtime
    from ft.config import StorageConfigurationError

    engine = None
    try:
        settings = StorageSettings.load()
        engine = create_web_readonly_engine(settings.database_url)
        validate_runtime(engine, settings.workspace_id, settings.database_url)
        origin = validate_local_origin(__import__("os").environ.get("FT_WEB_ORIGIN", DEFAULT_WEB_ORIGIN))
        service = CashLedgerQueryService(create_session_factory(engine), settings.workspace_id)

        @asynccontextmanager
        async def release_engine(_app):
            try:
                yield
            finally:
                engine.dispose()

        app = create_app(service, origin, lifespan=release_engine)
    except StorageConfigurationError as exc:
        raise StorageError("storage.config") from exc
    except RelationalEngineError as exc:
        if engine is not None:
            try:
                engine.dispose()
            except Exception as cleanup_error:
                raise StorageError("storage.connect") from cleanup_error
        raise StorageError(exc.code, settings.database_url if "settings" in locals() else None) from exc
    except Exception:
        if engine is not None:
            try:
                engine.dispose()
            except Exception as exc:
                raise StorageError("storage.connect") from exc
        raise
    return app
