import { callApi } from './callApi';

// Gateway prefix'i ("scoring") servis adından ("scoring-bff") farklı: repo yapılandırmasıyla eşlenir.
export function getBaseUrl() {
  return 'scoring';
}

// method verilmezse GET
export function getScore(customerId) {
  return callApi({ endpoint: getBaseUrl() + '/scores/' + customerId });
}

export function getCustomerSummary(customerId) {
  return callApi({ endpoint: `${getBaseUrl()}/customers/${customerId}/summary`, method: 'GET' });
}

// BFF'te karşılığı yok: UNRESOLVED olmalı.
export function getScoreHistory(customerId) {
  return callApi({ endpoint: getBaseUrl() + `/scores/${customerId}/history`, method: 'GET' });
}
