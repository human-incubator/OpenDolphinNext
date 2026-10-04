import { beforeEach, describe, expect, it, vi } from 'vitest';

const httpFetchMock = vi.fn();
vi.mock('../../../libs/http/httpClient', () => ({
  httpFetch: (...args: unknown[]) => httpFetchMock(...args),
}));

import { fetchChartSubjectiveEntries } from '../soap/subjectiveChartApi';

describe('fetchChartSubjectiveEntries', () => {
  beforeEach(() => {
    httpFetchMock.mockReset();
  });

  it('GET /api/local/charts/subjectives で保存済み SOAP を取得し readback 形式に正規化する', async () => {
    httpFetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({
          apiResult: '00',
          entries: [
            { documentId: 9001, entryId: 'local-subjective-9001-free', displaySection: 'free', soapCategory: 'S', body: '咽頭痛', performDate: '2026-04-10' },
            { documentId: 9002, displaySection: 'unknown', body: 'x' },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );

    const result = await fetchChartSubjectiveEntries({ patientId: '00001', performDate: '2026-04-10' });

    expect(httpFetchMock.mock.calls[0][0]).toBe('/api/local/charts/subjectives?patientId=00001&performDate=2026-04-10');
    expect(result.ok).toBe(true);
    expect(result.entries).toHaveLength(1);
    expect(result.entries[0]).toMatchObject({ entryId: 'local-subjective-9001-free', displaySection: 'free', body: '咽頭痛' });
  });
});
