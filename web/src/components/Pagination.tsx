import { useEffect, useRef } from "react";

type Props = {
  hasMore: boolean;
  loading: boolean;
  error?: string;
  onLoadMore: () => void;
};

/** 提供自动连续加载，并保留键盘和辅助技术可用的手动回退。 */
export function LoadMoreControl({ hasMore, loading, error, onLoadMore }: Props) {
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
