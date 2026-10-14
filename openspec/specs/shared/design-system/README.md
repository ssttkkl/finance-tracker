# 共享设计资源

`tokens/*.tokens.json` 保留颜色、字号、间距、圆角、触控目标和主题值，是 `scripts/generate_ui_tokens.py` 的唯一输入。
生成器只产出 Compose Kotlin token。历史 `design/generated/pen-variables.json` 保留作归档参考，不再更新或参与 CI 校验。

页面结构与样式由 Compose 实现维护，页面行为和验收见对应 OpenSpec。历史 `.pen` 文件位于 [设计资料目录](../../../../design/README.md)，不参与当前设计和验收。
此处不维护重复的页面或组件 YAML，也不新增 `spec.md`。Git token JSON 是唯一 token 输入，Compose Kotlin 是运行时消费副本。
