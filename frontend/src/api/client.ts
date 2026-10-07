import type {
  AnalysisRunView, AnalysisStatus, CallTreeNode, CallTreeOptions, CodeBlock, EndpointView, NodeDetail, ScreenView,
  SearchHit, ServiceDependencies, ServiceList, TraceResult,
} from './types.ts';

/** Backend'in döndürdüğü RFC 9457 problem detayından üretilen hata. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

type Query = Record<string, string | number | boolean | string[] | undefined | null>;

function url(path: string, query: Query = {}): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) value.forEach((v) => params.append(key, v));
    else params.append(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

async function request<T>(path: string, query?: Query, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url(path, query), { headers: { Accept: 'application/json' }, ...init });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'CodeGraph sunucusuna bağlanılamadı. Backend çalışıyor mu?');
  }
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const problem = await response.json();
      message = problem.detail ?? problem.title ?? message;
    } catch {
      // gövde JSON değil
    }
    throw new ApiError(response.status, message);
  }
  return response.json() as Promise<T>;
}

const seg = encodeURIComponent;

export const api = {
  services: (layer?: string, signal?: AbortSignal) =>
    request<ServiceList>('/api/services', { layer }, { signal }),
  serviceDependencies: (name: string, signal?: AbortSignal) =>
    request<ServiceDependencies>(`/api/services/${seg(name)}/dependencies`, {}, { signal }),
  screens: (service?: string, signal?: AbortSignal) =>
    request<ScreenView[]>('/api/screens', { service }, { signal }),
  endpoints: (service?: string, signal?: AbortSignal) =>
    request<EndpointView[]>('/api/endpoints', { service }, { signal }),
  trace: (
    direction: 'forward' | 'backward',
    from: string,
    options: { maxDepth: number; includeAccessors: boolean; prune: boolean },
    signal?: AbortSignal,
  ) =>
    request<TraceResult>(`/api/trace/${direction}`, {
      from,
      maxDepth: options.maxDepth,
      includeAccessors: options.includeAccessors,
      prune: direction === 'forward' ? options.prune : undefined,
    }, { signal }),
  node: (id: string, signal?: AbortSignal) => request<NodeDetail>(`/api/nodes/${seg(id)}`, {}, { signal }),
  code: (id: string, signal?: AbortSignal) => request<CodeBlock>(`/api/nodes/${seg(id)}/code`, {}, { signal }),
  search: (q: string, limit = 20, signal?: AbortSignal) =>
    request<SearchHit[]>('/api/search', { q, limit }, { signal }),
  callTree: (id: string, options: CallTreeOptions, ancestors: string[] = [], signal?: AbortSignal) =>
    request<CallTreeNode>(`/api/methods/${seg(id)}/call-tree`, {
      depth: options.depth,
      includeAccessors: options.includeAccessors,
      onlyExternal: options.onlyExternal,
      crossService: options.crossService,
      hidden: options.hidden,
      ancestors,
    }, { signal }),
  analysisRuns: (signal?: AbortSignal) => request<AnalysisRunView[]>('/api/analysis', { limit: 20 }, { signal }),
  analysisRun: (id: string, signal?: AbortSignal) =>
    request<AnalysisRunView>(`/api/analysis/${seg(id)}`, {}, { signal }),
  startAnalysis: () => request<AnalysisStatus>('/api/analysis', {}, { method: 'POST' }),
};
