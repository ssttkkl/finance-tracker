type Props = {
  ariaLabel?: string;
  page: number;
  hasPrevious: boolean;
  hasNext: boolean;
  loading: boolean;
  error?: string;
  onPrevious: () => void;
  onNext: () => void;
  onRetry: () => void;
};

export function PageNavigation({ ariaLabel = "流水分页", page, hasPrevious, hasNext, loading, error, onPrevious, onNext, onRetry }: Props) {
  return <nav className="page-navigation" aria-label={ariaLabel}>
    <button type="button" aria-label="上一页" disabled={loading || !hasPrevious} onClick={onPrevious}>上一页</button>
    <span aria-current="page">{loading ? "正在切换…" : `第 ${page} 页`}</span>
    {error ? <button type="button" className="page-navigation-error" aria-label="重试当前页" onClick={onRetry}>重试</button> : <button type="button" aria-label="下一页" disabled={loading || !hasNext} onClick={onNext}>下一页</button>}
    {error ? <span className="sr-only" role="alert">{error}</span> : null}
  </nav>;
}
