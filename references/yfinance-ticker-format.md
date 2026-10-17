# 证券代码到行情 provider 的映射

内部账本证券代码由 `ledger_security_to_yfinance` 转换为行情 provider 代码。此映射属于本地领域适配合同；不要把它描述成所有 Web/API 行情查询都直接调用 Yahoo Finance。

| 账本代码形式 | Provider 代码 | 当前规则 |
|---|---|---|
| `NVDA.US` | `NVDA` | `.US` 去掉后缀 |
| `00700.HK` | `0700.HK` | 港股移除多余前导零，至少补齐为 4 位 |
| `600519.SH` | `600519.SS` | `.SH` 映射为 `.SS` |
| `159740.SZ` | `159740.SZ` | `.SZ` 转为大写后缀 |
| `XXXX.TW` / `XXXX.TWO` | `XXXX.TW` | 台湾市场后缀规范化 |
| `XXXX.JP` / `XXXX.T` | `XXXX.T` | 日本市场后缀规范化 |
| `XXXX.KR` / `XXXX.KS` | `XXXX.KS` | 韩国市场后缀规范化 |
| `XXXX.KQ` | `XXXX.KQ` | 保留 KOSDAQ 后缀 |
| 含字母的无后缀代码 | 大写裸代码 | 按美股代码处理 |

不支持的代码返回 unsupported quote；不要依赖 provider 返回错误来判定资产类别。货币、现金和预测市场身份由各自的领域分类处理，不走此证券代码映射。

映射实现见 [`valuation.py`](../src/ft/domain/valuation.py)，行情读取由 [`market_data.py`](../src/ft/adapters/market_data.py) 的 provider 负责。
