import { afterEach, describe, expect, it, vi } from 'vitest';

import { getBasePath, normalizeBasePath, setBasePathForTests, stripBasePath, withBasePath } from './basePath';
import { httpFetch, setCsrfRuntimeOverrideForTests } from './httpClient';
import { toClientImageUrl } from '../../features/images/patientImagesApi';

const setCsrfMetaToken = (content: string) => {
  document.querySelector("meta[name='csrf-token']")?.remove();
  const meta = document.createElement('meta');
  meta.setAttribute('name', 'csrf-token');
  meta.setAttribute('content', content);
  document.head.appendChild(meta);
};

describe('basePath helpers', () => {
  afterEach(() => {
    setBasePathForTests(undefined);
  });

  it('normalizes base path values', () => {
    expect(normalizeBasePath(undefined)).toBe('/');
    expect(normalizeBasePath('')).toBe('/');
    expect(normalizeBasePath('/')).toBe('/');
    expect(normalizeBasePath('medical_chart/')).toBe('/medical_chart');
    expect(normalizeBasePath('/medical_chart//')).toBe('/medical_chart');
  });

  it('is a no-op when base path is root', () => {
    setBasePathForTests('/');
    expect(getBasePath()).toBe('/');
    expect(withBasePath('/api/session/me')).toBe('/api/session/me');
    expect(stripBasePath('/api/session/me')).toBe('/api/session/me');
  });

  it('prefixes root-relative paths idempotently and leaves absolute URLs alone', () => {
    setBasePathForTests('/medical_chart/');
    expect(withBasePath('/api/session/me')).toBe('/medical_chart/api/session/me');
    expect(withBasePath('/karte/image/1')).toBe('/medical_chart/karte/image/1');
    expect(withBasePath('/medical_chart/api/session/me')).toBe('/medical_chart/api/session/me');
    expect(withBasePath('/medical_chart')).toBe('/medical_chart');
    expect(withBasePath('/medical_chart?x=1')).toBe('/medical_chart?x=1');
    expect(withBasePath('/medical_chartx/a')).toBe('/medical_chart/medical_chartx/a');
    expect(withBasePath('https://example.com/api/x')).toBe('https://example.com/api/x');
    expect(withBasePath('//example.com/api/x')).toBe('//example.com/api/x');
    expect(withBasePath('blob:abc')).toBe('blob:abc');
    expect(stripBasePath('/medical_chart/api/session')).toBe('/api/session');
    expect(stripBasePath('/medical_chart')).toBe('/');
    expect(stripBasePath('/other/api')).toBe('/other/api');
  });

  it('supports multi-segment base paths', () => {
    expect(normalizeBasePath('clinic/emr/')).toBe('/clinic/emr');
    setBasePathForTests('/clinic/emr/');
    expect(getBasePath()).toBe('/clinic/emr');
    expect(withBasePath('/api/session/me')).toBe('/clinic/emr/api/session/me');
    expect(withBasePath('/clinic/emr/api/session/me')).toBe('/clinic/emr/api/session/me');
    expect(withBasePath('/clinic/emr')).toBe('/clinic/emr');
    // Only the full base counts as already-prefixed.
    expect(withBasePath('/medical_chart/api/x')).toBe('/clinic/emr/medical_chart/api/x');
    expect(stripBasePath('/clinic/emr/karte/image/1')).toBe('/karte/image/1');
    expect(stripBasePath('/clinic/emr')).toBe('/');
  });
});

describe('httpFetch under a sub-path', () => {
  afterEach(() => {
    setBasePathForTests(undefined);
    setCsrfRuntimeOverrideForTests(undefined);
    vi.restoreAllMocks();
  });

  it('prefixes root-relative request URLs once and keeps CSRF headers for same-origin requests', async () => {
    setBasePathForTests('/clinic/emr');
    setCsrfMetaToken('token-123');
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 200 }));

    await httpFetch('/api/session/me');
    await httpFetch('/clinic/emr/api/chart-events');
    await httpFetch('/karte/revisions', { method: 'POST', body: '{}' });
    await httpFetch('https://example.com/api/x');

    const urls = fetchSpy.mock.calls.map(([input]) => new URL(String(input)));
    expect(urls[0].pathname).toBe('/clinic/emr/api/session/me');
    expect(urls[1].pathname).toBe('/clinic/emr/api/chart-events');
    expect(urls[2].pathname).toBe('/clinic/emr/karte/revisions');
    expect(urls[3].toString()).toBe('https://example.com/api/x');

    const postInit = fetchSpy.mock.calls[2][1] as RequestInit;
    expect(new Headers(postInit.headers).get('X-CSRF-Token')).toBe('token-123');
    // classification still works on the stripped path (karte => no-store for GET)
    const sessionInit = fetchSpy.mock.calls[0][1] as RequestInit;
    expect(sessionInit.cache).toBe('no-store');
  });

  it('rewrites backend image download URLs to the client-facing path', () => {
    setBasePathForTests('/clinic/emr');
    expect(toClientImageUrl('http://backend:8080/openDolphin/api/patients/P1/images/10')).toBe(
      '/clinic/emr/api/patients/P1/images/10',
    );
    expect(toClientImageUrl('/openDolphin/resources/patients/P1/images/10?x=1')).toBe(
      '/clinic/emr/api/patients/P1/images/10?x=1',
    );
    expect(toClientImageUrl('/patients/P1/images/10')).toBe('/clinic/emr/patients/P1/images/10');
    expect(toClientImageUrl('https://example.test/images/1')).toBe('https://example.test/images/1');
    setBasePathForTests('/');
    expect(toClientImageUrl('http://backend:8080/openDolphin/api/patients/P1/images/10')).toBe(
      'http://backend:8080/openDolphin/api/patients/P1/images/10',
    );
  });
});
