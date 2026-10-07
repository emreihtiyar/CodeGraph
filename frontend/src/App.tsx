import { CodePanel } from './components/CodePanel.tsx';
import { SearchBox } from './components/SearchBox.tsx';
import { navigate, useRoute, type View } from './lib/route.ts';
import { useAppState } from './state.tsx';
import { AnalysisView } from './views/AnalysisView.tsx';
import { CallTreeView } from './views/CallTreeView.tsx';
import { SystemView } from './views/SystemView.tsx';
import { TraceView } from './views/TraceView.tsx';

const NAV: { view: View; title: string }[] = [
  { view: 'system', title: 'Sistem' },
  { view: 'trace', title: 'Akış izi' },
  { view: 'tree', title: 'Çağrı ağacı' },
  { view: 'analysis', title: 'Analiz' },
];

export function App() {
  const route = useRoute();
  const { code, latestRun } = useAppState();
  const running = latestRun?.state === 'RUNNING';

  return (
    <div className={code ? 'app with-code' : 'app'}>
      <header className="topbar">
        <a className="brand" href="#/system">
          <img src="/favicon.svg" alt="" width={22} height={22} /> CodeGraph
        </a>
        <nav className="nav">
          {NAV.map((n) => (
            <button key={n.view} type="button" className={route.view === n.view ? 'active' : ''} onClick={() => navigate(n.view)}>
              {n.title}
            </button>
          ))}
        </nav>
        <SearchBox />
        <button type="button" className={`analysis-indicator state-${latestRun?.state ?? 'none'}`} onClick={() => navigate('analysis')}
          title="Analiz paneli">
          <span className="dot" />
          {running ? 'Analiz sürüyor' : latestRun ? `Son analiz: ${new Date(latestRun.startedAt ?? '').toLocaleString('tr-TR', { dateStyle: 'short', timeStyle: 'short' })}` : 'Analiz yok'}
        </button>
      </header>
      {running && route.view !== 'analysis' && (
        <div className="banner">Analiz sürüyor; graf yeniden yüklenirken sonuçlar eksik görünebilir. Bitince görünüm yenilenir.</div>
      )}
      <main className="content">
        <div className="view-host">
          {route.view === 'system' && <SystemView focus={route.params.focus} />}
          {route.view === 'trace' && <TraceView params={route.params} />}
          {route.view === 'tree' && <CallTreeView params={route.params} />}
          {route.view === 'analysis' && <AnalysisView runId={route.params.run} />}
        </div>
        <CodePanel />
      </main>
    </div>
  );
}
