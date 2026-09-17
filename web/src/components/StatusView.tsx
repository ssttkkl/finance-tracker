import { copy } from "@finance-tracker/presentation";

type Props = { kind: "loading" | "empty" | "error"; onRetry?: () => void; message?: string; testID?: string };

export function StatusView({ kind, onRetry, message, testID }: Props) {
  const text = {
    loading: copy.ledger.loading,
    empty: "当前筛选没有匹配的收支记录。",
    error: "无法读取账本，请稍后重试。",
  }[kind];
  return <div data-testid={testID} className={`status-view status-${kind}`} data-status-kind={kind} role={kind === "error" ? "alert" : "status"}>
    <p>{message ?? text}</p>{onRetry && kind === "error" ? <button type="button" onClick={onRetry}>{copy.ledger.reload}</button> : null}
  </div>;
}
