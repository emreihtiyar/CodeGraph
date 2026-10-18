import { useQuery } from '@tanstack/react-query';
import { getScore } from '../../api/scoringApiCalls';

// ApiCall'ı useQuery ile saran özel hook; component'ler doğrudan bunu kullanır.
export function useScore(customerId?: number) {
  return useQuery({
    enabled: !!customerId,
    queryKey: ['scoring', 'score', customerId],
    queryFn: () => getScore(customerId!),
  });
}
