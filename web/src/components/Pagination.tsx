type Props = { nextCursor: string | null; onNext: () => void; onPrevious: () => void; hasPrevious: boolean };

export function Pagination({ nextCursor, onNext, onPrevious, hasPrevious }: Props) {
  return <nav className="pagination" aria-label="收支投影分页"><button type="button" disabled={!hasPrevious} onClick={onPrevious}>上一页</button><button type="button" disabled={!nextCursor} onClick={onNext}>下一页</button></nav>;
}
