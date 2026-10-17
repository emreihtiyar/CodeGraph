import { describe, expect, it } from 'vitest';
import type { EndpointView, Service } from '../api/types.ts';
import { groupEndpoints, handlerMethod, pickerServices, splitServiceName } from './picker.ts';

const ep = (httpMethod: string, path: string, handler: string | null): EndpointView =>
  ({ id: `${httpMethod} ${path}`, service: 's', httpMethod, path, handlerId: null, handler });

const svc = (name: string, layer: Service['layer'], endpointCount: number, screenCount = 0, analyzed = true): Service =>
  ({ id: name, name, layer, repo: null, contextPath: null, gatewayPrefix: null, analyzed, endpointCount, screenCount, tableCount: 0 });

describe('picker', () => {
  it('splits namespaced service names', () => {
    expect(splitServiceName('LEASING.NGLA.leasing-contract')).toEqual({ namespace: 'LEASING.NGLA.', short: 'leasing-contract' });
    expect(splitServiceName('credit-api')).toEqual({ namespace: '', short: 'credit-api' });
  });

  it('groups services by layer and drops ones with nothing to pick', () => {
    const services = [
      svc('web', 'FRONTEND', 0, 3), svc('b-bff', 'BFF', 2), svc('a-bff', 'BFF', 1),
      svc('lib', 'LIBRARY', 0), svc('legacy', null, 0, 0, false),
    ];
    expect(pickerServices(services, true).map((g) => [g.layer, g.services.map((s) => s.name)]))
      .toEqual([['FRONTEND', ['web']], ['BFF', ['a-bff', 'b-bff']]]);
    expect(pickerServices(services, false).map((g) => g.layer)).toEqual(['BFF']);
  });

  it('groups endpoints by controller and filters case-insensitively', () => {
    const list = [
      ep('POST', '/api/b/save', 'BController.save'),
      ep('GET', '/api/a/{id}', 'AController.get'),
      ep('GET', '/api/b/list', 'BController.list'),
      ep('GET', '/health', null),
    ];
    expect(groupEndpoints(list, '').map((g) => [g.title, g.endpoints.map((e) => e.path)])).toEqual([
      ['/health', ['/health']],
      ['AController', ['/api/a/{id}']],
      ['BController', ['/api/b/list', '/api/b/save']],
    ]);
    expect(groupEndpoints(list, 'SAVE').flatMap((g) => g.endpoints.map((e) => e.path))).toEqual(['/api/b/save']);
    expect(handlerMethod('AssetSummaryController.getAllModelsInfo')).toBe('getAllModelsInfo');
  });
});
