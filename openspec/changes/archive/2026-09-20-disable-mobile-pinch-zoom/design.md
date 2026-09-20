## Context

生产入口 `web/index.html` 已设置设备宽度和初始缩放比例，但没有声明用户缩放策略，因此移动浏览器仍可响应双指缩放。该变更只影响文档级 viewport 元数据，不涉及 React 页面布局、表单控件字号或运行时服务。

## Goals / Non-Goals

**Goals:**

- 让生产 Web 页面在支持该 viewport 策略的移动浏览器中保持 `1.0` 页面缩放比例。
- 将禁止缩放策略集中放在唯一的生产 HTML 入口，避免各页面路由重复实现。

**Non-Goals:**

- 不通过 JavaScript 拦截触摸事件。
- 不使用 `touch-action: none`，避免连页面滚动也被禁用。
- 不改变输入框字号、响应式断点、宽表格的容器内滚动或原型文件。

## Decisions

- 在生产入口的 viewport 内容中增加 `maximum-scale=1.0` 和 `user-scalable=no`。
  - 选择原因：这是移动浏览器识别页面缩放策略的标准文档入口，改动最小且不引入运行时副作用。
  - 备选方案：用 JavaScript 监听 `touchmove` 并阻止默认行为会干扰滚动、性能和辅助技术，因此不采用；用 `touch-action` 也不能稳定替代页面 viewport 缩放策略，因此不采用。
- 保留现有 `width=device-width, initial-scale=1.0`，使设备宽度和初始比例语义不变，只关闭后续用户缩放。

## Risks / Trade-offs

- [可访问性受影响] 依赖页面放大阅读的使用者无法通过双指放大 → 这是用户明确要求的产品取舍，并在变更记录中保留；部分浏览器可能基于辅助功能设置忽略禁止缩放。
- [浏览器差异] 不同移动浏览器对 `user-scalable=no` 的遵循程度不同 → 使用 `maximum-scale=1.0` 与 `user-scalable=no` 双重声明，并在可用的真实移动浏览器或触控浏览器中验证入口元数据。

## Migration Plan

部署前确认构建产物仍包含更新后的 viewport 内容；回滚时移除 `maximum-scale=1.0` 和 `user-scalable=no`，恢复原 viewport 声明。无数据库、API 或数据迁移。
