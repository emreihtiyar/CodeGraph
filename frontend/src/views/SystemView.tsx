import { useMemo, useState, type CSSProperties } from 'react';
import { api } from '../api/client.ts';
import type { ServiceDependency, ServiceList } from '../api/types.ts';
import { GraphCanvas } from '../components/GraphCanvas.tsx';
import { Badge, Empty, ErrorBox, Spinner } from '../components/ui.tsx';
import { DB_NODE, serviceColumn, SYSTEM_COLUMNS, systemElements } from '../lib/graphElements.ts';
import { LAYER_COLORS, LAYER_TITLES } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState } from '../state.tsx';

/** Sistem görünümü (FR-15): servis düzeyinde katmanlı graf, katman filtresi, seçilen servisin komşuları. */
export function SystemView({ focus }: { focus?: string }) {
  const { graphVersion, latestRun } = useAppState();
  const services = useAsync((signal) => api.services(undefined, signal), [graphVersion]);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const visible = useMemo(() => new Set(SYSTEM_COLUMNS.filter((c) => !hidden.has(c))), [hidden]);
  const elements = useMemo(
    () => (services.data ? systemElements(services.data, visible) : []),
    [services.data, visible],
  );

  if (services.error) return <ErrorBox error={services.error} onRetry={services.reload} />;
  if (!services.data) return <Spinner />;
  if (services.data.services.length === 0) {
    return (
      <Empty title={latestRun === null ? 'Henüz analiz yapılmadı' : 'Grafta servis yok'}>
        <p>Repoları analiz edip grafı oluşturmak için analizi başlatın.</p>
        <button type="button" className="btn btn-primary" onClick={() => navigate('analysis')}>Analiz paneline git</button>
      </Empty>
    );
  }
  const present = new Set(services.data.services.map((s) => serviceColumn(s.layer)));
  if (services.data.services.some((s) => s.tableCount > 0)) present.add('DB');

  return (
    <div className="view">
      <div className="view-row">
      <div className="view-main">
        <div className="toolbar">
          <span className="toolbar-label">Katmanlar</span>
          {SYSTEM_COLUMNS.filter((c) => present.has(c)).map((c) => (
            <button
              key={c}
              type="button"
              className={hidden.has(c) ? 'chip off' : 'chip'}
              style={{ '--chip-color': LAYER_COLORS[c] } as CSSProperties}
              onClick={() => setHidden((h) => {
                const next = new Set(h);
                if (next.has(c)) next.delete(c);
                else next.add(c);
                return next;
              })}
            >
              {LAYER_TITLES[c]}
            </button>
          ))}
          <span className="toolbar-spacer" />
          <span className="muted small">Kenar etiketi: çağrı sayısı · kesikli: analiz dışı / kullanılmayan</span>
        </div>
        <GraphCanvas
          elements={elements}
          layout="preset"
          selectedId={focus ?? null}
          onNodeTap={(id) => navigate('system', { focus: id })}
          onBackgroundTap={() => navigate('system')}
        />
      </div>
      {focus && (
        <aside className="inspector">
          {focus === DB_NODE
            ? <DatabaseInspector list={services.data} />
            : <ServiceInspector key={`${focus}:${graphVersion}`} name={focus} />}
        </aside>
      )}
      </div>
    </div>
  );
}

function DatabaseInspector({ list }: { list: ServiceList }) {
  return (
    <div className="inspector-body">
      <h2>Veritabanı</h2>
      <p className="muted">Tabloya erişen servisler. Tablo kullanımları için arama kutusunda tablo adını arayın.</p>
      <ul className="plain-list">
        {list.services.filter((s) => s.tableCount > 0).map((s) => (
          <li key={s.name}>
            <button type="button" className="link" onClick={() => navigate('system', { focus: s.name })}>{s.name}</button>
            <span className="muted small"> {s.tableCount} tablo</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ServiceInspector({ name }: { name: string }) {
  const { openCode } = useAppState();
  const deps = useAsync((signal) => api.serviceDependencies(name, signal), [name]);
  const frontend = deps.data?.service.layer === 'FRONTEND';
  const screens = useAsync((signal) => (frontend ? api.screens(name, signal) : undefined), [name, frontend]);
  const endpoints = useAsync(
    (signal) => (deps.data && !frontend ? api.endpoints(name, signal) : undefined),
    [name, frontend, deps.data],
  );

  if (deps.error) return <ErrorBox error={deps.error} />;
  if (!deps.data) return <Spinner />;
  const s = deps.data.service;
  const column = serviceColumn(s.layer);
  return (
    <div className="inspector-body">
      <h2>{s.name}</h2>
      <div className="row-wrap">
        <Badge tone="info">{LAYER_TITLES[column]}</Badge>
        {!s.analyzed && <Badge tone="warn" title="Feign hedefi; kodu analize dahil değil">analiz dışı</Badge>}
        {s.gatewayPrefix && <Badge>gateway /{s.gatewayPrefix}</Badge>}
        {s.contextPath && <Badge>context-path {s.contextPath}</Badge>}
        {s.repo && <Badge>repo {s.repo}</Badge>}
      </div>

      {frontend && (
        <section>
          <h3>Ekranlar</h3>
          {screens.data?.map((sc) => (
            <div key={sc.id} className="list-row">
              <span className="mono">{sc.routePath}</span>
              <span className="muted small">{sc.name}</span>
              <span className="row-actions">
                <button type="button" className="btn btn-small" onClick={() => navigate('trace', { from: sc.id, dir: 'forward' })}>İz →</button>
              </span>
            </div>
          )) ?? <Spinner />}
        </section>
      )}

      {!frontend && s.analyzed && (
        <section>
          <h3>Endpoint'ler {endpoints.data && <span className="muted">({endpoints.data.length})</span>}</h3>
          {endpoints.data?.map((e) => (
            <div key={e.id} className="list-row">
              <span className="mono"><b>{e.httpMethod}</b> {e.path}</span>
              <span className="row-actions">
                <button type="button" className="btn btn-small" title="Tablolara ileri iz" onClick={() => navigate('trace', { from: e.id, dir: 'forward' })}>İz →</button>
                <button type="button" className="btn btn-small" title="Kullanan ekranlar" onClick={() => navigate('trace', { from: e.id, dir: 'backward' })}>← İz</button>
                <button type="button" className="btn btn-small" title="Metot çağrı ağacı" onClick={() => navigate('tree', { root: e.id })}>Ağaç</button>
                <button type="button" className="btn btn-small" onClick={() => openCode([{ nodeId: e.id, caption: 'Handler' }])}>Kod</button>
              </span>
            </div>
          )) ?? <Spinner />}
        </section>
      )}

      <Dependencies title="Çağırdığı servisler" items={deps.data.outgoing} arrow="→" />
      <Dependencies title="Onu çağıran servisler" items={deps.data.incoming} arrow="←" />
    </div>
  );
}

function Dependencies({ title, items, arrow }: { title: string; items: ServiceDependency[]; arrow: string }) {
  const { openCode } = useAppState();
  return (
    <section>
      <h3>{title} <span className="muted">({items.length})</span></h3>
      {items.length === 0 && <p className="muted small">Yok</p>}
      {items.map((d) => (
        <details key={d.service} className="dependency" open={items.length <= 3}>
          <summary>
            {arrow}{' '}
            <button type="button" className="link" onClick={() => navigate('system', { focus: d.service })}>{d.service}</button>
            <span className="muted small"> {d.callCount} çağrı</span>
            {!d.analyzed && <Badge tone="warn">analiz dışı</Badge>}
          </summary>
          <ul className="plain-list links">
            {d.links.map((l) => (
              <li key={l.sourceId + (l.targetId ?? '')}>
                <button type="button" className="link mono" onClick={() => openCode([{ nodeId: l.sourceId, caption: l.sourceKind }])}>
                  {l.source}
                </button>
                {' → '}
                {l.target
                  ? <button type="button" className="link mono" onClick={() => navigate('trace', { from: l.targetId!, dir: 'forward' })}>{l.target}</button>
                  : <span className="muted">?</span>}
                {l.resolution && l.resolution !== 'RESOLVED' && (
                  <Badge tone={l.resolution === 'UNRESOLVED' ? 'danger' : 'warn'} title={l.reason ?? undefined}>{l.resolution}</Badge>
                )}
              </li>
            ))}
          </ul>
        </details>
      ))}
    </section>
  );
}
