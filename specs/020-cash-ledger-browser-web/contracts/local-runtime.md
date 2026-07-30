# 本地双进程运行合同

## Python API

`ft web` 读取 `FT_DATABASE_URL`、`FT_WORKSPACE_ID` 和可选 `FT_WEB_ORIGIN`。它只在 `127.0.0.1`
绑定 API，并输出 API 地址与允许的前端来源。数据库、schema、工作区或来源配置无效时，命令以非零
退出码失败，且不尝试其他后端。投影尚未初始化时 API 仍可启动，由投影端点返回
`projection.unavailable`，使前端能够展示明确状态；投影 schema 缺失或损坏仍属于启动失败。

选择文件型 SQLite 时，目标文件必须已经存在。Web 运行时继续使用既有只读快照连接，不创建数据库、
不切换 journal mode，也不生成 WAL、SHM 或其他持久化旁路文件；投影重建必须通过单独的写命令完成。
选择 PostgreSQL 时继续使用服务端权限作为只读边界，不自动改用 SQLite。

`FT_WEB_ORIGIN` 缺省为 `http://127.0.0.1:5173`。它只能是 `http://127.0.0.1:<port>` 或
`http://localhost:<port>`，不允许凭据、通配符、局域网地址或 HTTPS 降级替代。

## 投影维护命令

`ft projections rebuild` 和 `ft projections status` 使用与其他写命令相同的显式数据库配置。它们不由
`ft web` 或 Node 前端隐式调用。详细合同见 [projection-cli.md](projection-cli.md)。

## Node 前端

`web/` 提供：

- `npm run dev`：开发模式启动前端并输出页面地址。
- `VITE_FT_API_ORIGIN='http://127.0.0.1:<api-port>' npm run build`：构建时注入唯一 API 来源。
- `npm run start`：在 `127.0.0.1:5173` 预览既有构建产物，端口被占用时失败。

`VITE_FT_API_ORIGIN` 必须指向 Python API 的 `http://127.0.0.1:<port>` 或
`http://localhost:<port>` 地址。缺失、格式不符或无法连接时，前端显示可操作错误，不能静默使用其他
地址。列表收到 `projection.updated` 时保留筛选并刷新第一页；收到 `projection.unavailable` 时显示
投影不可用状态，不请求原始现金流水端点。

两个进程由使用者显式分别启动。本 feature 不下载 Node、安装依赖、启动子进程、托管后台进程或提供
云端部署配置。
