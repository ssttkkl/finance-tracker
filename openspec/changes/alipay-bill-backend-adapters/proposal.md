## Why

现有支付宝交易流水证明 Skill 将 iPhone 镜像和 Computer Use 操作写在主文件中，导致业务流程与执行手段耦合。后续引入 Android + ADB 或其他设备时，容易复制并分叉状态检查、安全边界和交付口径。

本次将 Skill 统一命名为 `alipay-bill`，把交易流水证明定义为唯一导出对象，并将设备操作下沉到独立适配文件。核心流程只负责业务状态、后端选择、状态检查、安全边界和交付报告。

## What Changes

- 将 `.agents/skills/alipay-trade-proof-airdrop/` 重命名为 `.agents/skills/alipay-bill/`。
- 重写 `SKILL.md`，只保留平台无关的 `alipay-bill` 业务流程和后端选择契约。
- 将当前 iOS + Computer Use 操作移到 `references/ios-computer-use.md`。
- 更新 `agents/openai.yaml` 的展示名称、调用示例和 `$alipay-bill` 入口。
- 更新 `DOMAIN_GLOSSARY.md`，固定交易流水证明、导出后端、设备适配文件和认证接管等术语。

## Scope and Non-Goals

范围包括 Skill 文件、适配参考文件、Skill 展示元数据、领域词表和本 change 记录。

非目标包括：实现 Android + ADB 后端、修改支付宝产品行为、导入或解析账单文件、修改财务账本代码、增加运行时依赖，以及保留旧 Skill 名称兼容别名。

“微信账单”措辞按已确认上下文处理为平台无关核心流程；当前业务对象仍是支付宝「交易流水证明」，因此核心入口继续使用 `alipay-bill`，平台细节只写入支付宝 iOS 适配文件。

## Acceptance Criteria

- 目录和 frontmatter 名称为 `alipay-bill`，旧名称不再存在于项目 Skill 目录。
- 主 `SKILL.md` 不包含 iPhone 页面点击、AirDrop 或文件保存页等平台操作步骤，只引用适配文件并定义后端选择和业务状态。
- `references/ios-computer-use.md` 包含当前 iPhone 镜像实现、认证接管、生成等待、下载、保存、AirDrop 和恢复检查。
- 文档明确新增 Android + ADB 时只需增加适配文件，不复制核心流程。
- Skill 校验和空白检查通过；不执行提交、推送、PR、合并或部署。
