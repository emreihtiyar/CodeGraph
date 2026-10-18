import { api } from '../api/client.ts';
import type { CallTreeNode } from '../api/types.ts';
import { isCallable, LABELS } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState, type CodeTab } from '../state.tsx';
import { Badge, ErrorBox, LabelPill, RequestLine, Spinner } from './ui.tsx';

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
      {node.label === 'Screen' && <ScreenRequests id={node.id} />}
      {unresolvedCalls && unresolvedCalls.length > 0 && (
        <section>
          <h3>Çözülemeyen çağrılar <Badge tone="warn">{unresolvedCalls.length}</Badge></h3>
          <ul className="plain-list mono small">{unresolvedCalls.map((c) => <li key={c}>{c}</li>)}</ul>
        </section>
      )}
    </div>
  );
}

const REQUEST_TREE = { depth: 2, includeAccessors: false, onlyExternal: false, crossService: true, hidden: [] };

/** Ekranın component ağacından çıkan istekler ve BFF'teki karşılıkları (ekranın çağrı ağacının ilk iki seviyesi). */
function ScreenRequests({ id }: { id: string }) {
  const { openCode } = useAppState();
  const tree = useAsync((signal) => api.callTree(id, REQUEST_TREE, [], signal), [id]);
  if (tree.error) return <ErrorBox error={tree.error} />;
  if (!tree.data) return <Spinner />;
  const requests = tree.data.children ?? [];
  return (
    <section>
      <h3>Bu ekrandan çıkan istekler <Badge>{requests.length}</Badge></h3>
      {requests.length === 0 && <p className="muted small">Ekranın component'lerinde ApiCall kullanımı bulunamadı.</p>}
      <ul className="plain-list">
        {requests.map((r) => (
          <li key={r.nodeId} className="request-item">
            <RequestLine apiCall={asApiCall(r)} detail={<> · {String(r.call?.component ?? '')}</>} onClick={() => openCode(...requestCode(r))} />
            {typeof r.call?.hook === 'string' && <Badge tone="info" title="İsteği saran hook zinciri">{r.call.hook}</Badge>}
            {r.details.resolution === 'UNRESOLVED' && <Badge tone="danger" title={String(r.details.unresolvedReason ?? '')}>çözülemedi</Badge>}
            {(r.children ?? []).map((e) => (
              <div key={e.nodeId} className="small request-target">→ <b>{e.service}</b> <span className="mono">{e.name}</span></div>
            ))}
          </li>
        ))}
      </ul>
      {requests.length > 0 && (
        <button type="button" className="btn btn-small" onClick={() => navigate('tree', { root: id })}>Çağrı ağacında aç</button>
      )}
    </section>
  );
}

function asApiCall(r: CallTreeNode) {
  return { id: r.nodeId, label: 'ApiCall' as const, name: r.name, service: r.service, properties: r.details };
}

/** ApiCall'un kodu ve isteğin atıldığı component, kullanım satırı vurgulu. */
function requestCode(r: CallTreeNode): [CodeTab[], number] {
  const tabs: CodeTab[] = [{ nodeId: r.nodeId, caption: 'ApiCall' }];
  const call = r.call ?? {};
  if (typeof call.componentId === 'string') {
    const line = typeof call.line === 'number' ? call.line : undefined;
    tabs.push({ nodeId: call.componentId, caption: `${String(call.component)}${line ? ` · satır ${line}` : ''}`, line });
  }
  return [tabs, 0];
}

function formatValue(v: unknown): string {
  if (Array.isArray(v)) return v.join(', ');
  if (v === null || v === undefined) return '—';
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
