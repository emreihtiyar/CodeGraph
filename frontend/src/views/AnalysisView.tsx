import type { ReactNode } from 'react';
import { api } from '../api/client.ts';
import type { AnalysisReport, AnalysisRunView, CallIssue, LinkIssue } from '../api/types.ts';
import { Badge, Empty, ErrorBox, Spinner } from '../components/ui.tsx';
import { LAYER_TITLES } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState } from '../state.tsx';

const STATE_TONE = { RUNNING: 'info', SUCCEEDED: 'ok', FAILED: 'danger', IDLE: 'neutral' } as const;
const STATE_TITLE = { RUNNING: 'Çalışıyor', SUCCEEDED: 'Başarılı', FAILED: 'Başarısız', IDLE: 'Boşta' } as const;

/** Analiz paneli (FR-20): başlatma, ilerleme ve FR-12 raporu. */
export function AnalysisView({ runId }: { runId?: string }) {
  const { latestRun, analysisError, startAnalysis, graphVersion } = useAppState();
  const running = latestRun?.state === 'RUNNING';
  const runs = useAsync((signal) => api.analysisRuns(signal), [graphVersion, latestRun?.runId, latestRun?.state]);
  const selectedId = runId ?? (latestRun && !running ? latestRun.runId : undefined);
  const selected = useAsync(
    (signal) => (selectedId ? api.analysisRun(selectedId, signal) : undefined),
    [selectedId, graphVersion],
  );

  return (
    <div className="view padded analysis">
      <section className="card status-card">
        <div>
          <h2>Analiz</h2>
          <p className="muted">Yapılandırmadaki tüm repolar baştan analiz edilir ve graf yeniden oluşturulur.</p>
        </div>
        <div className="status-side">
          {latestRun && <RunStatus run={latestRun} />}
          <button type="button" className="btn btn-primary" disabled={running} onClick={() => void startAnalysis()}>
            {running ? 'Analiz sürüyor…' : 'Analizi başlat'}
          </button>
        </div>
      </section>
      {analysisError && <ErrorBox error={analysisError} />}
      {latestRun === null && (
        <Empty title="Henüz analiz yapılmadı">
          <p>Repolar <code>application.yaml</code> içindeki <code>codegraph.repos</code> ya da
            <code> codegraph.repos-file</code> ile tanımlanır.</p>
        </Empty>
      )}
      <div className="analysis-columns">
        <aside className="card runs">
          <h3>Geçmiş</h3>
          {runs.error && <ErrorBox error={runs.error} />}
          {runs.data?.map((r) => (
            <button
              key={r.runId}
              type="button"
              className={r.runId === selectedId ? 'run-item active' : 'run-item'}
              onClick={() => navigate('analysis', { run: r.runId })}
            >
              <Badge tone={STATE_TONE[r.state]}>{STATE_TITLE[r.state]}</Badge>
              <span className="small">{formatTime(r.startedAt)}</span>
              {r.issueCount != null && <span className="muted small">{r.issueCount} sorun</span>}
            </button>
          ))}
        </aside>
        <div className="report">
          {selected.error && <ErrorBox error={selected.error} />}
          {selected.loading && !selected.data && selectedId && <Spinner />}
          {selected.data?.error && <ErrorBox error={selected.data.error} />}
          {selected.data?.report && <Report report={selected.data.report} />}
        </div>
      </div>
    </div>
  );
}

export function RunStatus({ run }: { run: AnalysisRunView }) {
  const s = run.status;
  return (
    <div className="run-status">
      <Badge tone={STATE_TONE[run.state]}>{STATE_TITLE[run.state]}</Badge>
      {s && run.state === 'RUNNING' ? (
        <div className="progress-block">
          <div className="small">
            {phaseTitle(s.phase)} {s.total > 0 && `${s.done}/${s.total}`} {s.current && <span className="muted">· {s.current}</span>}
          </div>
          <div className="progress"><div style={{ width: `${s.total > 0 ? (100 * s.done) / s.total : 5}%` }} /></div>
        </div>
      ) : (
        <span className="muted small">
          {formatTime(run.startedAt)}{run.durationMs != null && ` · ${(run.durationMs / 1000).toFixed(1)} sn`}
        </span>
      )}
    </div>
  );
}

function Report({ report }: { report: AnalysisReport }) {
  return (
    <>
      <section className="card">
        <h3>Özet</h3>
        <div className="stats">
          <Stat label="Süre" value={`${(report.durationMs / 1000).toFixed(1)} sn`} />
          {['Service', 'Screen', 'Endpoint', 'Method', 'FeignMethod', 'Table'].map((k) => (
            <Stat key={k} label={k} value={String(report.nodeCounts[k] ?? 0)} />
          ))}
        </div>
      </section>
      <section className="card">
        <h3>Repolar</h3>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Repo</th><th>Katman</th><th>Dosya</th><th>Sınıf</th><th>Metot</th><th>Çağrı</th><th>Endpoint</th>
                <th>Feign</th><th>Entity</th><th>ApiCall</th><th>Ekran</th><th>Sorun</th><th>Commit</th>
              </tr>
            </thead>
            <tbody>
              {report.repos.map((r) => (
                <tr key={r.name}>
                  <td><b>{r.name}</b></td>
                  <td>{LAYER_TITLES[r.layer] ?? r.layer}</td>
                  <td>{r.files}</td><td>{r.classes}</td><td>{r.methods}</td><td>{r.calls}</td><td>{r.endpoints}</td>
                  <td>{r.feignMethods}</td><td>{r.entities}</td><td>{r.apiCalls}</td><td>{r.screens}</td>
                  <td>{r.issues > 0 ? <Badge tone="warn">{r.issues}</Badge> : 0}</td>
                  <td className="mono small">{r.commitHash?.slice(0, 8) ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      <Issues title="Çözülemeyen bağlantılar" hint="Zincirin koptuğu yerler: statik çözülemeyen URL'ler, bulunamayan servis ya da endpoint."
        count={report.unresolvedLinks.length} tone="danger">
        <LinkTable issues={report.unresolvedLinks} />
      </Issues>
      <Issues title="Belirsiz bağlantılar" count={report.ambiguousLinks.length} tone="warn">
        <LinkTable issues={report.ambiguousLinks} />
      </Issues>
      <Issues title="Belirsiz metot çağrıları" hint="Birden çok implementasyondan hangisinin çağrıldığı statik olarak belirlenemedi."
        count={report.ambiguousCalls.length} tone="warn">
        <CallTable issues={report.ambiguousCalls} />
      </Issues>
      <Issues title="Çözülemeyen metot çağrıları" hint="Proje dışı (JDK/kütüphane) olmayan ama hedefi bulunamayan çağrılar."
        count={report.unresolvedCalls.length} tone="neutral">
        <CallTable issues={report.unresolvedCalls} />
      </Issues>
      <Issues title="Parse sorunları" count={report.parseIssues.length} tone="danger">
        <table className="data-table">
          <tbody>
            {report.parseIssues.map((p, i) => (
              <tr key={i}><td>{p.repo}</td><td className="mono small">{p.file}:{p.line}</td><td>{p.message}</td></tr>
            ))}
          </tbody>
        </table>
      </Issues>
      <Issues title="Uyarılar" count={report.warnings.length} tone="neutral">
        <ul className="plain-list small">{report.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
      </Issues>
    </>
  );
}

function Issues({ title, hint, count, tone, children }: {
  title: string; hint?: string; count: number; tone: 'danger' | 'warn' | 'neutral'; children: ReactNode;
}) {
  return (
    <details className="card issues" open={count > 0 && count <= 20}>
      <summary>
        <h3>{title}</h3> <Badge tone={count === 0 ? 'ok' : tone}>{count}</Badge>
      </summary>
      {hint && <p className="muted small">{hint}</p>}
      {count > 0 && <div className="table-scroll">{children}</div>}
    </details>
  );
}

function LinkTable({ issues }: { issues: LinkIssue[] }) {
  return (
    <table className="data-table">
      <thead><tr><th>Tür</th><th>Repo</th><th>Kaynak</th><th>Hedef servis</th><th>Neden / adaylar</th></tr></thead>
      <tbody>
        {issues.map((i, k) => (
          <tr key={k}>
            <td>{i.kind === 'API_CALL' ? 'ApiCall' : 'Feign'}</td>
            <td>{i.repo}</td>
            <td className="mono small break">{i.source}</td>
            <td>{i.targetService ?? '—'}</td>
            <td className="small">{i.reason}{i.candidates.length > 0 && <div className="mono">{i.candidates.join(' | ')}</div>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function CallTable({ issues }: { issues: CallIssue[] }) {
  return (
    <table className="data-table">
      <thead><tr><th>Repo</th><th>Çağıran</th><th>Satır</th><th>İfade</th><th>Adaylar</th></tr></thead>
      <tbody>
        {issues.map((i, k) => (
          <tr key={k}>
            <td>{i.repo}</td>
            <td className="mono small break">{i.caller}</td>
            <td>{i.line}</td>
            <td className="mono small break">{i.expression}</td>
            <td className="mono small">{i.candidates.join(' | ')}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat">
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}

function phaseTitle(phase: string | null): string {
  switch (phase) {
    case 'CONFIG': return 'Yapılandırma okunuyor';
    case 'ANALYZE': return 'Repolar analiz ediliyor';
    case 'LINK': return 'Servisler bağlanıyor';
    case 'LOAD': return 'Graf yükleniyor';
    case 'REPORT': return 'Rapor hazırlanıyor';
    default: return phase ?? '';
  }
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('tr-TR', { dateStyle: 'short', timeStyle: 'medium' });
}
