import type { ReactNode } from 'react';
import type { NodeLabel, NodeSummary } from '../api/types.ts';
import { LABELS } from '../lib/labels.ts';

export function LabelPill({ label }: { label: NodeLabel }) {
  const meta = LABELS[label] ?? { title: label, color: '#64748b', icon: '•' };
  return (
    <span className="label-pill" style={{ color: meta.color, borderColor: meta.color }}>
      <span aria-hidden>{meta.icon}</span> {meta.title}
    </span>
  );
}

export function NodeLine({ node, onClick }: { node: NodeSummary; onClick?: () => void }) {
  const content = (
    <>
      <LabelPill label={node.label} />
      <span className="node-name">{node.name}</span>
      {node.service && <span className="muted small">{node.service}</span>}
    </>
  );
  return onClick ? (
    <button type="button" className="node-line link" onClick={onClick}>{content}</button>
  ) : (
    <span className="node-line">{content}</span>
  );
}

export type BadgeTone = 'neutral' | 'info' | 'warn' | 'danger' | 'ok' | 'purple';

export function Badge({ tone = 'neutral', title, children }: { tone?: BadgeTone; title?: string; children: ReactNode }) {
  return <span className={`badge badge-${tone}`} title={title}>{children}</span>;
}

export function Spinner({ text = 'Yükleniyor…' }: { text?: string }) {
  return (
    <div className="spinner" role="status">
      <span className="spinner-dot" aria-hidden /> {text}
    </div>
  );
}

export function ErrorBox({ error, onRetry }: { error: Error | string; onRetry?: () => void }) {
  return (
    <div className="error-box" role="alert">
      <strong>Hata:</strong> {typeof error === 'string' ? error : error.message}
      {onRetry && <button type="button" className="btn btn-small" onClick={onRetry}>Tekrar dene</button>}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children}
    </div>
  );
}

export function Toggle({ label, checked, onChange, title }: {
  label: string; checked: boolean; onChange: (v: boolean) => void; title?: string;
}) {
  return (
    <label className="toggle" title={title}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} /> {label}
    </label>
  );
}
