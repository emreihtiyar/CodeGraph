import { useEffect, useState } from 'react';

/**
 * Hash tabanlı yönlendirme (#/trace?from=...): sayfalar paylaşılabilir ve tarayıcı geri tuşu çalışır, router
 * kütüphanesine gerek kalmaz.
 */
export type View = 'system' | 'trace' | 'tree' | 'analysis';

export interface Route {
  view: View;
  params: Record<string, string>;
}

const VIEWS: View[] = ['system', 'trace', 'tree', 'analysis'];

export function parseRoute(hash: string): Route {
  const raw = hash.replace(/^#\/?/, '');
  const [path, query = ''] = raw.split('?', 2);
  const view = (VIEWS as string[]).includes(path) ? (path as View) : 'system';
  const params: Record<string, string> = {};
  new URLSearchParams(query).forEach((value, key) => {
    params[key] = value;
  });
  return { view, params };
}

export function formatRoute(route: Route): string {
  const query = new URLSearchParams();
  Object.entries(route.params)
    .filter(([, v]) => v !== undefined && v !== '')
    .forEach(([k, v]) => query.set(k, v));
  const qs = query.toString();
  return `#/${route.view}${qs ? `?${qs}` : ''}`;
}

export function navigate(view: View, params: Record<string, string | number | boolean | undefined> = {}) {
  const clean: Record<string, string> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') clean[k] = String(v);
  }
  window.location.hash = formatRoute({ view, params: clean });
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
