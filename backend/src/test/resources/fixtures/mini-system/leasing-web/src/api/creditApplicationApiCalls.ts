import { GATEWAY_URL } from '../config';
import { buildUrl } from './urlUtils';

const BFF = `${GATEWAY_URL}/leasing-credit-bff`;

export interface CreditApplicationRequest {
  customerId: number;
  customerNo: string;
  amount: number;
}

export const getCreditApplication = async (applicationId: string) => {
  const response = await fetch(`${BFF}/applications/${applicationId}`);
  return response.json();
};

export const createCreditApplication = async (body: CreditApplicationRequest) => {
  const response = await fetch(`${BFF}/applications`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return response.json();
};

// URL yardımcı fonksiyonla dinamik üretiliyor: UNRESOLVED olmalı.
export const getApplicationDocument = (path: string) => fetch(buildUrl(path)).then((r) => r.blob());
