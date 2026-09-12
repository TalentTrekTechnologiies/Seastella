import { useQuery } from '@tanstack/react-query';

/**
 * One hook for every dashboard.
 *
 * <p>Each dashboard is a single aggregation request, keyed by role. Cards read
 * from that one response rather than fetching individually, so no two figures
 * on a screen can come from different moments.
 */
export function useDashboard<T>(key: string, fetcher: () => Promise<T>) {
  return useQuery<T>({
    queryKey: ['dashboard', key],
    queryFn: fetcher,
  });
}
