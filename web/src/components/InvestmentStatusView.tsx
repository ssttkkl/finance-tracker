type Props = { kind: "loading" | "empty" | "error"; message?: string; onRetry?: () => void };

export function InvestmentStatusView({ kind, message, onRetry }: Props) {
  const text = { loading: "正在读取投资事件…", empty: "当前筛选没有匹配的投资事件。", error: "暂时无法读取投资账本，请稍后重试。" }[kind];
  return <div className={`status-view status-${kind}`} data-status-kind={kind} role={kind === "error" ? "alert" : "status"}><p>{message ?? text}</p>{kind === "error" && onRetry ? <button type="button" onClick={onRetry}>重试</button> : null}</div>;
}
