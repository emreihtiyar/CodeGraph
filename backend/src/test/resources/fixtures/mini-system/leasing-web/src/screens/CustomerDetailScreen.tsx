import { useQueries } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getCustomerSummary, getScore, getScoreHistory } from '../api/scoringApiCalls';
import ScoreBadge from '../components/ScoreBadge';

export default function CustomerDetailScreen() {
  const { customerId } = useParams();
  const id = Number(customerId);

  const [score, summary] = useQueries({
    queries: [
      { queryKey: ['score', id], queryFn: () => getScore(id) },
      { queryKey: ['summary', id], queryFn: () => getCustomerSummary(id) },
    ],
  });

  const showHistory = () => {
    getScoreHistory(id).then((history) => console.log(history));
  };

  return (
    <div className="screen">
      <h1>Müşteri {customerId}</h1>
      <ScoreBadge customerId={id} />
      <p>{summary.data?.offerCount} teklif, skor {score.data}</p>
      <button onClick={showHistory}>Geçmiş</button>
    </div>
  );
}
