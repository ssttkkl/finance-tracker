## Why

微信支付和支付宝账单导出共享申请、用户认证、异步生成、下载和保存等顶层状态，但应用内入口、参数和消息路径不同。分别维护独立 Skill 会重复通用契约，也不利于后续增加其他支付渠道。

原支付宝方案还把业务点击路径放进“后端”文件，使渠道业务知识与 Computer Use 控制技术混在一起。正确边界应是：渠道流程描述应用内完整业务操作，控制后端只描述如何操作设备界面。

## What Changes

- 用 `.agents/skills/bill-export/` 替换独立的 `.agents/skills/alipay-bill/`。
- 在主 `SKILL.md` 中按微信支付、支付宝等账单来源路由，并定义通用状态、安全边界和多渠道执行规则。
- 新增 `references/wechat.md` 和 `references/alipay.md`，分别承载完整的应用内业务流程。
- 新增 `references/computer-use-iphone-mirroring.md`，只承载 Computer Use + iPhone 镜像的通用控制方式。
- 更新技能元数据、README 和领域词表。

## Scope and Non-Goals

范围包括 Skill、渠道流程、当前控制后端、技能展示元数据、README、领域词表和本 change 记录。

非目标包括实现 Android + ADB、导入或解析账单文件、修改财务账本运行时代码、保留旧 Skill 名称兼容入口、替用户完成身份认证或未经授权转交文件。

## Acceptance Criteria

- 目录和 frontmatter 名称为 `bill-export`，旧 `alipay-bill` 不再存在。
- 主 Skill 能路由微信支付和支付宝，并允许一次请求包含多个渠道。
- `wechat.md` 和 `alipay.md` 各自包含从打开应用到申请、等待、下载、保存和恢复的完整业务流程。
- `computer-use-iphone-mirroring.md` 不包含微信或支付宝的应用内点击路径，只描述通用界面控制能力。
- 新增渠道只需增加渠道文件；新增设备控制方式只需增加后端文件。
- Skill、OpenSpec、YAML 和空白检查通过。
