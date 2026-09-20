export const platformDifferences = {
  navigationSurface: {
    web: "侧边导航在桌面、折叠菜单在窄屏",
    native: "大窗口使用 rail，窄窗口使用顶部菜单/返回导航",
    invariant: "导航层级、当前页面、工作区上下文和可用第一阶段入口一致",
  },
  recordCollection: {
    web: "桌面表格、窄屏卡片",
    native: "FlatList/触控记录卡片",
    invariant: "账户、金额、分类、来源、时间、对方和详情动作不丢失",
  },
  detailSurface: {
    web: "模态证据抽屉或嵌入式编辑抽屉",
    native: "原生导航页面或 sheet",
    invariant: "关闭/返回、保存、删除、关系区域和错误语义一致",
  },
  fileSelection: {
    web: "HTML file input / 拖入账单文件",
    native: "系统文件选择器",
    invariant: "取消不开始会话，文件名/类型/密码错误和扫描结果语义一致",
  },
  debugApiOriginOverride: {
    web: "不展示 Native 调试地址控件",
    native: "调试构建开启时在登录/注册表单展示后端地址控件",
    invariant: "关闭调试开关时不展示控件、不读取覆盖地址，认证结果和 Token 语义不变",
  },
  dateAndChoiceControls: {
    web: "HTML date/select/popover 控件",
    native: "Native picker、sheet 或可滚动选择列表",
    invariant: "选项顺序、初始值、标签、确认时机和提交值一致",
  },
} as const;
