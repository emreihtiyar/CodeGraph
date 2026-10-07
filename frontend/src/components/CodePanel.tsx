import { useEffect, useMemo, useRef } from 'react';
import { api, ApiError } from '../api/client.ts';
import { highlightLines } from '../lib/codeLines.ts';
import { useAsync } from '../lib/useAsync.ts';
import { useAppState } from '../state.tsx';
import { ErrorBox, Spinner } from './ui.tsx';

/** Kod paneli (FR-18): satır numaraları, sözdizimi renklendirme, çağrı satırının vurgusu. */
export function CodePanel() {
  const { code, openCode, closeCode } = useAppState();
  if (!code) return null;
  const tab = code.tabs[code.active] ?? code.tabs[0];
  return (
    <aside className="code-panel" aria-label="Kod paneli">
      <div className="code-panel-bar">
        <div className="tabs">
          {code.tabs.map((t, i) => (
            <button
              key={`${t.nodeId}-${i}`}
              type="button"
              className={i === code.active ? 'tab active' : 'tab'}
              onClick={() => openCode(code.tabs, i)}
            >
              {t.caption}
            </button>
          ))}
        </div>
        <button type="button" className="icon-btn" title="Kapat" onClick={closeCode}>✕</button>
      </div>
      <CodeContent key={`${tab.nodeId}:${tab.line ?? ''}`} nodeId={tab.nodeId} line={tab.line} />
    </aside>
  );
}

function CodeContent({ nodeId, line }: { nodeId: string; line?: number }) {
  const { data, error, loading } = useAsync((signal) => api.code(nodeId, signal), [nodeId]);
  const lines = useMemo(() => (data ? highlightLines(data.code, data.language) : []), [data]);
  const highlighted = useRef<HTMLDivElement>(null);

  useEffect(() => {
    highlighted.current?.scrollIntoView({ block: 'center' });
  }, [lines]);

  if (loading && !data) return <Spinner />;
  if (error) {
    return error instanceof ApiError && error.status === 404
      ? <div className="code-missing">{error.message}</div>
      : <ErrorBox error={error} />;
  }
  if (!data) return null;
  const first = data.startLine ?? 1;
  return (
    <div className="code-content">
      <header className="code-header">
        <div className="code-title">{data.name}</div>
        <div className="code-meta">
          {data.repo && <span className="repo">{data.repo}</span>}
          <span className="file" title={data.file ?? ''}>{data.file}</span>
          {data.startLine != null && <span>satır {data.startLine}–{data.endLine}</span>}
          {line != null && <span className="call-line">çağrı satırı {line}</span>}
        </div>
      </header>
      <div className={`code-view language-${data.language ?? 'text'}`}>
        {lines.map((segments, i) => {
          const number = first + i;
          const isTarget = number === line;
          return (
            <div key={i} ref={isTarget ? highlighted : undefined} className={isTarget ? 'code-line target' : 'code-line'}>
              <span className="line-no">{number}</span>
              <span className="line-text">
                {segments.map((s, j) => (
                  <span key={j} className={s.types.length ? `token ${s.types.join(' ')}` : undefined}>{s.text}</span>
                ))}
                {segments.length === 0 && ' '}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
