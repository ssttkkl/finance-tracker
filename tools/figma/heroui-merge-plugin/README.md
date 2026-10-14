# HeroUI Figma 页面合并插件

此插件只把当前 Figma 文件的多个页面复制到一个新页面，供可选视觉参考；它不会生成 Finance UI，也不再把页面导入 Pen。Figma Variables、component variants 和 slots 不会自动映射为 Compose 组件。

Finance UI 的上游参考是 HeroUI 官方代码、`@heroui/styles` 和 CSS variables；当前页面结构与样式由共享 Compose 实现维护，最终 QA 在 Compose 演示模式中进行。

## 使用

1. 在 Figma Desktop 打开 `HeroUI Figma Kit V3`。
2. 打开 **Plugins → Development → Import plugin from manifest…**。
3. 选择本目录中的 `manifest.json`。
4. 运行 **HeroUI Merge Pages**。
5. 等待 `HeroUI All (Merged)` 页面生成。原始页面不会被修改。

插件会为每个来源页面创建一个同名 Frame，并按网格排列。重复运行会生成新的合并页；不会覆盖已有页面。
