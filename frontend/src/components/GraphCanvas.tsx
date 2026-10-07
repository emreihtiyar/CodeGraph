import cytoscape, { type Core, type ElementDefinition, type LayoutOptions, type StylesheetJson } from 'cytoscape';
import dagre from 'cytoscape-dagre';
import { useEffect, useRef } from 'react';

cytoscape.use(dagre);

export interface GraphCanvasProps {
  elements: ElementDefinition[];
  /** {@code preset}: konumlar elemanlarda; {@code dagre}: soldan sağa katmanlı yerleşim */
  layout: 'preset' | 'dagre';
  selectedId?: string | null;
  onNodeTap?: (id: string, data: Record<string, unknown>) => void;
  onEdgeTap?: (data: Record<string, unknown>) => void;
  onBackgroundTap?: () => void;
}

const STYLE: StylesheetJson = [
  {
    selector: 'node',
    style: {
      label: 'data(text)',
      'font-size': 11,
      'font-family': 'Inter, system-ui, sans-serif',
      color: '#0f172a',
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'ellipsis',
      'text-max-width': 'data(w)',
      shape: 'round-rectangle',
      width: 'data(w)',
      height: 30,
      'background-color': '#ffffff',
      'border-width': 2,
      'border-color': 'data(color)',
    },
  },
  { selector: 'node[kind = "Table"]', style: { shape: 'barrel', 'background-color': '#fef3c7', height: 36 } },
  { selector: 'node[kind = "Entity"]', style: { 'background-color': '#f7fee7' } },
  { selector: 'node[kind = "Screen"]', style: { 'background-color': '#f5f3ff', 'font-weight': 600 } },
  { selector: 'node[kind = "ApiCall"]', style: { shape: 'tag', 'background-color': '#fdf4ff' } },
  { selector: 'node[kind = "Endpoint"]', style: { shape: 'cut-rectangle', 'background-color': '#eff6ff' } },
  { selector: 'node[kind = "FeignMethod"]', style: { shape: 'cut-rectangle', 'background-color': '#ecfeff' } },
  { selector: 'node[kind = "RepositoryMethod"]', style: { 'background-color': '#ecfdf5' } },
  {
    selector: 'node.service-group',
    style: {
      shape: 'round-rectangle',
      'background-color': 'data(color)',
      'background-opacity': 0.06,
      'border-width': 1.5,
      'border-style': 'dashed',
      'text-valign': 'top',
      'text-halign': 'center',
      'text-margin-y': -4,
      'font-size': 12,
      'font-weight': 700,
      color: 'data(color)',
      padding: '14px',
    },
  },
  {
    selector: 'node.service',
    style: {
      height: 40,
      'background-color': 'data(color)',
      'background-opacity': 0.1,
      'font-size': 12,
      'font-weight': 600,
    },
  },
  { selector: 'node.external', style: { 'border-style': 'dashed', color: '#64748b' } },
  {
    selector: 'node.database',
    style: { shape: 'barrel', height: 56, 'background-color': '#fef3c7', 'font-weight': 700 },
  },
  { selector: 'node.start', style: { 'border-width': 4 } },
  { selector: 'node.unresolved', style: { 'border-color': '#dc2626', 'border-style': 'dashed', color: '#b91c1c' } },
  { selector: 'node.ambiguous', style: { 'border-color': '#d97706', 'border-style': 'dashed' } },
  {
    selector: 'edge',
    style: {
      width: 1.6,
      'curve-style': 'bezier',
      'line-color': '#94a3b8',
      'target-arrow-color': '#94a3b8',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.9,
      label: 'data(label)',
      'font-size': 10,
      'font-weight': 600,
      color: '#334155',
      'text-background-color': '#ffffff',
      'text-background-opacity': 0.9,
      'text-background-padding': '2px',
      'text-background-shape': 'roundrectangle',
    },
  },
  { selector: 'edge.requests, edge.resolves_to', style: { 'line-color': '#2563eb', 'target-arrow-color': '#2563eb', width: 2.2 } },
  { selector: 'edge.invokes', style: { 'line-color': '#0891b2', 'target-arrow-color': '#0891b2' } },
  { selector: 'edge.uses_repository, edge.reads', style: { 'line-color': '#059669', 'target-arrow-color': '#059669' } },
  { selector: 'edge.writes, edge.mapped_to.writes', style: { 'line-color': '#ea580c', 'target-arrow-color': '#ea580c' } },
  { selector: 'edge.handled_by, edge.mapped_to', style: { 'line-style': 'dotted' } },
  { selector: 'edge.renders, edge.uses_api_call, edge.has_screen', style: { 'line-color': '#a855f7', 'target-arrow-color': '#a855f7' } },
  { selector: 'edge.conditional', style: { 'line-style': 'dashed' } },
  { selector: 'edge.depends', style: { width: 2.4, 'line-color': '#64748b', 'target-arrow-color': '#64748b' } },
  { selector: 'edge.unused', style: { 'line-style': 'dashed', opacity: 0.6 } },
  { selector: 'edge.db-edge', style: { 'line-color': '#d97706', 'target-arrow-color': '#d97706', 'line-style': 'dashed' } },
  { selector: '.faded', style: { opacity: 0.18 } },
  { selector: 'node:selected', style: { 'border-color': '#f59e0b', 'border-width': 4 } },
  { selector: 'edge:selected', style: { 'line-color': '#f59e0b', 'target-arrow-color': '#f59e0b', width: 3.5 } },
];

const DAGRE = {
  name: 'dagre',
  rankDir: 'LR',
  nodeSep: 18,
  rankSep: 70,
  edgeSep: 8,
  padding: 24,
  animate: false,
} as LayoutOptions;

const READABLE_ZOOM = 0.6;

/**
 * Geniş graflarda "sığdır" yazıları okunmaz hâle getirir; o durumda okunur yakınlıkta kalıp başlangıç düğümünü
 * (yoksa grafın sol kenarını) görünür tutar. Tamamı "Sığdır" düğmesiyle görülebilir.
 */
function keepReadable(cy: Core) {
  if (cy.zoom() >= READABLE_ZOOM) return;
  const box = cy.elements().boundingBox();
  const start = cy.nodes('.start');
  const y = start.nonempty() ? start.first().position().y : (box.y1 + box.y2) / 2;
  cy.zoom(READABLE_ZOOM);
  cy.pan({ x: 30 - box.x1 * READABLE_ZOOM, y: cy.height() / 2 - y * READABLE_ZOOM });
}

/** Cytoscape tuvali; elemanlar değişince graf yeniden kurulur, seçim değişince komşuluk vurgulanır. */
export function GraphCanvas({ elements, layout, selectedId, onNodeTap, onEdgeTap, onBackgroundTap }: GraphCanvasProps) {
  const container = useRef<HTMLDivElement>(null);
  const cyRef = useRef<Core | null>(null);
  const handlers = useRef({ onNodeTap, onEdgeTap, onBackgroundTap });
  handlers.current = { onNodeTap, onEdgeTap, onBackgroundTap };

  useEffect(() => {
    const cy = cytoscape({
      container: container.current,
      elements,
      style: STYLE,
      // Varsayılan (grid) yerleşim preset konumlarını ezmesin; asıl yerleşim aşağıda çalışır.
      layout: { name: 'preset', fit: false },
      minZoom: 0.1,
      maxZoom: 3,
      wheelSensitivity: 0.25,
    });
    const run = cy.layout(layout === 'dagre' ? DAGRE : { name: 'preset', padding: 40 });
    run.one('layoutstop', () => keepReadable(cy));
    run.run();
    cy.on('tap', 'node', (e) => {
      const data = e.target.data();
      if (data.kind !== 'ServiceGroup') handlers.current.onNodeTap?.(data.id, data);
    });
    cy.on('tap', 'edge', (e) => handlers.current.onEdgeTap?.(e.target.data()));
    cy.on('tap', (e) => {
      if (e.target === cy) handlers.current.onBackgroundTap?.();
    });
    cyRef.current = cy;
    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [elements, layout]);

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.elements().removeClass('faded').unselect();
    if (!selectedId) return;
    const node = cy.getElementById(selectedId);
    if (node.empty()) return;
    node.select();
    const keep = node.closedNeighborhood().union(node.closedNeighborhood().parents());
    cy.elements().difference(keep).addClass('faded');
  }, [selectedId, elements]);

  return (
    <div className="graph-canvas">
      <div ref={container} className="graph-canvas-inner" />
      <div className="graph-controls">
        <button type="button" title="Sığdır" onClick={() => cyRef.current?.fit(undefined, 30)}>⤢</button>
        <button type="button" title="Yakınlaştır" onClick={() => cyRef.current?.zoom(cyRef.current.zoom() * 1.25)}>+</button>
        <button type="button" title="Uzaklaştır" onClick={() => cyRef.current?.zoom(cyRef.current.zoom() / 1.25)}>−</button>
      </div>
    </div>
  );
}
