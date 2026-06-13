# Git 事务型提交设计

## 背景

当前 `~/.ft/` 数据仓库采用每次写入自动 `git commit` 的策略。每次 `ft append`、`ft stock buy`、`ft add` 等操作都会产生一个 commit，导致 commit 历史碎、无法批量撤销、不便于人类审查改动。

## 目标

将自动 commit 改为**事务型**：每次写入只 stage（`git add -A`），不产生 commit。用户主动调用 `ft commit` 时才将累积的变更一次性提交。

## 改动范围

### 1. `src/ft/snapshot.py`

**函数更名**：`git_auto_commit` → `git_stage`

```python
def git_stage(repo_dir=None):
    """Stage all changes via git add -A, no commit."""
    # 保持 git_init_repo 逻辑（首次自动 git init）
    # 只做 git add -A，去掉 git commit
```

**新增函数**：`git_do_commit`

```python
def git_do_commit(msg: str = None, repo_dir=None):
    """Commit all staged changes.
    
    无 msg: commit -m "chore: YYYY-MM-DD HH:mm"
    有 msg: commit -m "<msg>"
    无变更时静默跳过（不使用 --allow-empty）
    """
```

**调用方替换**：所有 `git_auto_commit(...)` 改为 `git_stage(...)`

涉及的文件：
- `snapshot.py:save_snapshot()` → `git_stage(snapshot_path.parent)`
- `append.py:do_append()` → `git_stage(records_dir.parent)`
- `stock.py:stock_append()` → `git_stage()`
- `stock.py:do_buy/sell/deposit/withdraw/dividend/checkin()` — 这些通过 `save_snapshot()` 间接调用，无需改动

### 2. `src/ft/cli.py`

新增三个子命令：

```python
# commit
commit_p = sub.add_parser("commit", help="提交所有未提交的改动")
commit_p.add_argument("-m", "--message", help="自定义提交信息")

# status  
status_p = sub.add_parser("status", help="查看未提交的改动")

# reset
reset_p = sub.add_parser("reset", help="丢弃所有未提交改动")
```

### 3. 命令行为

| 命令 | git 操作 | 行为 |
|------|---------|------|
| `ft status` | `git status --short` | 列出变更文件（M/D/?? 标记），无变更时输出"无未提交改动" |
| `ft commit` | `git commit -m "chore: YYYY-MM-DD HH:mm"` | 无参=自动生成消息 |
| `ft commit -m "xxx"` | `git commit -m "xxx"` | 自定义消息 |
| `ft reset` | `git reset --hard HEAD` | 丢弃所有未提交变更，**执行前确认提示** |

### 4. 边界情况

- **空 stage**：`ft commit` 时无变更，提示"无待提交变更"并静默退出
- **reset 确认**：输出当前未提交的文件列表，提示 `确定要丢弃以上 X 个文件的改动？(y/N)`，只有用户输入 y/Y 才执行
- **git 不可用**：所有 git 操作保持现有异常静默捕获逻辑
- **首次初始化**：`git_stage` 保留 `git_init_repo`（自动 git init + 首次 init commit），确保仓库始终存在

## 测试

- `snapshot.py`：mock 验证 `git_stage` 调用了 `git add -A`，不调用 `git commit`
- `snapshot.py`：验证 `git_do_commit` 调用了 `git commit` 且消息格式正确
- `cli.py`：验证三个子命令的解析和分派

## 依赖

无新增依赖。
