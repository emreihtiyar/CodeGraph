import { asyncComponent } from './shared/asyncComponent';

// Kabuk uygulamaya verilen route listesi; ekranlar `path` + `component` nesnelerinden çıkarılır.
const routes = [
  {
    path: "/credit-application",
    processId: 6001,
    component: asyncComponent(() =>
      import("./screens/CreditApplicationScreen")
    ),
  },
  {
    path: "/customers/:customerId",
    processId: 6002,
    component: asyncComponent(() => import("./screens/CustomerDetailScreen")),
  },
];

export default routes;
