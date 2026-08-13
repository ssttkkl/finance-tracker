"""本机 FastAPI 应用装配与来源校验。"""
from __future__ import annotations

from contextlib import asynccontextmanager
from contextvars import ContextVar
from pathlib import Path
from urllib.parse import urlparse

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.engine import make_url
from sqlalchemy import inspect, text

from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.runtime import StorageError, storage_error
from ft.application.web_queries import CashLedgerQueryService
from ft.application.cash_ledger import CashLedgerCommandService
from ft.config import StorageSettings
from ft.web.serialization import error_payload
from ft.application.access import AccessService, AuthenticationRequired, PermissionDenied, SESSION_COOKIE


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


def validate_web_origin(origin: str) -> str:
    try:
        parsed = urlparse(origin)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("Web 来源必须是本机 HTTP 地址或 HTTPS 地址。") from exc
    local_http = (
        parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"}
        and port is not None
    )
    hosted_https = parsed.scheme == "https" and parsed.hostname is not None
    if not (
        (local_http or hosted_https)
        and not parsed.username and not parsed.password and parsed.path in {"", "/"}
        and not parsed.query and not parsed.fragment
    ):
        raise ValueError("Web 来源必须是本机 HTTP 地址或 HTTPS 地址。")
    return origin.rstrip("/")


class WorkspaceServices:
    """Resolve legacy workspace-bound services from an authenticated request context."""
    def __init__(self, sessions, workspace_var):
        self._sessions = sessions; self._workspace_var = workspace_var
    def _workspace(self):
        workspace_id = self._workspace_var.get()
        if workspace_id is None: raise AuthenticationRequired("authentication_required")
        return workspace_id
    def __getattr__(self, name):
        workspace_id = self._workspace()
        query = CashLedgerQueryService(self._sessions, workspace_id)
        if hasattr(query, name): return getattr(query, name)
        from ft.application.relations import RelationService
        from ft.adapters.relational.uow import RelationalUnitOfWork
        command = CashLedgerCommandService(self._sessions, workspace_id, relation_service=RelationService(RelationalUnitOfWork(self._sessions, workspace_id)))
        if hasattr(command, name): return getattr(command, name)
        from ft.application.cash_categories import CashCategoryService
        category = CashCategoryService(self._sessions, workspace_id)
        if hasattr(category, name): return getattr(category, name)
        from ft.application.cash_classification import CashClassificationService
        classification = CashClassificationService(self._sessions, workspace_id)
        if hasattr(classification, name): return getattr(classification, name)
        raise AttributeError(name)


def create_app(
    service,
    allowed_origin: str = DEFAULT_WEB_ORIGIN,
    lifespan=None,
    mutation_service=None,
    *,
    category_service=None,
    classification_service=None,
    investment_service=None,
    portfolio_service=None,
    portfolio_refresh=None,
    access_service: AccessService | None = None,
    workspace_context=None,
    cookie_secure: bool | None = None,
) -> FastAPI:
    from ft.web.routes import cash_router

    allowed_origin = validate_web_origin(allowed_origin)
    if cookie_secure is None:
        cookie_secure = urlparse(allowed_origin).scheme == "https"
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
        allow_credentials=access_service is not None,
        allow_methods=["GET", "POST", "PUT", "DELETE"],
        allow_headers=["Accept", "Content-Type", "X-FT-Statement-Password"],
    )

    if access_service is not None:
        @app.middleware("http")
        async def authenticate_web_api(request, call_next):
            if request.url.path.startswith("/api/v1") and not request.url.path.startswith("/api/v1/auth"):
                try:
                    workspace_id, role = access_service.require(request.cookies.get(SESSION_COOKIE), {"admin", "editor", "viewer"})
                    if request.method not in {"GET", "HEAD", "OPTIONS"} and role == "viewer":
                        return JSONResponse(error_payload("workspace_forbidden", "当前角色仅可查看账本。"), 403)
                    request.state.workspace_id = workspace_id; request.state.workspace_role = role
                    context_token = workspace_context.set(workspace_id) if workspace_context is not None else None
                except AuthenticationRequired:
                    return JSONResponse(error_payload("authentication_required", "请先登录。"), 401)
                except PermissionDenied:
                    return JSONResponse(error_payload("workspace_forbidden", "当前用户无权访问该工作区。"), 403)
                try: return await call_next(request)
                finally:
                    if context_token is not None: workspace_context.reset(context_token)
            return await call_next(request)

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

    if access_service is not None:
        from ft.web.access_routes import access_router
        app.include_router(access_router(access_service, cookie_secure=cookie_secure))
    app.include_router(cash_router(
        service,
        mutation_service=mutation_service,
        category_service=category_service,
        classification_service=classification_service,
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
        settings = StorageSettings.load(require_workspace=False)
        selected_url = make_url(settings.database_url)
        if selected_url.get_backend_name() == "sqlite" and selected_url.database not in {None, ":memory:"}:
            selected_path = Path(selected_url.database)
            if selected_path.exists() and selected_path.stat().st_size == 0:
                raise StorageError("storage.schema", settings.database_url)
        engine = create_relational_engine(settings.database_url)
        tables = set(inspect(engine).get_table_names())
        if "alembic_version" not in tables:
            raise StorageError("storage.schema", settings.database_url)
        with engine.connect() as connection:
            revision = connection.scalar(text("SELECT version_num FROM alembic_version"))
        from ft.adapters.relational.runtime import SCHEMA_REVISION
        if revision != SCHEMA_REVISION:
            raise StorageError("storage.schema", settings.database_url)
        origin = validate_web_origin(__import__("os").environ.get("FT_WEB_ORIGIN", DEFAULT_WEB_ORIGIN))
        sessions = create_session_factory(engine)
        workspace_var = ContextVar("web_workspace_id", default=None)
        service = WorkspaceServices(sessions, workspace_var)
        access_service = AccessService(sessions)
        mutation_service = service
        category_service = service
        classification_service = service
        investment_service = service
        portfolio_service = None
        portfolio_refresh = None

        @asynccontextmanager
        async def release_engine(_app):
            if portfolio_refresh is not None:
                portfolio_refresh.start()
            try:
                yield
            finally:
                if portfolio_refresh is not None:
                    portfolio_refresh.stop()
                engine.dispose()

        app = create_app(
            service, origin, lifespan=release_engine, mutation_service=mutation_service,
            category_service=category_service, classification_service=classification_service,
            investment_service=investment_service,
            portfolio_service=portfolio_service,
            portfolio_refresh=portfolio_refresh,
            access_service=access_service,
            workspace_context=workspace_var,
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
