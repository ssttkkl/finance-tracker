## 设计

失败发生在现有测试自定义点击助手的 `scrollIntoViewIfNeeded()`：页面已跳转到工作区管理页，鼠标移动后菜单仍可见，但 Compose 在 Playwright 滚动操作期间重建了退出按钮。仓库已有 `clickStableComposeTarget` 会等待目标几何连续稳定后直接以坐标单击，适合该交互且不需要生产代码改动。

修复仅替换此测试调用，不增加自动重试、固定延时或业务结果替代断言。验证先针对同一测试多次运行，再运行完整浏览器套件。此测试只触及共享 Web UI 测试层，Native 与共享业务层不受影响；Cross-platform Impact Check：Web 测试受影响，Native 与共享层不受影响。
