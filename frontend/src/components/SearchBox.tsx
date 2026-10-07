import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client.ts';
import type { NodeSummary, SearchHit } from '../api/types.ts';
import { defaultAction, LABELS } from '../lib/labels.ts';
import { navigate } from '../lib/route.ts';
import { useAppState } from '../state.tsx';
import { LabelPill } from './ui.tsx';

const ACTION_HINT = {
  'trace-forward': 'İleri iz',
  'trace-backward': 'Geri iz',
  'call-tree': 'Çağrı ağacı',
  service: 'Sistemde göster',
  code: 'Kodu aç',
} as const;

/** Global arama (FR-19): servis, endpoint path'i, sınıf/metot, component, tablo. Seçim düğüm tipine göre açılır. */
export function SearchBox() {
  const { openCode } = useAppState();
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setHits([]);
      setError(null);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      api.search(q, 20, controller.signal).then(
        (result) => {
          setHits(result);
          setActive(0);
          setError(null);
        },
        (e: Error) => e.name !== 'AbortError' && setError(e.message),
      );
    }, 220);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === '/' && document.activeElement?.tagName !== 'INPUT') {
        e.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const choose = (node: NodeSummary) => {
    setOpen(false);
    setQuery('');
    openNode(node, openCode);
  };

  return (
    <div className="search">
      <input
        ref={input}
        type="search"
        placeholder="Ara: ekran, endpoint, metot, tablo…  ( / )"
        value={query}
        aria-label="Ara"
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => window.setTimeout(() => setOpen(false), 150)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActive((a) => Math.min(a + 1, hits.length - 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActive((a) => Math.max(a - 1, 0));
          } else if (e.key === 'Enter' && hits[active]) {
            choose(hits[active].node);
          } else if (e.key === 'Escape') {
            setOpen(false);
            input.current?.blur();
          }
        }}
      />
      {open && query.trim().length >= 2 && (
        <div className="search-results" role="listbox">
          {error && <div className="search-empty">{error}</div>}
          {!error && hits.length === 0 && <div className="search-empty">Sonuç yok</div>}
          {hits.map((h, i) => (
            <button
              key={h.node.id}
              type="button"
              role="option"
              aria-selected={i === active}
              className={i === active ? 'search-hit active' : 'search-hit'}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(h.node)}
            >
              <LabelPill label={h.node.label} />
              <span className="node-name">{h.node.name}</span>
              {h.node.service && <span className="muted small">{h.node.service}</span>}
              <span className="hit-action">{ACTION_HINT[defaultAction(h.node.label)]}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Düğümü tipine uygun görünümde açar. */
export function openNode(node: NodeSummary, openCode: ReturnType<typeof useAppState>['openCode']) {
  switch (defaultAction(node.label)) {
    case 'trace-forward':
      navigate('trace', { from: node.id, dir: 'forward' });
      break;
    case 'trace-backward':
      navigate('trace', { from: node.id, dir: 'backward' });
      break;
    case 'call-tree':
      navigate('tree', { root: node.id });
      break;
    case 'service':
      navigate('system', { focus: node.name });
      break;
    default:
      openCode([{ nodeId: node.id, caption: LABELS[node.label]?.title ?? node.label }]);
  }
}
