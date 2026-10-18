import { lazy, Suspense, type ComponentType } from 'react';

export function asyncComponent(load: () => Promise<{ default: ComponentType<any> }>) {
  const Page = lazy(load);
  return (props: any) => (
    <Suspense fallback={null}>
      <Page {...props} />
    </Suspense>
  );
}
