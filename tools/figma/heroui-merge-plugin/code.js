// 将 Figma 文件中的每个页面合并到一个新页面，供 Pen 单页导入。
(async () => {
  await figma.loadAllPagesAsync();

  const sourcePages = figma.root.children.slice();
  const outputName = "HeroUI All (Merged)";

  if (sourcePages.length === 0) {
    figma.notify("没有可合并的页面");
    figma.closePlugin();
    return;
  }

  const outputPage = figma.createPage();
  outputPage.name = outputName;

  let cursorX = 0;
  let cursorY = 0;
  let rowHeight = 0;
  const gap = 160;
  const maxRowWidth = 8000;

  for (const sourcePage of sourcePages) {
    // 使用 Frame，兼容不同 Figma 版本和 Pen 的 Figma 导入器。
    const nodes = sourcePage.children.slice();
    const frame = figma.createFrame();
    frame.name = sourcePage.name;
    frame.x = cursorX;
    frame.y = cursorY;
    frame.layoutMode = "NONE";
    frame.clipsContent = false;
    frame.fills = [];

    if (nodes.length > 0) {
      const minX = Math.min(...nodes.map((node) => node.x));
      const minY = Math.min(...nodes.map((node) => node.y));
      const maxX = Math.max(...nodes.map((node) => node.x + node.width));
      const maxY = Math.max(...nodes.map((node) => node.y + node.height));
      frame.resizeWithoutConstraints(Math.max(maxX - minX, 320), Math.max(maxY - minY, 240));

      for (const sourceNode of nodes) {
        const clone = sourceNode.clone();
        frame.appendChild(clone);
        clone.x = sourceNode.x - minX;
        clone.y = sourceNode.y - minY;
      }
    } else {
      frame.resizeWithoutConstraints(320, 240);
    }

    rowHeight = Math.max(rowHeight, frame.height);
    cursorX += frame.width + gap;

    if (cursorX > maxRowWidth) {
      cursorX = 0;
      cursorY += rowHeight + gap;
      rowHeight = 0;
    }
  }

  figma.currentPage = outputPage;
  figma.viewport.scrollAndZoomIntoView(outputPage.children);
  figma.notify(`已合并 ${sourcePages.length} 个页面到「${outputName}」`);
  figma.closePlugin();
})();
