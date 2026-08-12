"""本机 FastAPI 应用装配与来源校验。"""
from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlparse

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.engine import make_url

from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.runtime import StorageError, storage_error
from ft.application.web_queries import CashLedgerQueryService
from ft.application.cash_ledger import CashLedgerCommandService
from ft.config import StorageSettings
from ft.web.serialization import error_payload


DEFAULT_WEB_ORIGIN = "http://127.0.0.1:5173"
LOCAL_WEB_ORIGIN_REGEX = r"^http://(?:127\.0\.0\.1|localhost):[0-9]+$"
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


def create_app(
    service,
    allowed_origin: str = DEFAULT_WEB_ORIGIN,
    lifespan=None,
    mutation_service=None,
    *,
    investment_service=None,
    portfolio_service=None,
    portfolio_refresh=None,
) -> FastAPI:
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
        # The local Vite/preview port may move when another process occupies the
        # default port. Keep the trust boundary local while allowing that port
        # change without making the user manually restart the API.
        allow_origin_regex=LOCAL_WEB_ORIGIN_REGEX,
        allow_credentials=False,
        allow_methods=["GET", "POST", "PUT", "DELETE"],
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

    app.include_router(cash_router(
        service,
        mutation_service=mutation_service,
        investment_service=investment_service,
        portfolio_service=portfolio_service,
        portfolio_refresh=portfolio_refresh,
    ))
    return app


def create_runtime_app():
    from ft.adapters.relational import create_relational_engine, create_session_factory
    from ft.adapters.relational.dialect import RelationalEngineError
    from ft.adapters.relational.runtime import StorageError, validate_runtime
    from ft.config import StorageConfigurationError

    engine = None
    try:
        settings = StorageSettings.load()
        selected_url = make_url(settings.database_url)
        if selected_url.get_backend_name() == "sqlite" and selected_url.database not in {None, ":memory:"}:
            selected_path = Path(selected_url.database)
            if selected_path.exists() and selected_path.stat().st_size == 0:
                raise StorageError("storage.schema", settings.database_url)
        engine = create_relational_engine(settings.database_url)
        validate_runtime(engine, settings.workspace_id, settings.database_url)
        origin = validate_local_origin(__import__("os").environ.get("FT_WEB_ORIGIN", DEFAULT_WEB_ORIGIN))
        sessions = create_session_factory(engine)
        service = CashLedgerQueryService(sessions, settings.workspace_id)
        from ft.adapters.fx_rates import FxRateProvider
        from ft.adapters.market_data import CompositeQuoteProvider
        from ft.adapters.relational.queries import RelationalPortfolioRepository
        from ft.application.investment import PortfolioQueryService
        from ft.application.portfolio_refresh import PortfolioRefreshCoordinator
        from ft.application.investment_web_queries import InvestmentLedgerQueryService
        from ft.application.valuation import ValuationService
        from ft.application.relations import RelationService
        from ft.adapters.relational.uow import RelationalUnitOfWork
        write_uow = RelationalUnitOfWork(sessions, settings.workspace_id)
        mutation_service = CashLedgerCommandService(
            sessions, settings.workspace_id,
            relation_service=RelationService(write_uow),
        )
        quote_provider = CompositeQuoteProvider()
        investment_service = InvestmentLedgerQueryService(sessions, settings.workspace_id)
        portfolio_service = PortfolioQueryService(
            RelationalPortfolioRepository(sessions, settings.workspace_id),
            ValuationService(quote_provider),
            fx_rates=FxRateProvider(),
            query_deadline_seconds=None,
        )
        portfolio_refresh = PortfolioRefreshCoordinator(portfolio_service)

        @asynccontextmanager
        async def release_engine(_app):
            portfolio_refresh.start()
            try:
                yield
            finally:
                portfolio_refresh.stop()
                engine.dispose()

        app = create_app(
            service,
            origin,
            lifespan=release_engine,
            mutation_service=mutation_service,
            investment_service=investment_service,
            portfolio_service=portfolio_service,
            portfolio_refresh=portfolio_refresh,
        )
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
