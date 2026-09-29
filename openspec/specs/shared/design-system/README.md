# 共享设计资源

`tokens/*.tokens.json` 保留颜色、字号、间距、圆角、触控目标和主题值，是现有 `scripts/generate_ui_tokens.py` 的唯一输入。
生成器产出 Compose Kotlin token 和 `design/generated/pen-variables.json`；本次仅迁移目录，token 值及生成逻辑不变。

页面、表单、组件、反馈位置、无障碍设计和状态画面由 [设计工作区](../../../../design/README.md) 中的 `.pen` 维护，业务行为见对应 OpenSpec。
此处不维护重复的页面或组件 YAML，也不新增 `spec.md`。`.pen` 变量暂时仍消费 JSON token，尚未切换为代码生成的输入。
