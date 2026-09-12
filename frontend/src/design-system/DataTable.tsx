import { useMemo, useState, type ReactNode } from 'react';
import { EmptyState } from './States';
import './table.css';

export interface Column<T> {
  key: string;
  header: string;
  /** Right-align numeric columns so digits compare down the column. */
  align?: 'left' | 'right';
  width?: string;
  render: (row: T) => ReactNode;
  /** Value used for sorting. Omit to make the column unsortable. */
  sortValue?: (row: T) => string | number | null;
}

/**
 * The one table.
 *
 * <p>Sorting and paging happen here, over data the server already scoped —
 * the client never fetches a wider set and filters it down. Long tables scroll
 * inside their own container so the page body never scrolls sideways.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  emptyTitle = 'Nothing to show',
  emptyBody,
  pageSize = 0,
  dense = false,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  onRowClick?: (row: T) => void;
  emptyTitle?: string;
  emptyBody?: string;
  /** 0 disables paging. */
  pageSize?: number;
  dense?: boolean;
}) {
  const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(null);
  const [page, setPage] = useState(0);

  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col?.sortValue) return rows;

    return [...rows].sort((a, b) => {
      const av = col.sortValue!(a);
      const bv = col.sortValue!(b);
      if (av === bv) return 0;
      if (av === null) return 1;
      if (bv === null) return -1;
      const cmp = av < bv ? -1 : 1;
      return sort.dir === 'asc' ? cmp : -cmp;
    });
  }, [rows, sort, columns]);

  const paged = pageSize > 0 ? sorted.slice(page * pageSize, (page + 1) * pageSize) : sorted;
  const totalPages = pageSize > 0 ? Math.ceil(sorted.length / pageSize) : 1;

  if (rows.length === 0) {
    return <EmptyState title={emptyTitle} body={emptyBody} />;
  }

  return (
    <>
      <div className="table-scroll">
        <table className={dense ? 'dt dt--dense' : 'dt'}>
          <thead>
            <tr>
              {columns.map((c) => (
                <th
                  key={c.key}
                  style={{ width: c.width, textAlign: c.align ?? 'left' }}
                  aria-sort={
                    sort?.key === c.key
                      ? sort.dir === 'asc'
                        ? 'ascending'
                        : 'descending'
                      : undefined
                  }
                >
                  {c.sortValue ? (
                    <button
                      type="button"
                      className="dt__sort"
                      onClick={() =>
                        setSort((s) =>
                          s?.key === c.key
                            ? { key: c.key, dir: s.dir === 'asc' ? 'desc' : 'asc' }
                            : { key: c.key, dir: 'asc' },
                        )
                      }
                    >
                      {c.header}
                      <span className="dt__arrow" aria-hidden="true">
                        {sort?.key === c.key ? (sort.dir === 'asc' ? '\u2191' : '\u2193') : '\u2195'}
                      </span>
                    </button>
                  ) : (
                    c.header
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {paged.map((row) => (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={onRowClick ? 'dt__row--clickable' : undefined}
                tabIndex={onRowClick ? 0 : undefined}
                onKeyDown={
                  onRowClick
                    ? (e) => {
                        if (e.key === 'Enter') onRowClick(row);
                      }
                    : undefined
                }
              >
                {columns.map((c) => (
                  <td key={c.key} style={{ textAlign: c.align ?? 'left' }}>
                    {c.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="dt__pager">
          <span className="dt__pager-info">
            {page * pageSize + 1}&ndash;{Math.min((page + 1) * pageSize, sorted.length)} of{' '}
            {sorted.length}
          </span>
          <div className="dt__pager-controls">
            <button type="button" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
              Previous
            </button>
            <button
              type="button"
              onClick={() => setPage((p) => p + 1)}
              disabled={page >= totalPages - 1}
            >
              Next
            </button>
          </div>
        </div>
      )}
    </>
  );
}
