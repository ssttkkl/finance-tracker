## Why

Chrome 实测 3,000 行账单在选文件时形成 519 毫秒 CSV 和 1,148 毫秒 XLSX 主线程长任务，影响绘制及输入。

## What Changes

将 Compose Wasm 有界文件读取、端侧格式解析、归一和捕获编码迁至现有导入 Worker。Worker 故障通过现有单文件入口重试，保留同批其他文件。按原字节识别 PDF 和旧 XLS 并继续使用原解析接口。预览回包缩减由后续独立变更承载。

## Capabilities

### Modified Capabilities

- `import/local-import-pipeline` 增加 Web 本地解析后台执行与单文件恢复场景。

## Impact

影响 Wasm 导入、共享文件捕获与 Worker 消息。沿用原缓存格式及服务端接口。可回滚到原页面端读取和计算。
