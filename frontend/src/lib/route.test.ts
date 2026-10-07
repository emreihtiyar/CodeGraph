import { describe, expect, it } from 'vitest';
import { formatRoute, parseRoute } from './route.ts';

describe('route', () => {
  it('parses view and params', () => {
    expect(parseRoute('#/trace?from=%2Fcredit-application&dir=backward')).toEqual({
      view: 'trace',
      params: { from: '/credit-application', dir: 'backward' },
    });
  });

  it('falls back to system view', () => {
    expect(parseRoute('')).toEqual({ view: 'system', params: {} });
    expect(parseRoute('#/unknown?x=1').view).toBe('system');
  });

  it('round-trips and drops empty params', () => {
    const hash = formatRoute({ view: 'tree', params: { root: 'GET /a/{id}', hide: '' } });
    expect(hash).toBe('#/tree?root=GET+%2Fa%2F%7Bid%7D');
    expect(parseRoute(hash).params).toEqual({ root: 'GET /a/{id}' });
  });
});
