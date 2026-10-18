import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client.ts';
import type { EdgeView, NodeSummary, TraceResult } from '../api/types.ts';
import { GraphCanvas } from '../components/GraphCanvas.tsx';
import { NodeInspector } from '../components/NodeInspector.tsx';
import { StartPicker } from '../components/StartPicker.tsx';
import { Badge, ErrorBox, NodeLine, RequestLine, Spinner, Toggle } from '../components/ui.tsx';
import { CALL_TYPES, traceElements } from '../lib/graphElements.ts';
import { LABELS, LAYER_TITLES } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState, type CodeTab } from '../state.tsx';

export interface TraceParams {
  from?: string;
  dir?: string;
  depth?: string;
  prune?: string;
  acc?: string;
  /** Başlangıç seçicide seçili servis. */
  svc?: string;
}

/** Akış izleme (FR-16 ileri, FR-17 geri): alt graf, özet ve kod paneli bağlantısı. */
export function TraceView({ params }: { params: TraceParams }) {
  const { graphVersion, openCode } = useAppState();
  const direction = params.dir === 'backward' ? 'backward' : 'forward';
  const maxDepth = clamp(Number(params.depth ?? 40), 1, 100);
  const prune = params.prune !== '0';
  const includeAccessors = params.acc === '1';
  const [selected, setSelected] = useState<string | null>(null);
  const [depthDraft, setDepthDraft] = useState(maxDepth);
  useEffect(() => setDepthDraft(maxDepth), [maxDepth]);

  const trace = useAsync(
    (signal) => (params.from
      ? api.trace(direction, params.from, { maxDepth, includeAccessors, prune }, signal)
      : undefined),
    [params.from, direction, maxDepth, prune, includeAccessors, graphVersion],
  );
  useEffect(() => setSelected(null), [trace.data]);
  const elements = useMemo(() => (trace.data ? traceElements(trace.data) : []), [trace.data]);

  const update = (changes: Partial<TraceParams>) => navigate('trace', { ...params, ...changes });

  if (!params.from) {
    return (
      <StartPicker
        title="İzlenecek başlangıcı seçin"
        hint="Servis seçip bir ekrandan ya da endpoint'ten tablolara kadar ileri iz alın. Tablo veya entity'den geri iz için üstteki aramayı kullanın."
        withScreens
        service={params.svc}
        onService={(svc) => navigate('trace', { svc })}
        onPick={(from) => navigate('trace', { svc: params.svc, from, dir: 'forward' })}
      />
    );
  }

  const onEdgeTap = (data: Record<string, unknown>) => {
    const edge = trace.data?.graph.edges[data.edgeIndex as number];
    if (edge) openCode(...edgeCode(edge, trace.data!));
  };

  return (
    <div className="view">
      <div className="toolbar view-toolbar">
        {trace.data ? <NodeLine node={trace.data.start} /> : <span className="mono">{params.from}</span>}
        <div className="segmented" role="group" aria-label="Yön">
          <button type="button" className={direction === 'forward' ? 'active' : ''} onClick={() => update({ dir: 'forward' })}>İleri →</button>
          <button type="button" className={direction === 'backward' ? 'active' : ''} onClick={() => update({ dir: 'backward' })}>← Geri</button>
        </div>
        <label className="depth" title="İzin en fazla kaç kenar ilerleyeceği">
          Derinlik
          <input type="range" min={1} max={100} value={depthDraft}
            onChange={(e) => setDepthDraft(Number(e.target.value))}
            onPointerUp={() => update({ depth: String(depthDraft) })}
            onKeyUp={() => update({ depth: String(depthDraft) })} />
          <span className="mono">{depthDraft}</span>
        </label>
        {direction === 'forward' && (
          <Toggle label="Yalnızca anlamlı dallar" checked={prune} onChange={(v) => update({ prune: v ? undefined : '0' })}
            title="Tabloya, endpoint'e ya da dış çağrıya gitmeyen ara metotları gizle" />
        )}
        <Toggle label="Getter/setter" checked={includeAccessors} onChange={(v) => update({ acc: v ? '1' : undefined })} />
        <span className="toolbar-spacer" />
        {trace.loading && <Spinner text="İz hesaplanıyor…" />}
        {trace.data && <span className="muted small">{trace.data.graph.nodes.length} düğüm · {trace.data.graph.edges.length} kenar</span>}
      </div>

      {trace.error && <div className="view-error"><ErrorBox error={trace.error} onRetry={trace.reload} /></div>}
      {trace.data && (
        <div className="view-row">
          <aside className="summary">
            <Summary trace={trace.data} onSelect={setSelected} />
          </aside>
          <div className="view-main">
            {trace.data.graph.truncated && <div className="notice warn">{trace.data.graph.note}</div>}
            <GraphCanvas
              elements={elements}
              layout="dagre"
              selectedId={selected}
              onNodeTap={(id) => {
                setSelected(id);
                const node = trace.data!.graph.nodes.find((n) => n.id === id);
                if (node && node.label !== 'Service') openCode([{ nodeId: id, caption: LABELS[node.label]?.title ?? node.label }]);
              }}
              onEdgeTap={onEdgeTap}
              onBackgroundTap={() => setSelected(null)}
            />
            <Legend />
          </div>
          {selected && (
            <aside className="inspector">
              <NodeInspector id={selected} onClose={() => setSelected(null)} />
            </aside>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Kenara tıklanınca: çağrı kenarında çağıranın kodu çağrı satırı vurgulu, ikinci sekmede çağrılan. Ekran → ApiCall
 * kenarında çağıran, isteği atan component'tir (ekranın component ağacı izde gizli).
 */
export function edgeCode(edge: EdgeView, trace: TraceResult): [CodeTab[], number] {
  const name = (id: string) => trace.graph.nodes.find((n) => n.id === id)?.name ?? id;
  const line = typeof edge.properties.line === 'number' ? edge.properties.line : undefined;
  if (edge.type === 'USES_API_CALL' && typeof edge.properties.componentId === 'string') {
    return [[
      { nodeId: edge.properties.componentId, caption: `${String(edge.properties.component)}${line ? ` · satır ${line}` : ''}`, line },
      { nodeId: edge.target, caption: name(edge.target) },
    ], 0];
  }
  if (CALL_TYPES.has(edge.type)) {
    return [[
      { nodeId: edge.source, caption: line ? `Çağrı yeri · satır ${line}` : 'Çağıran', line },
      { nodeId: edge.target, caption: name(edge.target) },
    ], 0];
  }
  return [[
    { nodeId: edge.source, caption: name(edge.source) },
    { nodeId: edge.target, caption: name(edge.target) },
  ], 0];
}

function Summary({ trace, onSelect }: { trace: TraceResult; onSelect: (id: string) => void }) {
  const s = trace.summary;
  const pick = (n: NodeSummary) => onSelect(n.id);
  return (
    <div className="summary-body">
      {s.unresolved.length > 0 && (
        <section className="notice danger">
          <h3>Zincir kopuyor ({s.unresolved.length})</h3>
          {s.unresolved.map((u) => (
            <div key={u.id} className="unresolved-item">
              <NodeLine node={u} onClick={() => pick(u)} />
              {typeof u.properties.unresolvedReason === 'string' && <div className="small">{u.properties.unresolvedReason}</div>}
            </div>
          ))}
        </section>
      )}
      {s.screens.length > 0 && (
        <section>
          <h3>Ekranlar</h3>
          {s.screens.map((n) => <NodeLine key={n.id} node={n} onClick={() => pick(n)} />)}
        </section>
      )}
      {s.apiCalls.length > 0 && (
        <section>
          <h3>İstekler <span className="muted">({s.apiCalls.length})</span></h3>
          {s.apiCalls.map((a) => <RequestLine key={a.id} apiCall={a} onClick={() => pick(a)} />)}
        </section>
      )}
      <section>
        <h3>Servisler</h3>
        {s.services.map((l) => (
          <div key={l.layer} className="layer-row">
            <span className="layer-name">{LAYER_TITLES[l.layer] ?? l.layer}</span>
            <span className="row-wrap">
              {l.services.map((name) => (
                <button key={name} type="button" className="chip-link" onClick={() => navigate('system', { focus: name })}>{name}</button>
              ))}
            </span>
          </div>
        ))}
      </section>
      {s.tables.length > 0 && (
        <section>
          <h3>Tablolar</h3>
          {s.tables.map((t) => (
            <div key={t.id} className="list-row">
              <button type="button" className="link mono" onClick={() => onSelect(t.id)}>{t.name}</button>
              <span className="row-wrap">
                {t.access.map((a) => <Badge key={a} tone={a === 'WRITE' ? 'warn' : 'ok'}>{a}</Badge>)}
              </span>
            </div>
          ))}
        </section>
      )}
      {s.endpoints.length > 0 && (
        <section>
          <h3>Endpoint'ler <span className="muted">({s.endpoints.length})</span></h3>
          {s.endpoints.map((n) => (
            <button key={n.id} type="button" className="link summary-endpoint" onClick={() => pick(n)}>
              <span className="muted small">{n.service}</span>
              <span className="mono break">{n.name}</span>
            </button>
          ))}
        </section>
      )}
    </div>
  );
}

function Legend() {
  return (
    <div className="legend">
      <span><b>1 2 3</b> çağrı sırası</span>
      <span><b>?</b> koşullu</span>
      <span><b>↻</b> döngüde</span>
      <span><b>⚡</b> async</span>
      <span><b>λ</b> lambda</span>
      <span className="legend-line requests">HTTP</span>
      <span className="legend-line writes">yazma</span>
    </div>
  );
}

function clamp(n: number, min: number, max: number): number {
  return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n))) : min;
}
