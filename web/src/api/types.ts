/**
 * Web 兼容出口：DTO 由跨平台 contracts 包维护，页面继续从原路径导入以保持迁移期间的稳定性。
 */
export type * from "@finance-tracker/contracts";
export { roleLabel, SESSION_TOKEN_STORAGE_KEY } from "@finance-tracker/contracts";
