import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api, ApiError } from './api/client.ts';
import type { AnalysisRunView } from './api/types.ts';

/** Kod panelinde açılacak kod; kenar/çağrı seçiminde çağıranın kodu ve çağrılanın kodu ayrı sekmelerdir. */
export interface CodeTab {
  nodeId: string;
  caption: string;
  /** vurgulanacak satır (dosyadaki mutlak satır numarası) */
  line?: number;
}

export interface CodeTarget {
  tabs: CodeTab[];
  active: number;
}

interface AppState {
  code: CodeTarget | null;
  openCode: (tabs: CodeTab[], active?: number) => void;
  closeCode: () => void;
  /** son analiz; undefined: yükleniyor, null: hiç analiz yok */
  latestRun: AnalysisRunView | null | undefined;
  analysisError: string | null;
  startAnalysis: () => Promise<void>;
  /** her başarılı analizden sonra artar; görünümler verilerini yeniden yükler */
  graphVersion: number;
}

const Context = createContext<AppState | null>(null);

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [code, setCode] = useState<CodeTarget | null>(null);
  const [latestRun, setLatestRun] = useState<AnalysisRunView | null | undefined>(undefined);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [graphVersion, setGraphVersion] = useState(0);
  const previousState = useRef<string | undefined>(undefined);

  const refresh = useCallback(async () => {
    try {
      const run = await api.analysisRun('latest');
      setLatestRun(run);
      setAnalysisError(null);
      if (previousState.current === 'RUNNING' && run.state !== 'RUNNING') setGraphVersion((v) => v + 1);
      previousState.current = run.state;
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setLatestRun(null);
        setAnalysisError(null);
      } else {
        setAnalysisError((e as Error).message);
      }
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const running = latestRun?.state === 'RUNNING';
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void refresh(), 1000);
    return () => window.clearInterval(timer);
  }, [running, refresh]);

  const startAnalysis = useCallback(async () => {
    try {
      await api.startAnalysis();
    } catch (e) {
      // 409: zaten çalışıyor; durum yenilenince görünür
      if (!(e instanceof ApiError && e.status === 409)) {
        setAnalysisError((e as Error).message);
        return;
      }
    }
    previousState.current = 'RUNNING';
    await refresh();
  }, [refresh]);

  const value = useMemo<AppState>(() => ({
    code,
    openCode: (tabs, active = 0) => setCode({ tabs, active }),
    closeCode: () => setCode(null),
    latestRun,
    analysisError,
    startAnalysis,
    graphVersion,
  }), [code, latestRun, analysisError, startAnalysis, graphVersion]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useAppState(): AppState {
  const state = useContext(Context);
  if (!state) throw new Error('AppStateProvider eksik');
  return state;
}
