type Props = { kind: "loading" | "empty" | "error"; onRetry?: () => void; message?: string };

export function StatusView({ kind, onRetry, message }: Props) {
  const text = {
    loading: "正在读取收支记录…",
    empty: "当前筛选没有匹配的收支记录。",
    error: "暂时无法读取账本，请稍后重试。",
  }[kind];
  return <div className={`status-view status-${kind}`} data-status-kind={kind} role={kind === "error" ? "alert" : "status"}>
    <p>{message ?? text}</p>{onRetry && kind === "error" ? <button type="button" onClick={onRetry}>重试</button> : null}
  </div>;
}
