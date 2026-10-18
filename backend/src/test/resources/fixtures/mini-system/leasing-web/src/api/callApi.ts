// Projelerdeki ortak HTTP yardımcısı: endpoint gateway'e görelidir, ilk segment BFF'in gateway prefix'i.
const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL;

export interface CallApiOptions {
  endpoint: string;
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  notifyErrors?: boolean;
}

export async function callApi({ endpoint, method = 'GET', body }: CallApiOptions): Promise<any> {
  const response = await fetch(`${GATEWAY_URL}/${endpoint}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return response.json();
}
