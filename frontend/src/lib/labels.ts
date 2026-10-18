import type { CallTreeKind, NodeLabel } from '../api/types.ts';

/** Düğüm tiplerinin Türkçe adı, rengi ve simgesi; graf, rozet ve listelerde ortak. */
export const LABELS: Record<NodeLabel, { title: string; color: string; icon: string }> = {
  Service: { title: 'Servis', color: '#334155', icon: '▣' },
  Screen: { title: 'Ekran', color: '#7c3aed', icon: '▤' },
  Component: { title: 'Component', color: '#9333ea', icon: '◇' },
  ApiCall: { title: 'ApiCall', color: '#c026d3', icon: '⇢' },
  Endpoint: { title: 'Endpoint', color: '#2563eb', icon: '⚑' },
  Class: { title: 'Sınıf', color: '#475569', icon: 'C' },
  Method: { title: 'Metot', color: '#64748b', icon: 'ƒ' },
  FeignClient: { title: 'Feign istemcisi', color: '#0891b2', icon: '⇄' },
  FeignMethod: { title: 'Feign çağrısı', color: '#0891b2', icon: '🌐' },
  JpaRepository: { title: 'Repository', color: '#059669', icon: 'R' },
  RepositoryMethod: { title: 'DB erişimi', color: '#059669', icon: '🗄' },
  Entity: { title: 'Entity', color: '#65a30d', icon: 'E' },
  Table: { title: 'Tablo', color: '#b45309', icon: '▦' },
  AnalysisRun: { title: 'Analiz', color: '#334155', icon: '⟳' },
};

export const KIND_LABEL: Record<CallTreeKind, NodeLabel> = {
  METHOD: 'Method',
  FEIGN_METHOD: 'FeignMethod',
  REPOSITORY_METHOD: 'RepositoryMethod',
  ENDPOINT: 'Endpoint',
  SCREEN: 'Screen',
  API_CALL: 'ApiCall',
};

export const LAYER_TITLES: Record<string, string> = {
  FRONTEND: 'Frontend',
  BFF: 'BFF',
  API: 'API',
  ADAPTER: 'Adaptör',
  LIBRARY: 'Kütüphane',
  EXTERNAL: 'Analiz dışı',
  DB: 'Veritabanı',
};

export const LAYER_COLORS: Record<string, string> = {
  FRONTEND: '#7c3aed',
  BFF: '#2563eb',
  API: '#0891b2',
  ADAPTER: '#0d9488',
  LIBRARY: '#64748b',
  EXTERNAL: '#94a3b8',
  DB: '#b45309',
};

/** Düğümle varsayılan olarak ne yapılacağı (aramada seçince). */
export type NodeAction = 'trace-forward' | 'trace-backward' | 'call-tree' | 'service' | 'code';

export function defaultAction(label: NodeLabel): NodeAction {
  switch (label) {
    case 'Screen':
    case 'Component':
    case 'ApiCall':
    case 'Endpoint':
      return 'trace-forward';
    case 'Method':
    case 'FeignMethod':
    case 'RepositoryMethod':
      return 'call-tree';
    case 'Table':
    case 'Entity':
    case 'JpaRepository':
      return 'trace-backward';
    case 'Service':
      return 'service';
    default:
      return 'code';
  }
}

export function isCallable(label: NodeLabel): boolean {
  return label === 'Method' || label === 'FeignMethod' || label === 'RepositoryMethod' || label === 'Endpoint'
    || label === 'Screen' || label === 'ApiCall';
}

/**
 * Ekran isteğinin çıktığı yer: ekrandan isteği atan component'e giden yol, sayfanın kendisi atlanarak
 * (`SubContractDetailsTab › SubContractUpdateModal`); istek sayfanın kendisindeyse onun adı.
 */
export function requestOrigin(call: Record<string, unknown>): string {
  const path = Array.isArray(call.componentPath) ? (call.componentPath as string[]) : [];
  if (path.length > 1) return path.slice(1).join(' › ');
  return String(call.component ?? path[0] ?? '');
}
