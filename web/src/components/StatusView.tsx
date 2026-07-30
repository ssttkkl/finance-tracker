type Props = { kind: "loading" | "empty" | "error"; onRetry?: () => void; message?: string };

export function StatusView({ kind, onRetry, message }: Props) {
  const text = {
    loading: "正在读取收支投影…",
    empty: "当前筛选没有匹配的收支投影。",
    error: "无法读取账本。请检查本机 API 后重试。",
  }[kind];
  return <div className={`status-view status-${kind}`} role={kind === "error" ? "alert" : "status"}>
    <p>{message ?? text}</p>{onRetry && kind === "error" ? <button type="button" onClick={onRetry}>重试</button> : null}
  </div>;
}
