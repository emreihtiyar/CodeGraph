import { describe, expect, it } from 'vitest';
import type { EdgeView, NodeSummary, ServiceList, TraceResult } from '../api/types.ts';
import { DB_NODE, sequenceNumbers, systemElements, SYSTEM_COLUMNS, traceElements } from './graphElements.ts';

const node = (id: string, label: NodeSummary['label'], service: string | null, properties = {}): NodeSummary =>
  ({ id, label, name: id, service, properties });
const edge = (source: string, target: string, type: string, properties = {}): EdgeView =>
  ({ source, target, type, properties });

describe('sequenceNumbers', () => {
  it('numbers calls per caller by code order, ignoring hidden accessor gaps', () => {
    const edges = [
      edge('m', 'b', 'CALLS', { order: 7, line: 20 }),
      edge('m', 'a', 'INVOKES', { order: 2, line: 12 }),
      edge('m', 'r', 'USES_REPOSITORY', { order: 9, line: 25 }),
      edge('x', 'y', 'CALLS', { order: 4 }),
      edge('e', 'm', 'HANDLED_BY'),
    ];
    const seq = sequenceNumbers(edges);
    expect([seq.get(edges[1]), seq.get(edges[0]), seq.get(edges[2]), seq.get(edges[3])]).toEqual([1, 2, 3, 1]);
    expect(seq.has(edges[4])).toBe(false);
  });
});

describe('traceElements', () => {
  const trace: TraceResult = {
    direction: 'FORWARD',
    start: node('h', 'Method', 'bff'),
    graph: {
      nodes: [
        node('h', 'Method', 'bff'),
        node('f', 'FeignMethod', 'bff', { resolution: 'UNRESOLVED' }),
        node('r', 'RepositoryMethod', 'api'),
        node('e', 'Entity', 'api'),
        node('t', 'Table', null),
      ],
      edges: [
        edge('h', 'f', 'INVOKES', { order: 1, conditional: true, inLoop: true }),
        edge('h', 'r', 'USES_REPOSITORY', { order: 2, async: true }),
        edge('r', 'e', 'WRITES'),
        edge('e', 't', 'MAPPED_TO'),
      ],
      truncated: false,
      note: null,
    },
    summary: {
      screens: [], endpoints: [], unresolved: [],
      services: [{ layer: 'BFF', services: ['bff'] }, { layer: 'API', services: ['api'] }],
      tables: [{ id: 't', name: 'T', access: ['READ', 'WRITE'] }],
    },
  };
  const elements = traceElements(trace);
  const byId = (id: string) => elements.find((e) => e.data.id === id)!;

  it('groups service nodes into compound parents and keeps tables outside', () => {
    expect(byId('h').data.parent).toBe('svc::bff');
    expect(byId('t').data.parent).toBeUndefined();
    expect(byId('svc::api').classes).toBe('service-group');
    expect(byId('h').classes).toContain('start');
    expect(byId('f').classes).toContain('unresolved');
  });

  it('labels edges with order, badges and table access', () => {
    const labels = elements.filter((e) => e.group === 'edges').map((e) => e.data.label);
    expect(labels).toEqual(['1 ?↻', '2 ⚡', 'WRITE', 'READ/WRITE']);
    expect(elements.find((e) => e.data.type === 'MAPPED_TO')!.classes).toContain('writes');
  });
});

describe('systemElements', () => {
  const list: ServiceList = {
    services: [
      { id: '1', name: 'web', layer: 'FRONTEND', repo: 'web', contextPath: null, gatewayPrefix: null, analyzed: true, endpointCount: 0, screenCount: 2, tableCount: 0 },
      { id: '2', name: 'bff', layer: 'BFF', repo: 'bff', contextPath: null, gatewayPrefix: 'bff', analyzed: true, endpointCount: 3, screenCount: 0, tableCount: 0 },
      { id: '3', name: 'api', layer: 'API', repo: 'api', contextPath: null, gatewayPrefix: null, analyzed: true, endpointCount: 4, screenCount: 0, tableCount: 2 },
      { id: '4', name: 'legacy', layer: null, repo: null, contextPath: null, gatewayPrefix: null, analyzed: false, endpointCount: 0, screenCount: 0, tableCount: 0 },
    ],
    dependencies: [
      { from: 'web', to: 'bff', callCount: 3 },
      { from: 'bff', to: 'api', callCount: 2 },
      { from: 'bff', to: 'legacy', callCount: 0 },
    ],
  };

  it('lays services out in layer columns with a database column', () => {
    const elements = systemElements(list, new Set(SYSTEM_COLUMNS));
    const x = (id: string) => elements.find((e) => e.data.id === id)!.position!.x;
    expect([x('web'), x('bff'), x('api'), x('legacy'), x(DB_NODE)]).toEqual([0, 250, 500, 750, 1000]);
    expect(elements.find((e) => e.data.id === 'db-api')!.data.label).toBe('2 tablo');
    expect(elements.find((e) => e.data.target === 'legacy')!.classes).toContain('unused');
  });

  it('hides filtered layers and their edges', () => {
    const elements = systemElements(list, new Set(['FRONTEND', 'API']));
    expect(elements.map((e) => e.data.id)).toEqual(['web', 'api']);
  });
});
