# HeroUI 页面合并插件（可选视觉校验）

Pen 的 Figma 导入器一次只能选择一个 page。这个本地插件会把当前 Figma 文件的所有页面复制到一个新页面 `HeroUI All (Merged)`，方便按页导入视觉参考。

本插件不是 HeroUI → Pen 的组件库转换器。Figma Variables、component variants 和 slots 不保证能映射为 Pen 的等价语义；Finance UI 的事实源仍是 HeroUI 官方代码、`@heroui/styles` 和 CSS variables。优先使用临时 HeroUI Gallery 的真实 DOM 做 Browser Import，本插件只用于可选视觉校验。

## 使用

1. 在 Figma Desktop 打开 `HeroUI Figma Kit V3`。
2. 打开 **Plugins → Development → Import plugin from manifest…**。
3. 选择本目录中的 `manifest.json`。
4. 运行 **HeroUI Merge Pages**。
5. 等待 `HeroUI All (Merged)` 页面生成。原始页面不会被修改。
6. 在 Pen 的 Figma 导入窗口选择 `HeroUI All (Merged)`。
7. 导入完成后，如需保存视觉校验资料，再保存为 `design/heroui-reference.lib.pen`；不要把它当作 Finance UI 或项目 token 的事实源。

插件会为每个来源页面创建一个同名 Frame，并按网格排列，便于在 Pen 中定位组件。重复运行会生成新的合并页；不会覆盖已有页面。
