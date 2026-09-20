import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { downloadReportPdf, fetchReport, fetchReports } from '@/api/reports';
import { Button, ConsoleHeader, EmptyNote, Plate } from '@/design-system/Console';
import { FormError } from '@/design-system/Dialog';
import { Icon } from '@/design-system/Icon';
import { ErrorState, LoadingState } from '@/design-system/States';
import { formatDateTime } from '@/lib/format';
import { errorText } from '@/features/admin/AdminParts';
import './reports.css';

/**
 * Reports (SoW §7).
 *
 * <p>The list is what this role may run — the invoice report is simply absent
 * for a Captain rather than present and refused — and each report is built for
 * the reader's own scope, which the report states on itself so a printed page
 * is never ambiguous.
 */
export function ReportsPage() {
  const available = useQuery({ queryKey: ['reports'], queryFn: fetchReports });
  const [key, setKey] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!key && available.data?.length) setKey(available.data[0].key);
  }, [available.data, key]);

  const report = useQuery({ queryKey: ['report', key], queryFn: () => fetchReport(key!), enabled: key !== null });

  const download = async () => {
    if (!key) return;
    setDownloading(true);
    setError(null);
    try {
      await downloadReportPdf(key);
    } catch (e) {
      setError(errorText(e, 'The PDF could not be produced.'));
    } finally {
      setDownloading(false);
    }
  };

  if (available.error) return <ErrorState error={available.error} onRetry={() => available.refetch()} />;

  const table = report.data;

  return (
    <div className="console">
      <ConsoleHeader
        scope={[{ label: 'Reports', strong: true }]}
        title="Reports"
        subtitle="Each report covers the vessels you are responsible for, and says so on the page."
        actions={
          table ? (
            <Button variant="primary" onClick={download} disabled={downloading}>
              <Icon name="report" size={16} />
              {downloading ? 'Preparing…' : 'Download PDF'}
            </Button>
          ) : undefined
        }
      />

      <div className="reports">
        <nav className="reports__list" aria-label="Reports">
          {(available.data ?? []).map((option) => (
            <button
              key={option.key}
              type="button"
              className={`reports__choice${option.key === key ? ' reports__choice--on' : ''}`}
              aria-pressed={option.key === key}
              onClick={() => setKey(option.key)}
            >
              <b>{option.title}</b>
              <span>{option.description}</span>
            </button>
          ))}
        </nav>

        <Plate
          title={table?.title ?? 'Report'}
          subtitle={table ? `${table.scopeNote} · produced ${formatDateTime(table.generatedAt)} by ${table.generatedBy}` : undefined}
          count={table ? table.rows.length : undefined}
          flush
        >
          <FormError message={error} />
          {report.isLoading || !table ? (
            <div style={{ padding: '0 20px 20px' }}>
              <LoadingState rows={6} />
            </div>
          ) : table.rows.length === 0 ? (
            <div className="qlist__empty">
              <EmptyNote>Nothing to report for your vessels yet.</EmptyNote>
            </div>
          ) : (
            <div className="reports__scroll">
              <table className="reports__table">
                <thead>
                  <tr>
                    {table.columns.map((column) => (
                      <th key={column.label} className={column.numeric ? 'reports__num' : undefined}>
                        {column.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {table.rows.map((row, i) => (
                    <tr key={i}>
                      {row.map((cell, c) => (
                        <td key={c} className={table.columns[c]?.numeric ? 'reports__num' : undefined}>
                          {cell}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {table && table.totals.length > 0 && (
            <ul className="reports__totals">
              {table.totals.map((total) => (
                <li key={total.label}>
                  <span>{total.label}</span>
                  <b>{total.value}</b>
                </li>
              ))}
            </ul>
          )}
        </Plate>
      </div>
    </div>
  );
}
