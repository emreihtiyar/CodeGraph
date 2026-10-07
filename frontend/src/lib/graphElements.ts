import type { ElementDefinition } from 'cytoscape';
import type { EdgeView, ServiceList, TraceResult } from '../api/types.ts';
import { LABELS, LAYER_COLORS } from './labels.ts';

/** Sıra numarası verilen çağrı kenarları (PRD Hikaye 2: kenarlarda 1, 2, 3…). */
export const CALL_TYPES = new Set(['CALLS', 'INVOKES', 'USES_REPOSITORY']);

export function callBadges(p: Record<string, unknown>): string {
  return [
    p.conditional ? '?' : '',
    p.inLoop ? '↻' : '',
    p.async ? '⚡' : '',
    p.viaLambda ? 'λ' : '',
  ].join('');
}

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' ? value : fallback;
}

/**
 * Her çağıranın gösterilen çağrı kenarlarına kod sırasıyla 1'den başlayan numara verir. Backend'deki {@code order}
 * gizlenen erişimci çağrılarını da saydığı için doğrudan gösterilmez.
 */
export function sequenceNumbers(edges: EdgeView[]): Map<EdgeView, number> {
  const bySource = new Map<string, EdgeView[]>();
  for (const e of edges) {
    if (!CALL_TYPES.has(e.type)) continue;
    const list = bySource.get(e.source) ?? [];
    list.push(e);
    bySource.set(e.source, list);
  }
  const result = new Map<EdgeView, number>();
  for (const list of bySource.values()) {
    list
      .slice()
      .sort((a, b) => num(a.properties.order, 1e9) - num(b.properties.order, 1e9)
        || num(a.properties.line, 1e9) - num(b.properties.line, 1e9))
      .forEach((e, i) => result.set(e, i + 1));
  }
  return result;
}

function nodeWidth(text: string): number {
  return Math.max(70, Math.min(260, text.length * 6.6 + 26));
}

const SERVICE_PREFIX = 'svc::';

/** İz alt grafından Cytoscape elemanları: düğümler servis kutularında, tablolar dışarıda. */
export function traceElements(trace: TraceResult): ElementDefinition[] {
  const layerOf = new Map<string, string>();
  trace.summary.services.forEach((l) => l.services.forEach((s) => layerOf.set(s, l.layer)));
  const tableAccess = new Map(trace.summary.tables.map((t) => [t.id, t.access.join('/')]));
  const elements: ElementDefinition[] = [];
  const parents = new Set<string>();

  for (const n of trace.graph.nodes) {
    const grouped = n.service && n.label !== 'Service' && n.label !== 'Table';
    if (grouped) parents.add(n.service!);
    elements.push({
      group: 'nodes',
      data: {
        id: n.id,
        parent: grouped ? SERVICE_PREFIX + n.service : undefined,
        text: n.name,
        kind: n.label,
        color: LABELS[n.label]?.color ?? '#64748b',
        w: nodeWidth(n.name),
      },
      classes: [
        n.id === trace.start.id ? 'start' : '',
        n.properties.resolution === 'UNRESOLVED' ? 'unresolved' : '',
        n.properties.resolution === 'AMBIGUOUS' ? 'ambiguous' : '',
      ].filter(Boolean).join(' '),
    });
  }
  for (const service of parents) {
    const layer = layerOf.get(service) ?? 'EXTERNAL';
    elements.push({
      group: 'nodes',
      data: { id: SERVICE_PREFIX + service, text: service, kind: 'ServiceGroup', color: LAYER_COLORS[layer] ?? '#94a3b8' },
      classes: 'service-group',
    });
  }

  const sequence = sequenceNumbers(trace.graph.edges);
  trace.graph.edges.forEach((e, i) => {
    let label = '';
    const seq = sequence.get(e);
    if (seq !== undefined) {
      const badges = callBadges(e.properties);
      label = badges ? `${seq} ${badges}` : String(seq);
    } else if (e.type === 'READS') {
      label = 'READ';
    } else if (e.type === 'WRITES') {
      label = 'WRITE';
    } else if (e.type === 'MAPPED_TO') {
      label = tableAccess.get(e.target) ?? '';
    }
    elements.push({
      group: 'edges',
      data: { id: `e${i}`, source: e.source, target: e.target, type: e.type, label, edgeIndex: i },
      classes: [
        e.type.toLowerCase(),
        e.properties.conditional ? 'conditional' : '',
        e.properties.async ? 'async' : '',
        e.type === 'MAPPED_TO' && (tableAccess.get(e.target) ?? '').includes('WRITE') ? 'writes' : '',
      ].filter(Boolean).join(' '),
    });
  });
  return elements;
}

/** Sistem görünümünün sütunları (FR-15: Frontend → BFF → API → Adaptör → DB). */
export const SYSTEM_COLUMNS = ['FRONTEND', 'BFF', 'API', 'ADAPTER', 'LIBRARY', 'EXTERNAL', 'DB'] as const;
export const DB_NODE = 'db::all';

/** Katmanı olmayan servisler Feign hedefi olup analize dahil edilmemiş servislerdir. */
export function serviceColumn(layer: string | null): string {
  return layer ?? 'EXTERNAL';
}

/** Servis düzeyi katmanlı graf; konumlar sütun/sıra olarak sabit hesaplanır (yerleşim algoritması yok). */
export function systemElements(list: ServiceList, visibleColumns: Set<string>): ElementDefinition[] {
  const columns = new Map<string, string[]>();
  for (const s of list.services) {
    const column = serviceColumn(s.layer);
    if (!visibleColumns.has(column)) continue;
    columns.set(column, [...(columns.get(column) ?? []), s.name]);
  }
  const withTables = list.services.filter((s) => s.tableCount > 0 && visibleColumns.has(serviceColumn(s.layer)));
  if (visibleColumns.has('DB') && withTables.length > 0) columns.set('DB', [DB_NODE]);

  const shown = SYSTEM_COLUMNS.filter((c) => columns.has(c));
  const position = new Map<string, { x: number; y: number }>();
  const tallest = Math.max(1, ...shown.filter((c) => c !== 'DB').map((c) => columns.get(c)!.length));
  shown.forEach((column, ci) => {
    const names = columns.get(column)!;
    // Veritabanı servis satırlarının altında durur; kenarları aradaki sütunların düğümlerinin üstünden geçmez.
    const offset = column === 'DB' ? (tallest / 2) * 90 + 60 : 0;
    names.forEach((name, ri) => position.set(name, { x: ci * 250, y: (ri - (names.length - 1) / 2) * 90 + offset }));
  });

  const elements: ElementDefinition[] = [];
  for (const s of list.services) {
    const p = position.get(s.name);
    if (!p) continue;
    const column = serviceColumn(s.layer);
    elements.push({
      group: 'nodes',
      data: { id: s.name, text: s.name, kind: 'Service', layer: column, color: LAYER_COLORS[column] ?? '#64748b',
        w: nodeWidth(s.name) + 10 },
      position: p,
      classes: s.analyzed ? 'service' : 'service external',
    });
  }
  if (position.has(DB_NODE)) {
    elements.push({
      group: 'nodes',
      data: { id: DB_NODE, text: 'Veritabanı', kind: 'Database', color: LAYER_COLORS.DB, w: 110 },
      position: position.get(DB_NODE)!,
      classes: 'database',
    });
    for (const s of withTables) {
      elements.push({
        group: 'edges',
        data: { id: `db-${s.name}`, source: s.name, target: DB_NODE, label: `${s.tableCount} tablo` },
        classes: 'db-edge',
      });
    }
  }
  list.dependencies.forEach((d, i) => {
    if (!position.has(d.from) || !position.has(d.to)) return;
    elements.push({
      group: 'edges',
      data: { id: `d${i}`, source: d.from, target: d.to, label: String(d.callCount) },
      classes: d.callCount === 0 ? 'depends unused' : 'depends',
    });
  });
  return elements;
}
