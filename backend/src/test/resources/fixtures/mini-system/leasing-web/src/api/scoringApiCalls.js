import { GATEWAY_URL } from '../config';

// Gateway prefix'i ("scoring") servis adından ("scoring-bff") farklı: repo yapılandırmasıyla eşlenir.
export function getScore(customerId) {
  return fetch(GATEWAY_URL + '/scoring/scores/' + customerId).then((res) => res.json());
}

export function getCustomerSummary(customerId) {
  return fetch(`${GATEWAY_URL}/scoring/customers/${customerId}/summary`, { method: 'GET' }).then((res) => res.json());
}

// BFF'te karşılığı yok: UNRESOLVED olmalı.
export function getScoreHistory(customerId) {
  return fetch(`${GATEWAY_URL}/scoring/scores/${customerId}/history`).then((res) => res.json());
}
