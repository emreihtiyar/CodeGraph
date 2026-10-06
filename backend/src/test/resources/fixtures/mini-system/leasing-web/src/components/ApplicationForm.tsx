import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { createCreditApplication } from '../api/creditApplicationApiCalls';

export default function ApplicationForm() {
  const [amount, setAmount] = useState(0);
  const mutation = useMutation({ mutationFn: createCreditApplication });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        mutation.mutate({ customerId: 1, customerNo: 'C1', amount });
      }}
    >
      <input type="number" value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
      <button type="submit">Gönder</button>
    </form>
  );
}
