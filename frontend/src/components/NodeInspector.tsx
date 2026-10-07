import { api } from '../api/client.ts';
import { isCallable, LABELS } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState } from '../state.tsx';
import { Badge, ErrorBox, LabelPill, Spinner } from './ui.tsx';

const HIDDEN_PROPS = new Set(['name', 'service', 'unresolvedCalls']);

/** Seçili düğümün özellikleri, komşu sayıları ve o düğümden başlatılabilecek işlemler. */
export function NodeInspector({ id, onClose }: { id: string; onClose?: () => void }) {
  const { openCode } = useAppState();
  const detail = useAsync((signal) => api.node(id, signal), [id]);
  if (detail.error) return <ErrorBox error={detail.error} />;
  if (!detail.data) return <Spinner />;
  const { node, hasCode, outgoingTotal, incomingTotal } = detail.data;
  const unresolvedCalls = node.properties.unresolvedCalls as string[] | undefined;
  return (
    <div className="inspector-body">
      <div className="inspector-head">
        <LabelPill label={node.label} />
        {onClose && <button type="button" className="icon-btn" title="Kapat" onClick={onClose}>✕</button>}
      </div>
      <h2 className="break">{node.name}</h2>
      {node.service && <div className="muted">{node.service}</div>}
      <div className="row-wrap actions">
        <button type="button" className="btn btn-small" onClick={() => navigate('trace', { from: node.id, dir: 'forward' })}>İleri iz →</button>
        <button type="button" className="btn btn-small" onClick={() => navigate('trace', { from: node.id, dir: 'backward' })}>← Geri iz</button>
        {isCallable(node.label) && (
          <button type="button" className="btn btn-small" onClick={() => navigate('tree', { root: node.id })}>Çağrı ağacı</button>
        )}
        {hasCode && (
          <button type="button" className="btn btn-small" onClick={() => openCode([{ nodeId: node.id, caption: LABELS[node.label]?.title ?? node.label }])}>Kod</button>
        )}
      </div>
      <table className="props">
        <tbody>
          {Object.entries(node.properties)
            .filter(([k]) => !HIDDEN_PROPS.has(k))
            .map(([k, v]) => (
              <tr key={k}>
                <th>{k}</th>
                <td className="break">{formatValue(v)}</td>
              </tr>
            ))}
          <tr><th>kenarlar</th><td>{outgoingTotal} giden · {incomingTotal} gelen</td></tr>
        </tbody>
      </table>
      {unresolvedCalls && unresolvedCalls.length > 0 && (
        <section>
          <h3>Çözülemeyen çağrılar <Badge tone="warn">{unresolvedCalls.length}</Badge></h3>
          <ul className="plain-list mono small">{unresolvedCalls.map((c) => <li key={c}>{c}</li>)}</ul>
        </section>
      )}
    </div>
  );
}

function formatValue(v: unknown): string {
  if (Array.isArray(v)) return v.join(', ');
  if (v === null || v === undefined) return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
