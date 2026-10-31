## 布局实现

只调整共享 `CashProjectionMonthHeader`。Compact 使用满宽 Column：月份为独立文本，后续复用 regular／wide 的币种横排，外层 Alignment.End。非 compact 继续月份与汇总分居左右。无合计时 compact 将“无收支”放在月份下一行右侧。保留现有间距、主题颜色、字号及等宽金额，不新增 token 或组件包装。

已在真实 Chromium 阅读 Material 3《Layout overview – Adaptive design》正文及《Start building with Material 3 Expressive》：采用按可用宽度重排元素方向、以分组与排版明确层级，保留金融信息密度。没有新增组件或实验 API。数字异常长的适配沿用当前字号及容器，不扩大本轮格式规则。

Cross-platform Impact Check：`commonMain` 改动影响 Web compact、Android Phone、iPhone；regular／wide 代码路径保持。导入与账本共用同一表头，关联搜索不显示收支合计。无后端／DTO／持久化／计算变化，双后端与财务回归不适用。
