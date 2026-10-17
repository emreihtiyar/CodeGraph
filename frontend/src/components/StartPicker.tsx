import { useState } from 'react';
import { api } from '../api/client.ts';
import { ErrorBox, Spinner } from './ui.tsx';
import { LAYER_COLORS, LAYER_TITLES } from '../lib/labels.ts';
import { filterScreens, groupEndpoints, handlerMethod, pickerServices, splitServiceName } from '../lib/picker.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState } from '../state.tsx';

interface Props {
  title: string;
  hint: string;
  /** Ekranlar da listelensin mi (akış izi); çağrı ağacında yalnızca endpoint'ler. */
  withScreens: boolean;
  /** Seçili servis (URL'deki {@code svc}). */
  service: string | undefined;
  onService: (name: string) => void;
  onPick: (nodeId: string) => void;
}

/** İki adımlı başlangıç seçici: önce servis, sonra o servisin ekranı ya da endpoint'i. */
export function StartPicker({ title, hint, withScreens, service, onService, onPick }: Props) {
  const { graphVersion } = useAppState();
  const services = useAsync((signal) => api.services(undefined, signal), [graphVersion]);
  const groups = services.data ? pickerServices(services.data.services, withScreens) : [];
  const selected = services.data?.services.find((s) => s.name === service);

  return (
    <div className="start-picker">
      <aside className="sp-services">
        <div className="sp-head">
          <h2>{title}</h2>
          <p className="muted small">{hint}</p>
        </div>
        {services.error && <ErrorBox error={services.error} onRetry={services.reload} />}
        {services.loading && <Spinner />}
        {services.data && groups.length === 0 && <p className="muted small sp-pad">Henüz analiz edilmiş servis yok.</p>}
        {groups.map((g) => (
          <section key={g.layer}>
            <div className="sp-layer" style={{ color: LAYER_COLORS[g.layer] }}>{LAYER_TITLES[g.layer] ?? g.layer}</div>
            {g.services.map((s) => {
              const { namespace, short } = splitServiceName(s.name);
              const count = s.layer === 'FRONTEND' ? s.screenCount : s.endpointCount;
              return (
                <button key={s.id} type="button" title={s.name}
                  className={`sp-service${s.name === service ? ' active' : ''}`} onClick={() => onService(s.name)}>
                  <span className="sp-service-name">{short}</span>
                  {namespace && <span className="sp-ns">{namespace}</span>}
                  <span className="sp-count">{count}</span>
                </button>
              );
            })}
          </section>
        ))}
      </aside>
      <main className="sp-items">
        {selected
          ? <ServiceItems key={selected.name} service={selected.name} withScreens={withScreens && selected.screenCount > 0} onPick={onPick} />
          : <div className="sp-placeholder muted">← Soldan bir servis seçin</div>}
      </main>
    </div>
  );
}

function ServiceItems({ service, withScreens, onPick }: { service: string; withScreens: boolean; onPick: (id: string) => void }) {
  const { graphVersion } = useAppState();
  const [filter, setFilter] = useState('');
  const endpoints = useAsync((signal) => api.endpoints(service, signal), [service, graphVersion]);
  const screens = useAsync((signal) => (withScreens ? api.screens(service, signal) : undefined), [service, withScreens, graphVersion]);
  const groups = endpoints.data ? groupEndpoints(endpoints.data, filter) : [];
  const shownScreens = screens.data ? filterScreens(screens.data, filter) : [];
  const total = groups.reduce((n, g) => n + g.endpoints.length, 0) + shownScreens.length;

  return (
    <>
      <div className="sp-items-head">
        <h3 className="mono">{service}</h3>
        <input autoFocus className="sp-filter" value={filter} placeholder="Path, controller ya da metot ara…"
          onChange={(e) => setFilter(e.target.value)} />
        <span className="muted small">{total} sonuç</span>
      </div>
      <div className="sp-list">
        {(endpoints.error || screens.error) && <ErrorBox error={(endpoints.error ?? screens.error)!} />}
        {(endpoints.loading || screens.loading) && <Spinner />}
        {shownScreens.length > 0 && (
          <section>
            <div className="sp-group">Ekranlar <span className="muted">({shownScreens.length})</span></div>
            {shownScreens.map((s) => (
              <button key={s.id} type="button" className="sp-row" onClick={() => onPick(s.id)}>
                <span className="method method-screen">EKRAN</span>
                <span className="mono sp-path">{s.routePath}</span>
                <span className="sp-handler">{s.name}</span>
              </button>
            ))}
          </section>
        )}
        {groups.map((g) => (
          <section key={g.title}>
            <div className="sp-group">{g.title} <span className="muted">({g.endpoints.length})</span></div>
            {g.endpoints.map((e) => (
              <button key={e.id} type="button" className="sp-row" title={e.handler ?? undefined} onClick={() => onPick(e.id)}>
                <span className={`method method-${e.httpMethod.toLowerCase()}`}>{e.httpMethod}</span>
                <span className="mono sp-path">{e.path}</span>
                <span className="sp-handler">{handlerMethod(e.handler)}</span>
              </button>
            ))}
          </section>
        ))}
        {endpoints.data && total === 0 && <p className="muted small sp-pad">Eşleşen kayıt yok.</p>}
      </div>
    </>
  );
}
