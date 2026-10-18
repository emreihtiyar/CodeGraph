import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getCreditApplication } from '../api/creditApplicationApiCalls';
import ApplicationForm from '../components/ApplicationForm';
import ScoreBadge from '../components/ScoreBadge';

// Sekmeler yapılandırma dizisinden render edilir: <item.component />
const tabItems = [{ key: 'form', title: 'Başvuru', visible: true, component: ApplicationForm }];

export default function CreditApplicationScreen() {
  const { applicationId } = useParams();
  const { data } = useQuery({
    queryKey: ['application', applicationId],
    queryFn: () => getCreditApplication(applicationId!),
    enabled: !!applicationId,
  });

  return (
    <div className="screen">
      <h1>Kredi Başvurusu</h1>
      {data && <ScoreBadge customerId={data.credit.customerId} />}
      {tabItems
        .filter((t) => t.visible)
        .map((item) => (
          <section key={item.key} title={item.title}>
            <item.component />
          </section>
        ))}
    </div>
  );
}
