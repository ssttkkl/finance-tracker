## Why

在手机上使用生产 Web 页面时，使用者明确希望页面不响应双指缩放，保持固定的初始视口比例。当前入口只设置了初始比例，浏览器仍允许用户手势缩放，导致页面在移动设备上出现可缩放体验。

## What Changes

- 为生产 Web 入口声明禁止用户缩放的 viewport 参数。
- 保持现有移动端布局、字号、表单行为和宽表格容器滚动不变。
- 不修改 OpenSpec 原型页面或其他独立文档页面。
- 接受禁用手势缩放对依赖页面放大的使用者可访问性的影响，因为这是本次明确的产品要求。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `cash-ledger-browser`: 移动端 Web 浏览必须禁止用户通过 viewport 手势缩放页面。

## Impact

- 受影响文件：`web/index.html` 及本 change 的 OpenSpec 记录。
- 不涉及 API、数据库、依赖、财务计算、持久化或路由。
- 回滚方式：移除新增的 viewport 禁止缩放参数，恢复仅设置设备宽度和初始比例的声明。
