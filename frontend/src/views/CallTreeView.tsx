import { useEffect, useState } from 'react';
import { api } from '../api/client.ts';
import type { CallTreeNode, CallTreeOptions } from '../api/types.ts';
import { Badge, Empty, ErrorBox, Spinner, Toggle } from '../components/ui.tsx';
import { KIND_LABEL, LABELS } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { ancestorIds, loadedKeys, nodeAt, pathKey, withChildren, type TreePath } from '../lib/tree.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState, type CodeTab } from '../state.tsx';

export interface TreeParams {
  root?: string;
  depth?: string;
  ext?: string;
  acc?: string;
  cross?: string;
  hide?: string;
}

function options(params: TreeParams): CallTreeOptions {
  const depth = Number(params.depth ?? 2);
  return {
    depth: Number.isFinite(depth) ? Math.min(10, Math.max(1, depth)) : 2,
    includeAccessors: params.acc === '1',
    onlyExternal: params.ext === '1',
    crossService: params.cross !== '0',
    hidden: (params.hide ?? '').split(',').map((s) => s.trim()).filter(Boolean),
  };
}

/** Metot çağrı ağacı (FR-22) ve filtreleri (FR-23); seviyeler tembel yüklenir. */
export function CallTreeView({ params }: { params: TreeParams }) {
  const { graphVersion } = useAppState();
  const opts = options(params);
  const [tree, setTree] = useState<CallTreeNode | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [loadingKeys, setLoadingKeys] = useState<Set<string>>(new Set());
  const [expandError, setExpandError] = useState<string | null>(null);
  const [hideDraft, setHideDraft] = useState(params.hide ?? '');
  useEffect(() => setHideDraft(params.hide ?? ''), [params.hide]);

  const optionsKey = JSON.stringify(opts);
  const initial = useAsync(
    (signal) => (params.root ? api.callTree(params.root, opts, [], signal) : undefined),
    [params.root, optionsKey, graphVersion],
  );
  useEffect(() => {
    if (!initial.data) return;
    setTree(initial.data);
    setExpanded(new Set(loadedKeys(initial.data)));
    setExpandError(null);
  }, [initial.data]);

  const update = (changes: Partial<TreeParams>) => navigate('tree', { ...params, ...changes });

  if (!params.root) return <RootPicker />;

  const toggle = async (path: TreePath) => {
    if (!tree) return;
    const key = pathKey(path);
    const node = nodeAt(tree, path);
    if (expanded.has(key)) {
      setExpanded((s) => { const n = new Set(s); n.delete(key); return n; });
      return;
    }
    if (node.children === null && node.hasChildren) {
      setLoadingKeys((s) => new Set(s).add(key));
      try {
        const sub = await api.callTree(node.nodeId, { ...opts, depth: 1 }, ancestorIds(tree, path));
        setTree((t) => (t ? withChildren(t, path, sub.children ?? []) : t));
        setExpandError(null);
      } catch (e) {
        setExpandError((e as Error).message);
        return;
      } finally {
        setLoadingKeys((s) => { const n = new Set(s); n.delete(key); return n; });
      }
    }
    setExpanded((s) => new Set(s).add(key));
  };

  return (
    <div className="view">
      <div className="toolbar view-toolbar">
        <Toggle label="Yalnızca dış çağrı / DB dalları" checked={opts.onlyExternal} onChange={(v) => update({ ext: v ? '1' : undefined })} />
        <Toggle label="Getter/setter'ları gizle" checked={!opts.includeAccessors} onChange={(v) => update({ acc: v ? undefined : '1' })} />
        <Toggle label="Servisler arası devam" checked={opts.crossService} onChange={(v) => update({ cross: v ? undefined : '0' })}
          title="Feign çağrısından hedef servisin endpoint'ine ve handler'ına geç" />
        <label className="depth" title="İlk yüklemede açılacak seviye sayısı">
          Derinlik
          <select value={opts.depth} onChange={(e) => update({ depth: e.target.value })}>
            {Array.from({ length: 10 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{d}</option>)}
          </select>
        </label>
        <label className="hide-input" title="Virgülle ayrılmış sınıf adları ya da paket önekleri">
          Gizle
          <input value={hideDraft} placeholder="ValidationUtils, com.bank.util"
            onChange={(e) => setHideDraft(e.target.value)}
            onBlur={() => update({ hide: hideDraft || undefined })}
            onKeyDown={(e) => e.key === 'Enter' && update({ hide: hideDraft || undefined })} />
        </label>
        <span className="toolbar-spacer" />
        {tree && (
          <>
            <button type="button" className="btn btn-small" onClick={() => setExpanded(new Set(loadedKeys(tree)))}>Hepsini aç</button>
            <button type="button" className="btn btn-small" onClick={() => setExpanded(new Set())}>Kapat</button>
          </>
        )}
      </div>
      <div className="tree-scroll">
        {initial.error && <ErrorBox error={initial.error} onRetry={initial.reload} />}
        {expandError && <ErrorBox error={expandError} />}
        {initial.loading && !tree && <Spinner />}
        {tree && (
          <ul className="call-tree" role="tree">
            <TreeRow node={tree} path={[]} parent={null} index={0} expanded={expanded} loading={loadingKeys} onToggle={toggle} />
          </ul>
        )}
      </div>
    </div>
  );
}

interface RowProps {
  node: CallTreeNode;
  path: TreePath;
  parent: CallTreeNode | null;
  index: number;
  expanded: Set<string>;
  loading: Set<string>;
  onToggle: (path: TreePath) => void;
}

function TreeRow({ node, path, parent, index, expanded, loading, onToggle }: RowProps) {
  const { openCode } = useAppState();
  const key = pathKey(path);
  const isOpen = expanded.has(key) && node.children !== null;
  const meta = LABELS[KIND_LABEL[node.kind]];
  const call = node.call ?? {};
  const crossService = parent && node.service && parent.service !== node.service;

  const showCode = () => {
    const tabs: CodeTab[] = [{ nodeId: node.nodeId, caption: meta.title === 'Metot' ? 'Metot' : meta.title }];
    if (parent && typeof call.line === 'number') {
      tabs.push({ nodeId: parent.nodeId, caption: `Çağrı yeri · satır ${call.line}`, line: call.line });
    }
    openCode(tabs);
  };

  return (
    <li role="treeitem" aria-expanded={node.hasChildren ? isOpen : undefined}>
      <div className={`tree-row kind-${node.kind.toLowerCase()}`}>
        <button
          type="button"
          className="tree-toggle"
          disabled={!node.hasChildren || node.recursive}
          onClick={() => onToggle(path)}
          aria-label={isOpen ? 'Kapat' : 'Aç'}
        >
          {loading.has(key) ? '…' : node.hasChildren && !node.recursive ? (isOpen ? '▾' : '▸') : ''}
        </button>
        {parent && <span className="tree-order">{index + 1}</span>}
        <span className="tree-icon" style={{ color: meta.color }} title={meta.title}>{meta.icon}</span>
        <button type="button" className="tree-name" onClick={showCode}>{node.name}</button>
        {crossService && <Badge tone="info" title="Servis değişti">{node.service}</Badge>}
        {!parent && node.service && <span className="muted small">{node.service}</span>}
        {node.visibility && node.kind === 'METHOD' && <Badge tone={node.visibility === 'PUBLIC' ? 'neutral' : 'purple'}>{node.visibility.toLowerCase()}</Badge>}
        {node.isStatic && <Badge tone="purple">static</Badge>}
        {call.conditional === true && <Badge tone="warn" title={typeof call.conditionText === 'string' ? call.conditionText : undefined}>koşullu</Badge>}
        {call.inLoop === true && <Badge tone="warn">döngüde</Badge>}
        {call.async === true && <Badge tone="info">async</Badge>}
        {call.viaLambda === true && <Badge>lambda</Badge>}
        {typeof call.resolution === 'string' && call.resolution !== 'RESOLVED' && (
          <Badge tone={call.resolution === 'UNRESOLVED' ? 'danger' : 'warn'}>{call.resolution}</Badge>
        )}
        {node.recursive && <Badge tone="danger" title="Bu metot ağaçta atalarından biri">özyinelemeli</Badge>}
        <Details node={node} />
        {typeof call.line === 'number' && <span className="muted small tree-line">satır {call.line}</span>}
      </div>
      {isOpen && node.children!.length > 0 && (
        <ul role="group">
          {node.children!.map((c, i) => (
            <TreeRow key={i} node={c} path={[...path, i]} parent={node} index={i}
              expanded={expanded} loading={loading} onToggle={onToggle} />
          ))}
        </ul>
      )}
    </li>
  );
}

function Details({ node }: { node: CallTreeNode }) {
  const d = node.details;
  if (node.kind === 'FEIGN_METHOD') {
    return (
      <span className="tree-details">
        → <b>{String(d.targetService ?? '?')}</b> {String(d.httpMethod ?? '')} {String(d.path ?? '')}
        {d.resolution === 'UNRESOLVED' && <Badge tone="danger" title={String(d.unresolvedReason ?? '')}>çözülemedi</Badge>}
        {d.resolution === 'AMBIGUOUS' && <Badge tone="warn">belirsiz</Badge>}
      </span>
    );
  }
  if (node.kind === 'REPOSITORY_METHOD') {
    const tables = (d.tables as string[] | undefined) ?? [];
    return (
      <span className="tree-details">
        <Badge tone={d.accessType === 'WRITE' ? 'warn' : 'ok'}>{String(d.accessType ?? '?')}</Badge> {tables.join(', ')}
      </span>
    );
  }
  if (node.kind === 'ENDPOINT') {
    if (node.name.includes(String(d.path ?? ''))) return null;
    return <span className="tree-details mono">{String(d.httpMethod ?? '')} {String(d.path ?? '')}</span>;
  }
  return null;
}

function RootPicker() {
  const { graphVersion } = useAppState();
  const endpoints = useAsync((signal) => api.endpoints(undefined, signal), [graphVersion]);
  const byService = new Map<string, NonNullable<typeof endpoints.data>>();
  endpoints.data?.forEach((e) => byService.set(e.service, [...(byService.get(e.service) ?? []), e]));
  return (
    <div className="view padded">
      <Empty title="Çağrı ağacının kökünü seçin">
        <p>Bir endpoint seçin ya da üstteki arama kutusundan bir metot arayın.</p>
      </Empty>
      {endpoints.error && <ErrorBox error={endpoints.error} />}
      <div className="picker-columns">
        {[...byService].map(([service, list]) => (
          <section key={service}>
            <h3>{service}</h3>
            {list.map((e) => (
              <button key={e.id} type="button" className="picker-item" onClick={() => navigate('tree', { root: e.id })}>
                <span className="mono"><b>{e.httpMethod}</b> {e.path}</span>
                {e.handler && <span className="muted small"> {e.handler}</span>}
              </button>
            ))}
          </section>
        ))}
      </div>
    </div>
  );
}
