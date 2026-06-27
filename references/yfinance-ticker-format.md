# YFinance Ticker 格式指南

`ft stock list` 和独立实时价查询都用 yfinance，但 yfinance 的 ticker 格式跟常见的券商/行情软件**不一样**，写错就 404。

## 美股

| 正确 | 错误 | 说明 |
|------|------|------|
| `NVDA` | `NVDA.US` ❌ | 裸代码即可，不加 `.US` 后缀 |
| `AVGO` | `AVGO.US` ❌ | 同上 |
| `GOOGL` / `GOOG` | `GOOG.US` ❌ | 有双股结构的注意选对代码 |
| `MU` | `MU.US` ❌ | 同上 |
| `QLD` / `SMH` / `MRVL` | 加 `.US` ❌ | ETF 也一样 |

**规则：** 美股直接写裸 ticker，**不要**加 `.US` 后缀。加了 yfinance 会返回 404 "Quote not found"。

## 港股

| 正确 | 错误 | 说明 |
|------|------|------|
| `0700.HK` | `00700.HK` ❌ | 去掉前导 0，保留 HK 后缀 |
| `9988.HK` | `09988.HK` ❌ | 同上 |

**规则：** 港股用 `XXXX.HK` 格式（4位数），去掉前导 0。`00700.HK` → `0700.HK`。

## A 股

| 正确 | 错误 | 说明 |
|------|------|------|
| `159740.SZ` | `159740.sz` ❌ | 后缀要大写 |
| `600519.SS` | | SH 后缀是 `.SS` |

**规则：** `XXXXXX.SZ`（深交所）或 `XXXXXX.SS`（上交所），后缀**大写**。

## 汇率

| 代码 | 说明 |
|------|------|
| `HKDCNY=X` | 港币兑人民币 |
| `CNY=X` | 美元兑人民币（USD/CNY） |

## 常用字段

```python
import yfinance as yf
tk = yf.Ticker("NVDA")

# 方法1: fast_info（推荐，一个调用全拿到）
price = tk.fast_info.get('regular_market_price')
name = tk.fast_info.get('longName')

# 方法2: info dict（较慢但字段全）
price = tk.info.get('currentPrice')
name = tk.info.get('longName', tk.info.get('shortName'))
```

## 代理要求

yfinance 需要网络访问美股/港股行情。用户环境需要 HTTP 代理：
```bash
HTTPS_PROXY=http://127.0.0.1:7890 python3 -c "import yfinance; ..."
```
