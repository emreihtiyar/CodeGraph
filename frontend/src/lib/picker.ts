import type { EndpointView, ScreenView, Service } from '../api/types.ts';

/** Seçicide servislerin gösterim sırası. */
export const PICKER_LAYERS = ['FRONTEND', 'BFF', 'API', 'ADAPTER', 'LIBRARY'] as const;

/** {@code LEASING.NGLA.leasing-contract} → isim alanı {@code LEASING.NGLA.} ve kısa ad {@code leasing-contract}. */
export function splitServiceName(name: string): { namespace: string; short: string } {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? { namespace: '', short: name } : { namespace: name.slice(0, dot + 1), short: name.slice(dot + 1) };
}

/** Seçicide gösterilecek servisler, katmana göre gruplu; {@code withScreens} yoksa yalnızca endpoint'i olanlar. */
export function pickerServices(services: Service[], withScreens: boolean): { layer: string; services: Service[] }[] {
  const usable = services.filter((s) => s.analyzed && (s.endpointCount > 0 || (withScreens && s.screenCount > 0)));
  return PICKER_LAYERS
    .map((layer) => ({
      layer: layer as string,
      services: usable.filter((s) => s.layer === layer)
        .sort((a, b) => splitServiceName(a.name).short.localeCompare(splitServiceName(b.name).short)),
    }))
    .filter((g) => g.services.length > 0);
}

export interface EndpointGroup {
  /** Controller'ın basit sınıf adı; handler bilinmiyorsa path'in ilk segmenti. */
  title: string;
  endpoints: EndpointView[];
}

function matches(filter: string, ...texts: (string | null)[]): boolean {
  const f = filter.trim().toLocaleLowerCase('tr');
  return !f || texts.some((t) => t?.toLocaleLowerCase('tr').includes(f));
}

/** Endpoint'leri controller'a göre gruplar ve filtreler; gruplar ve içleri path'e göre sıralı. */
export function groupEndpoints(endpoints: EndpointView[], filter: string): EndpointGroup[] {
  const groups = new Map<string, EndpointView[]>();
  for (const e of endpoints) {
    if (!matches(filter, e.path, e.handler, e.httpMethod)) continue;
    const title = e.handler?.includes('.') ? e.handler.slice(0, e.handler.lastIndexOf('.')) : firstSegment(e.path);
    groups.set(title, [...(groups.get(title) ?? []), e]);
  }
  return [...groups]
    .map(([title, list]) => ({ title, endpoints: list.sort((a, b) => a.path.localeCompare(b.path) || a.httpMethod.localeCompare(b.httpMethod)) }))
    .sort((a, b) => a.title.localeCompare(b.title));
}

export function filterScreens(screens: ScreenView[], filter: string): ScreenView[] {
  return screens.filter((s) => matches(filter, s.routePath, s.name)).sort((a, b) => a.routePath.localeCompare(b.routePath));
}

function firstSegment(path: string): string {
  return '/' + (path.split('/').find((s) => s) ?? '');
}

/** Handler'ın metot adı: {@code AssetSummaryController.getAllModelsInfo} → {@code getAllModelsInfo}. */
export function handlerMethod(handler: string | null): string {
  return handler ? handler.slice(handler.lastIndexOf('.') + 1) : '';
}
