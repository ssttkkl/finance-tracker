## 构建边界

根因为 `prepareWebSentry` 和 `wasmJsProcessResources` 的延迟动作读取 Gradle Kotlin 脚本实例的属性。配置缓存无法保存该实例。故障可以在生产资源任务复现，不需要启停 Render 或数据库。

在任务配置中取得路径和输入值，保留 Gradle 输入和输出声明。资源过滤器只捕获构建版本和 DSN 字符串。配置缓存仍追踪环境和 Git 来源。开启和关闭 Sentry 均沿用原产物范围。回滚可恢复该构建脚本的前一版本。

本次是 C 类局部缺陷修复。数据库、后端、运行时 Web 页面和 Native 入口无变更。
