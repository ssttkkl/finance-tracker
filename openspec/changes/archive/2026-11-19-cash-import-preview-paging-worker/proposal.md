## Why

3,000 行预览回包约 2,869 万字符，接收和显示附近仍让 Web 页面出现主线程长任务。

## What Changes

Compose Wasm 首次核对只返回摘要和首屏数据，详情及后续页按需获取。写集仅在明确确认时取得，继续沿用原核对、确认和恢复语义。

## Capabilities

### Modified Capabilities

- `import/local-import-pipeline` 增加 Web 预览的数据读取边界。

## Impact

影响 Web Worker、共享预览生命周期和分页调用。无需数据、API 或写入协议迁移。回滚可切回原整份预览传输。
