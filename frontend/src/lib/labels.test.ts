import { describe, expect, it } from 'vitest';
import { requestOrigin } from './labels.ts';

describe('requestOrigin', () => {
  it('shows the path below the page', () => {
    expect(requestOrigin({ component: 'UpdateModal', componentPath: ['ViewPage', 'DetailsTab', 'UpdateModal'] }))
      .toBe('DetailsTab › UpdateModal');
  });

  it('falls back to the component when the request is on the page itself', () => {
    expect(requestOrigin({ component: 'ViewPage', componentPath: ['ViewPage'] })).toBe('ViewPage');
    expect(requestOrigin({ component: 'ViewPage' })).toBe('ViewPage');
  });
});
