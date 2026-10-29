---
name: ui-reuse-review
description: 检查前端 UI 是否正确复用已有组件、主题 token 和统一能力，识别重复业务语义、公共/局部组件归属错误和布尔参数过多的万能组件。用于 UI 变更审查或明确发起的复用度评估。
---

# UI 复用度审查

## 目的

判断已有能力是否被正确使用，以及新出现的共性是否值得下沉。目标不是复用率越高越好，而是相同变化只需要改一处，同时保留合理差异。

检查项见 `docs/design-review/frontend/rules.md`。

## 工作方式

- 适用：UI 变更涉及组件、布局、主题 token 使用或用户可见交互时执行；纯文案等无结构变化可在 `tasks.md` 说明不适用。
- 先查已有能力：`docs/shared/design-system/registry.yaml`、`docs/shared/ui-design-rules.md`、`compose/shared/src/commonMain/kotlin/com/finance/tracker/presentation/` 和 `core/design/`。
- 增量：从本次 diff 识别新增或修改的页面、组件和 token 使用；搜索现有组件与同类业务 UI；只关注本次引入或加重的问题，历史问题单独标记。
- 全量：建立“用户任务/业务语义 → 页面/组件 → 公共层/局部层 → token”的映射，找出重复维护、归属错误和过度抽象。
- 由独立上下文执行，评审者不修改实现；不重复 Hallmark 的视觉、对比度、间距、动效和响应式检查。

## 输出

自由格式。写清审查范围、基线、证据、发现、建议改法和未覆盖项。需要记录时写入 `tasks.md` 第 5 阶段或 `design.md`；不要求固定报告文件、等级或状态。
