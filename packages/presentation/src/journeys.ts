import { semanticIds, type SemanticId } from "./semantic-ids";

export type JourneyStep = {
  action: string;
  target?: SemanticId;
  state?: string;
};

export const firstPhaseJourneys = {
  signInAndOpenLedger: {
    name: "登录并打开收支账本",
    steps: [
      { action: "填写邮箱", target: semanticIds.authEmail },
      { action: "填写密码", target: semanticIds.authPassword },
      { action: "提交登录", target: semanticIds.authSubmit },
      { action: "看到收支账本", target: semanticIds.ledgerScreen, state: "normal" },
    ],
  },
  createRecord: {
    name: "创建一笔现金流水",
    steps: [
      { action: "打开新建流水", target: semanticIds.ledgerAdd },
      { action: "填写金额", target: semanticIds.recordAmount },
      { action: "选择账户", target: semanticIds.recordAccount },
      { action: "保存流水", target: semanticIds.recordSave },
      { action: "回到账本", target: semanticIds.ledgerScreen, state: "success" },
    ],
  },
  importAndReviewRelations: {
    name: "导入账单并审查关系",
    steps: [
      { action: "打开导入", target: semanticIds.ledgerImport },
      { action: "选择账单文件", target: semanticIds.importChooseFile },
      { action: "确认账户映射", target: semanticIds.importMapping },
      { action: "核对流水", target: semanticIds.importPreview },
      { action: "进入配对", target: semanticIds.importRelations },
      { action: "确认导入", target: semanticIds.importConfirm },
      { action: "看到导入完成", target: semanticIds.importSuccess, state: "success" },
    ],
  },
} as const;
