import { useEffect, useRef } from "react";

type LoadMoreProps = {
  hasMore: boolean;
  loading: boolean;
  error?: string;
  onLoadMore: () => void;
};

/** 主列表的瀑布流追加，同时保留键盘和辅助技术可用的手动入口。 */
export function LoadMoreControl({ hasMore, loading, error, onLoadMore }: LoadMoreProps) {
  const sentinel = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!hasMore || loading || error || !sentinel.current || !("IntersectionObserver" in window)) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting) onLoadMore();
    });
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [hasMore, loading, error, onLoadMore]);

  if (!hasMore) return <p className="load-more-status" role="status">已显示全部记录。</p>;
  const message = loading ? "正在加载更多记录。" : error ? `加载更多失败：${error}` : "可继续加载更多记录。";
  return <div className="load-more-control">
    <div aria-hidden="true" ref={sentinel} />
    <div aria-live="polite" aria-atomic="true" className="load-more-feedback">
      <p className="sr-only" role="status">{message}</p>
      {error ? <p className="load-more-error" role="alert">{error}</p> : null}
    </div>
    <button type="button" disabled={loading} aria-describedby="load-more-instructions" onClick={onLoadMore}>
      {loading ? "正在加载更多…" : error ? "重试加载更多" : "加载更多"}
    </button>
    <span className="sr-only" id="load-more-instructions">列表末端会自动加载；也可以使用此按钮继续加载。</span>
  </div>;
}

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
