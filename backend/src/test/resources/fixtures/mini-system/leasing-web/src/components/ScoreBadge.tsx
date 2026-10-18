import { useScore } from '../shared/hooks/useScoring';

interface Props {
  customerId: number;
}

export const ScoreBadge = ({ customerId }: Props) => {
  const { data: score, isLoading } = useScore(customerId);
  return <span className="badge">{isLoading ? '…' : score ?? '-'}</span>;
};

export default ScoreBadge;
