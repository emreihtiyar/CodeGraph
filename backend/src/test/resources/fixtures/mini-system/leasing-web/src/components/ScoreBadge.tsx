import { useQuery } from '@tanstack/react-query';
import { getScore } from '../api/scoringApiCalls';

interface Props {
  customerId: number;
}

export const ScoreBadge = ({ customerId }: Props) => {
  const { data: score } = useQuery({ queryKey: ['score', customerId], queryFn: () => getScore(customerId) });
  return <span className="badge">{score ?? '-'}</span>;
};

export default ScoreBadge;
