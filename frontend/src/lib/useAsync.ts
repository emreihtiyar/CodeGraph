import { useCallback, useEffect, useState, type DependencyList } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: Error | undefined;
  loading: boolean;
  reload: () => void;
}

/** Bağımlılıklar değişince isteği yeniden yapar; önceki istek iptal edilir ve geç gelen yanıt yok sayılır. */
export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T> | undefined, deps: DependencyList): AsyncState<T> {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
  const [nonce, setNonce] = useState(0);
  const reload = useCallback(() => setNonce((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    const promise = fn(controller.signal);
    if (!promise) {
      setState({ loading: false });
      return () => controller.abort();
    }
    setState((s) => ({ data: s.data, loading: true }));
    promise.then(
      (data) => !controller.signal.aborted && setState({ data, loading: false }),
      (error: Error) => {
        if (!controller.signal.aborted && error.name !== 'AbortError') setState({ error, loading: false });
      },
    );
    return () => controller.abort();
  }, [...deps, nonce]);

  return { data: state.data, error: state.error, loading: state.loading, reload };
}
