import { createBrowserRouter } from 'react-router-dom';
import CreditApplicationScreen from './screens/CreditApplicationScreen';
import CustomerDetailScreen from './screens/CustomerDetailScreen';

export const router = createBrowserRouter([
  { path: '/credit-application', element: <CreditApplicationScreen /> },
  { path: '/customers/:customerId', element: <CustomerDetailScreen /> },
]);
