"""解析会话、计划和重试共用的安全错误，不提供原文件存储。"""


class ImportSessionError(ValueError):
    """临时导入内容无效或暂不可用。"""


class ImportSessionNotFound(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_not_found")


class ImportSessionForbidden(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_forbidden")


class ImportSessionExpired(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_expired")


class ImportSessionStorageUnavailable(ImportSessionError):
    def __init__(self) -> None:
        super().__init__("import_session_storage_unavailable")
