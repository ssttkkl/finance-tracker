# Noto Sans SC 字体

- 来源：Google Fonts 上游 `notosanssc` 目录中的 `NotoSansSC[wght].ttf`，<https://github.com/google/fonts/tree/main/ofl/notosanssc>。
- 许可：SIL Open Font License 1.1，见本目录的 `OFL-NotoSansSC.txt`。
- 处理：由原始可变 TTF 转换为 WOFF2，并裁剪为 ASCII、GB2312 字符集、U+3000–U+303F 标点和 Compose 静态界面/测试夹具中的实际字符；可用本目录的 `subset-noto-sans-sc.py` 重建。转换使用 fontTools 4.56.0 与 brotli 1.2.0。
- 首屏字体：`src/wasmJsMain/composeResources/font/noto_sans_sc.woff2`，2,028,440 字节，仅供 Web/Wasm 使用。
- 罕见字符回退：`compose/webApp/src/webMain/resources/fonts/notosanssc/v40/` 镜像 Compose Multiplatform 1.12.1 使用的 101 个 Noto Sans SC 子集，共 2,410,448 字节；`font-mirror.js` 将这些回退请求改写到站点同源路径，字体文件按字符需要加载。
- 站点随包带有相同的 OFL 许可文件：`compose/webApp/src/webMain/resources/fonts/notosanssc/OFL.txt`。Compose 升级若更改字体上游版本或路径，须同步更新回退镜像和 Chrome 回归用例。
