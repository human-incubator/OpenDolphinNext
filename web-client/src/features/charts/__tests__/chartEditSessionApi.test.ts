import { beforeEach, describe, expect, it, vi } from 'vitest';

const httpFetchMock = vi.fn();
vi.mock('../../../libs/http/httpClient', () => ({
  httpFetch: (...args: unknown[]) => httpFetchMock(...args),
}));

import { acquireChartEditSession, releaseChartEditSession } from '../chartEditSessionApi';

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('chartEditSessionApi', () => {
  beforeEach(() => {
    httpFetchMock.mockReset();
  });

  it('release はページ破棄で中断されないよう keepalive で送る', async () => {
    httpFetchMock.mockResolvedValue(jsonResponse(200, { lockStatus: 'released' }));
    await releaseChartEditSession({ patientId: 'P1', ownerTabSessionId: 'tab-1', leaseId: 'lease-1' });
    expect(httpFetchMock.mock.calls[0][0]).toBe('/api/local/charts/edit-sessions/release');
    expect(httpFetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST', keepalive: true });
  });

  it('409 応答の保持者 runId / 期限を結果に反映する', async () => {
    httpFetchMock.mockResolvedValue(
      jsonResponse(409, {
        error: 'chart_edit_session_locked',
        lockStatus: 'other-editor',
        ownerRunId: 'RUN-OTHER',
        expiresAt: '2026-10-04T01:05:00Z',
      }),
    );
    const result = await acquireChartEditSession({ patientId: 'P1', ownerTabSessionId: 'tab-1' });
    expect(result.ok).toBe(false);
    expect(result.ownerRunId).toBe('RUN-OTHER');
    expect(result.expiresAt).toBe('2026-10-04T01:05:00Z');
    expect(httpFetchMock.mock.calls[0][1]).toMatchObject({ keepalive: false });
  });
});
