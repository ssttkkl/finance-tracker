# Git 事务型提交 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `~/.ft/` 仓库的自动 commit 改为手动提交，新增 status/commit/reset 命令

**Architecture:** 修改 `snapshot.py` 中的 git 辅助函数，将 `git_auto_commit` 拆分为 stage-only 和 commit-only 两个函数；更新 `append.py`、`stock.py`、`cli.py` 中的调用点；新增三个 CLI 子命令

**Tech Stack:** Python 3.11, subprocess, argparse

---

### Task 1: 修改 snapshot.py — git_stage + git_do_commit

**Files:**
- Modify: `src/ft/snapshot.py:89-103`

- [ ] **Step 1: 将 `git_auto_commit` 改名为 `git_stage`，去掉 commit**

```python
def git_stage(repo_dir=None):
    """Stage all changes via git add -A, no commit."""
    if repo_dir is None:
        repo_dir = GIT_REPO
    repo_dir = Path(repo_dir)
    try:
        git_init_repo(repo_dir)
        subprocess.run(["git", "add", "-A"], cwd=str(repo_dir),
                       capture_output=True, timeout=10)
    except Exception:
        pass
```

- [ ] **Step 2: 新增 `git_do_commit` 函数**

在 `git_stage` 之后添加：

```python
def git_do_commit(msg: str = None, repo_dir=None):
    """Commit all staged changes. Returns True if committed, False if nothing to commit."""
    if repo_dir is None:
        repo_dir = GIT_REPO
    repo_dir = Path(repo_dir)
    try:
        git_init_repo(repo_dir)
        from datetime import datetime
        commit_msg = msg if msg else f"chore: {datetime.now().strftime('%Y-%m-%d %H:%M')}"
        result = subprocess.run(
            ["git", "commit", "-m", commit_msg],
            cwd=str(repo_dir), capture_output=True, timeout=10, text=True,
        )
        if result.returncode == 0:
            return True
        else:
            return False
    except Exception:
        return False
```

- [ ] **Step 3: 更新 `save_snapshot` 中的调用**

```python
def save_snapshot(data: dict, path: Optional[str] = None) -> None:
    ...
    with snapshot_path.open("w", encoding="utf-8") as f:
        yaml.dump(data, f, allow_unicode=True, default_flow_style=False)
    git_stage(snapshot_path.parent)
```

- [ ] **Step 4: 更新导出**

```python
from .snapshot import git_auto_commit, load_snapshot, save_snapshot
```
在 `stock.py` 的 import 中也需要更新（但该处使用 `from .snapshot import git_auto_commit, ...`，`git_auto_commit` 在 stock.py 中只用于 `stock_append()`，不影响其他导入）

- [ ] **Step 5: 运行 test_snapshot 验证**

```bash
cd ~/.hermes/skills/finance/finance-tracker
python3 -m pytest tests/test_snapshot.py -v
```
预期：3 passed

- [ ] **Step 6: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/snapshot.py
git commit -m "refactor: rename git_auto_commit to git_stage, add git_do_commit"
```

---

### Task 2: 更新调用方 — append.py + stock.py

**Files:**
- Modify: `src/ft/append.py:103-105`
- Modify: `src/ft/stock.py:13, 280-282`

- [ ] **Step 1: 更新 `append.py` 中的调用**

将：
```python
    from .snapshot import git_auto_commit
    git_auto_commit("append", records_dir.parent)
```
改为：
```python
    from .snapshot import git_stage
    git_stage(records_dir.parent)
```

- [ ] **Step 2: 更新 `stock.py` 中的导入**

将：
```python
from .snapshot import git_auto_commit, load_snapshot, save_snapshot
```
改为：
```python
from .snapshot import git_stage, load_snapshot, save_snapshot
```

- [ ] **Step 3: 更新 `stock.py:stock_append()` 中的调用**

将：
```python
        from .snapshot import git_auto_commit
        git_auto_commit("stock-append")
```
改为：
```python
        from .snapshot import git_stage
        git_stage()
```

- [ ] **Step 4: 运行全量测试确认无回归**

```bash
cd ~/.hermes/skills/finance/finance-tracker
python3 -m pytest tests/ -v --tb=short 2>&1 | tail -10
```
预期：269 passed, 1 skipped

- [ ] **Step 5: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/append.py src/ft/stock.py
git commit -m "refactor: replace git_auto_commit calls with git_stage"
```

---

### Task 3: 新增 CLI 子命令 — status / commit / reset

**Files:**
- Modify: `src/ft/cli.py`

- [ ] **Step 1: 在 parser 注册部分添加三个子命令**

在 `# verify` 之前或之后（保持字母排序）添加：

```python
    # commit
    commit_p = sub.add_parser("commit", help="提交所有未提交的改动")
    commit_p.add_argument("-m", "--message", help="自定义提交信息")

    # status
    sub.add_parser("status", help="查看未提交的改动")

    # reset
    reset_p = sub.add_parser("reset", help="丢弃所有未提交改动")
```

- [ ] **Step 2: 在 dispatch 部分添加命令处理**

在 `if args.cmd == "verify":` 之前添加：

```python
    if args.cmd == "commit":
        from .snapshot import git_do_commit
        committed = git_do_commit(args.message)
        if committed:
            print("✅ 已提交")
        else:
            print("📭 无待提交变更")
        return

    if args.cmd == "status":
        import subprocess
        from pathlib import Path
        from . import models
        repo_dir = models.FT_DIR
        result = subprocess.run(
            ["git", "status", "--short"],
            cwd=str(repo_dir), capture_output=True, timeout=10, text=True,
        )
        output = result.stdout.strip()
        if output:
            print(output)
        else:
            print("📭 无未提交改动")
        return

    if args.cmd == "reset":
        import subprocess
        from pathlib import Path
        from . import models
        repo_dir = models.FT_DIR
        # 先显示待丢弃的文件
        result = subprocess.run(
            ["git", "status", "--short"],
            cwd=str(repo_dir), capture_output=True, timeout=10, text=True,
        )
        output = result.stdout.strip()
        if not output:
            print("📭 无未提交改动，无需重置")
            return
        print("以下未提交改动将被丢弃：")
        print(output)
        confirm = input("确定要丢弃以上改动？(y/N): ")
        if confirm.lower() != "y":
            print("已取消")
            return
        subprocess.run(
            ["git", "reset", "--hard", "HEAD"],
            cwd=str(repo_dir), capture_output=True, timeout=10,
        )
        print("✅ 已重置到最近一次提交")
        return
```

- [ ] **Step 3: 运行全量测试确认无回归**

```bash
cd ~/.hermes/skills/finance/finance-tracker
python3 -m pytest tests/ -v --tb=short 2>&1 | tail -10
```
预期：269 passed, 1 skipped

- [ ] **Step 4: Commit**

```bash
cd ~/.hermes/skills/finance/finance-tracker
git add src/ft/cli.py
git commit -m "feat: add ft status / commit / reset commands"
```

---

### Task 4: End-to-End 验证

- [ ] **Step 1: 确认 status 正常**

```bash
cd ~/.ft
../../.hermes/skills/finance/finance-tracker/ft status
```
预期：显示未提交改动列表或"无未提交改动"

- [ ] **Step 2: 确认手动提交正常**

```bash
ft commit -m "测试手动提交"
ft status
```

- [ ] **Step 3: 确认 reset 正常**

```bash
# 先制造一个改动
echo "# test" >> records/cash/2026-06-13.csv
ft status  # 应看到改动
ft reset   # 应询问确认
ft status  # 应显示无改动
```
