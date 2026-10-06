import { GATEWAY_URL } from '../config';

export function buildUrl(path: string): string {
  return GATEWAY_URL + path;
}
