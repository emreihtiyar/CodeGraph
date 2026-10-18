import { callApi } from './callApi';

export function getBaseUrl(): string {
  return "leasing-credit-bff";
}

export interface CreditApplicationRequest {
  customerId: number;
  customerNo: string;
  amount: number;
}

export function getCreditApplication(applicationId: string): Promise<any> {
  return callApi({
    endpoint: getBaseUrl() + `/applications/${applicationId}`,
    method: "GET",
    notifyErrors: false,
  });
}

export const createCreditApplication = (body: CreditApplicationRequest) =>
  callApi({
    endpoint: getBaseUrl() + "/applications",
    method: "POST",
    notifyErrors: false,
    body,
  });

// Endpoint dışarıdan geliyor: hedef çözülemez, UNRESOLVED olmalı.
export function getApplicationDocument(path: string): Promise<any> {
  return callApi({ endpoint: path, method: "GET" });
}
