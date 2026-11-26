---
name: finance-tracker
description: 使用 Finance Tracker 的 ft CLI 查询或操作个人账本。适用于查看账户余额、汇总收支、记账和转账、管理工作区与分类，以及准备、核对、确认或恢复现金账单导入。
---

# 使用 ft 操作账本

## 连接服务与选择工作区

先运行 `ft --version`。如未安装，在 Node.js 26.2.0 或更高版本的环境中，从项目 **Ledger CLI** 工作流的成功运行下载 artifact，解压后安装其中的 npm 包。以下命令假设当前目录中是 0.1.0 版本。[CLI 构建产物](https://github.com/ssttkkl/finance-tracker/actions/workflows/ledger-cli.yml)

```sh
npm install --global ./finance-tracker-cli-0.1.0.tgz
ft --help
```

将 `FT_CLI_SERVER` 设为用户的服务地址，也可以逐次传 `--server URL`。远端使用 HTTPS，本机环回地址可用 HTTP。先执行 `ft auth status --json`；需要登录时，通过标准输入提供密码，例如从用户指定的凭据文件读取。已有会话可直接选择工作区。

```sh
ft auth login --email "$EMAIL" --password-stdin --json < "$PASSWORD_FILE"
ft workspace list --json
ft workspace use "$WORKSPACE_ID" --json
ft account list --workspace "$WORKSPACE_ID" --json
```

从返回值选择工作区和账户的 ID。后续命令带上 `--workspace "$WORKSPACE_ID"`，防止同一会话在另一进程切换工作区后写错账本。macOS 默认使用登录钥匙串；无交互环境可由密钥管理器为各次调用注入同一 `FT_CLI_STORAGE_KEY`（32 字节密钥的 Base64）。跨进程恢复需沿用原服务、身份和状态目录（默认 `~/.finance-tracker-cli`）。

## 查账与记账

脚本使用 `--json`，同时检查退出码。下例查询上海时区的 11 月收支、列出最近的记录，并按列表返回的 `page.items[].projection_id` 查看一笔收支和账单来源。

```sh
ft cash summary --workspace "$WORKSPACE_ID" --timezone Asia/Shanghai \
  --date-from 2026-11-01 --date-to 2026-11-30 --json
ft cash list --workspace "$WORKSPACE_ID" --limit 20 --json
ft cash show "$PROJECTION_ID" --workspace "$WORKSPACE_ID" --json
ft account show "$ACCOUNT_ID" --workspace "$WORKSPACE_ID" --json
```

续页使用原筛选条件和返回的 `page.next_cursor`，传 `--cursor "$CURSOR"`。汇总和余额按币种读取。返回 `offline: true` 时，用 `synced_at` 说明数据截至何时。明确的权限错误按错误处理。

用户要求保存一笔收入时，用已选账户 ID、原币种、十进制金额文本和带时区的发生时间。例如收到一笔 35 元报销款：

```sh
ft cash add --workspace "$WORKSPACE_ID" --account "$ACCOUNT_ID" \
  --amount 35.00 --currency CNY --type income --subtype not_applicable \
  --occurred-at '2026-11-12T12:00:00+08:00' --note '报销' --json
```

| 用户任务 | CLI 入口 |
|---|---|
| 账户间转账 | `ft cash transfer`。指定转出和转入账户、两端币种、日期、时间；跨币种时提供实际到账金额 `--to-amount` |
| 编辑、分类或删除收支 | `ft cash --help`。编辑先生成计划，按该计划及核对版本提交；批量分类和删除使用当前账本版本 |
| 查看或调整分类 | `ft category --help`。删除前用 `ft category impact ID` 查看引用影响 |
| 创建账户、管理工作区成员或邀请 | `ft account --help`、`ft workspace --help`。财务账户用账户 ID，成员操作用用户 ID |

## 导入现金账单

支持多文件一起核对。CSV、XLSX 在本地解析，PDF、旧 XLS 需要联网解析。有密码的 PDF 单文件传入，并用 `--password-stdin` 提供密码。

1. **准备文件。** `ft import prepare "$FILE" --workspace "$WORKSPACE_ID" --json` 返回 `batch_id`、批次 `revision` 和 `source_accounts`。保留批次 ID，检查每个文件的结果。
2. **选择对应账户。** 从 `source_accounts[].source_account_ref` 取来源引用，从 `ft account show` 取目标账户的 `account.account_id` 和 `account.account_revision`。逐项映射。第一项用准备结果的 `revision`，下一项使用上一次映射返回的 `review_revision`。
3. **核对整批。** 查看 `review` 的 `can_commit`、`review_revision`，用 `items --status pending` 查看待处理项。只要求预览时在此返回预计结果。用户要求入账且本次核对可以提交时，用当前版本确认整批，随后读取实际结果。修改选择后重新核对。

```sh
ft import map "$BATCH_ID" --workspace "$WORKSPACE_ID" --revision "$BATCH_REV" \
  --source-account "$SOURCE_REF" --account "$ACCOUNT_ID" --account-revision "$ACCOUNT_REV" --json
ft import review "$BATCH_ID" --workspace "$WORKSPACE_ID" --json
ft import items "$BATCH_ID" --workspace "$WORKSPACE_ID" --status pending --json
ft import confirm "$BATCH_ID" --workspace "$WORKSPACE_ID" --review "$REVIEW_REV" --json
ft import result "$BATCH_ID" --workspace "$WORKSPACE_ID" --kind row --json
```

核对分页仅影响显示范围。导入期间新建账户或补币种会单独生效，需要撤回时查看 `ft import --help` 中的 `account undo` 和 `cancel`。结束时根据实际结果报告已保存、草稿和未导入原因。投资账单不在此命令的支持范围内。

## 中断后继续

写入期间断线、进程退出，或错误返回 `error.commit_state = "unknown"` 时，沿原操作核实，再判断是否入账。

- **记账、转账或维护。** 用 `ft cash pending --json` 找回原操作，或使用错误返回的 `error.operation_id`，再执行 `ft cash resume "$OPERATION_ID" --workspace "$WORKSPACE_ID" --json`。
- **现金导入。** 用 `ft import batches --json` 找回原批次，再执行 `ft import resume "$BATCH_ID" --workspace "$WORKSPACE_ID" --json`。结果仍待核实时保留该 ID；重新创建一笔记账或开启新批次可能造成重复写入。
