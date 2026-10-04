import { getBasePath, withBasePath } from '../../libs/http/basePath';
import { buildHttpHeaders, httpFetch } from '../../libs/http/httpClient';
import { captureObservabilityFromResponse, ensureObservabilityMeta, getObservabilityMeta } from '../../libs/observability/observability';

const FEATURE_HEADER_NAME = 'X-Client-Feature-Images';
const FEATURE_HEADER_VALUE = '1';

export type PatientImageListItem = {
  imageId: number;
  fileName: string;
  contentType: string;
  size: number;
  createdAt: string;
  downloadUrl: string;
};

export type PatientImageUploadItem = {
  imageId: number;
  documentId: number;
  fileName: string;
  contentType: string;
  size: number;
  createdAt: string;
};

export type UploadProgressEvent = {
  mode: 'real' | 'indeterminate';
  loaded?: number;
  total?: number;
  percent?: number;
};

type ApiErrorInfo = {
  errorCode?: string;
  message?: string;
};

export type PatientImageListResult = ApiErrorInfo & {
  ok: boolean;
  status: number;
  endpoint: string;
  list: PatientImageListItem[];
  runId?: string;
  traceId?: string;
  error?: string;
};

export type PatientImageUploadResult = ApiErrorInfo & {
  ok: boolean;
  status: number;
  endpoint: string;
  item?: PatientImageUploadItem;
  runId?: string;
  traceId?: string;
  error?: string;
};

const buildFeatureHeaders = (init?: RequestInit, pathname?: string) =>
  buildHttpHeaders(
    {
      ...(init ?? {}),
      headers: {
        ...(init?.headers ?? {}),
        [FEATURE_HEADER_NAME]: FEATURE_HEADER_VALUE,
      },
    },
    pathname,
  );

const parsePositiveInt = (value: unknown) =>
  typeof value === 'number' && Number.isFinite(value) && value > 0 && Number.isInteger(value) ? value : undefined;

const parseNonNegativeInt = (value: unknown) =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 && Number.isInteger(value) ? value : undefined;

const parseString = (value: unknown) => (typeof value === 'string' && value.trim() ? value : undefined);
const normalizeErrorCode = (value?: string) => {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed.toLowerCase();
};

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;

const parseBody = async (response: Response): Promise<{ rawText: string; json?: Record<string, unknown> | unknown[] }> => {
  const rawText = await response.text();
  if (!rawText) return { rawText };
  try {
    const parsed = JSON.parse(rawText) as Record<string, unknown> | unknown[];
    return { rawText, json: parsed };
  } catch {
    return { rawText };
  }
};

const BACKEND_PATIENT_IMAGE_PATH = /\/(?:openDolphin\/(?:api|resources)|api)(\/patients\/.+)$/;

/**
 * Sub-path deployment: the server builds downloadUrl from its own request URI
 * (e.g. http://backend/openDolphin/api/patients/P/images/1), which is not reachable through the
 * reverse proxy. Rewrite it to the client-facing `${BASE_PATH}/api/patients/...` path.
 * With BASE_PATH='/' the value is returned unchanged (legacy behavior).
 */
export const toClientImageUrl = (raw: string): string => {
  if (getBasePath() === '/') return raw;
  if (raw.startsWith('/') && !raw.startsWith('//')) {
    const match = raw.match(/^([^?#]*)(.*)$/);
    const path = match?.[1] ?? raw;
    const suffix = match?.[2] ?? '';
    const backend = path.match(BACKEND_PATIENT_IMAGE_PATH);
    if (backend && path.startsWith('/openDolphin/')) return withBasePath(`/api${backend[1]}${suffix}`);
    return withBasePath(raw);
  }
  try {
    const parsed = new URL(raw);
    const backend = parsed.pathname.match(BACKEND_PATIENT_IMAGE_PATH);
    if (!backend || !parsed.pathname.startsWith('/openDolphin/')) return raw;
    return withBasePath(`/api${backend[1]}${parsed.search}${parsed.hash}`);
  } catch {
    return raw;
  }
};

const normalizeListItem = (entry: unknown): PatientImageListItem | null => {
  const record = asRecord(entry);
  if (!record) return null;
  const imageId = parsePositiveInt(record.imageId);
  const fileName = parseString(record.fileName);
  const contentType = parseString(record.contentType);
  const size = parseNonNegativeInt(record.size);
  const createdAt = parseString(record.createdAt);
  const downloadUrl = parseString(record.downloadUrl);
  if (!imageId || !fileName || !contentType || size === undefined || !createdAt || !downloadUrl) {
    return null;
  }
  return {
    imageId,
    fileName,
    contentType,
    size,
    createdAt,
    downloadUrl: toClientImageUrl(downloadUrl),
  };
};

const normalizeUploadItem = (payload: unknown): PatientImageUploadItem | undefined => {
  const record = asRecord(payload);
  if (!record) return undefined;
  const imageId = parsePositiveInt(record.imageId);
  const documentId = parsePositiveInt(record.documentId);
  const fileName = parseString(record.fileName);
  const contentType = parseString(record.contentType);
  const size = parseNonNegativeInt(record.size);
  const createdAt = parseString(record.createdAt);
  if (!imageId || !documentId || !fileName || !contentType || size === undefined || !createdAt) {
    return undefined;
  }
  return {
    imageId,
    documentId,
    fileName,
    contentType,
    size,
    createdAt,
  };
};

const resolveErrorInfo = (payload: unknown): ApiErrorInfo => {
  const record = asRecord(payload);
  if (!record) return {};
  return {
    errorCode: normalizeErrorCode(parseString(record.errorCode) ?? parseString(record.code) ?? parseString(record.error)),
    message: parseString(record.message),
  };
};

const parseXhrHeaders = (xhr: XMLHttpRequest) => {
  const raw = xhr.getAllResponseHeaders?.() ?? '';
  const headers = new Headers();
  raw
    .trim()
    .split(/[\r\n]+/)
    .forEach((line) => {
      const parts = line.split(': ');
      const key = parts.shift();
      if (!key) return;
      headers.append(key, parts.join(': '));
    });
  return headers;
};

export async function fetchPatientImages(patientId: string): Promise<PatientImageListResult> {
  const metaBefore = ensureObservabilityMeta();
  const endpoint = `/patients/${encodeURIComponent(patientId)}/images`;
  const response = await httpFetch(endpoint, {
    method: 'GET',
    headers: buildFeatureHeaders({ method: 'GET' }, endpoint),
  });
  const parsed = await parseBody(response);
  const payload = parsed.json;
  const rawList = Array.isArray(payload)
    ? payload
    : Array.isArray(asRecord(payload)?.list)
      ? ((asRecord(payload)?.list as unknown[]) ?? [])
      : [];
  const list = rawList.map(normalizeListItem).filter((entry): entry is PatientImageListItem => entry !== null);
  const metaAfter = getObservabilityMeta();
  const errorInfo = resolveErrorInfo(payload);
  return {
    ok: response.ok,
    status: response.status,
    endpoint,
    list,
    runId: metaAfter.runId ?? metaBefore.runId,
    traceId: metaAfter.traceId ?? metaBefore.traceId,
    error: response.ok ? undefined : `HTTP ${response.status}`,
    ...errorInfo,
  };
}

export function uploadPatientImageFile(params: {
  patientId: string;
  file: File;
  onProgress?: (event: UploadProgressEvent) => void;
}): Promise<PatientImageUploadResult & { progressMode: UploadProgressEvent['mode'] }> {
  const metaBefore = ensureObservabilityMeta();
  const endpoint = `/patients/${encodeURIComponent(params.patientId)}/images`;
  let progressMode: UploadProgressEvent['mode'] = 'indeterminate';

  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', withBasePath(endpoint), true);

    const headers = buildFeatureHeaders({ method: 'POST' }, endpoint);
    Object.entries(headers).forEach(([key, value]) => {
      if (!value || key.toLowerCase() === 'content-type') return;
      xhr.setRequestHeader(key, value);
    });

    const emitProgress = (event: UploadProgressEvent) => {
      progressMode = event.mode;
      params.onProgress?.(event);
    };

    if (xhr.upload && typeof xhr.upload.addEventListener === 'function') {
      xhr.upload.addEventListener('progress', (event) => {
        if (event.lengthComputable && event.total > 0) {
          emitProgress({
            mode: 'real',
            loaded: event.loaded,
            total: event.total,
            percent: Math.min(100, Math.round((event.loaded / event.total) * 100)),
          });
          return;
        }
        emitProgress({ mode: 'indeterminate' });
      });
    } else {
      emitProgress({ mode: 'indeterminate' });
    }

    xhr.onload = () => {
      const headers = parseXhrHeaders(xhr);
      const response = new Response(xhr.responseText ?? '', { status: xhr.status, headers });
      captureObservabilityFromResponse(response);
      const metaAfter = getObservabilityMeta();
      let payload: Record<string, unknown> | unknown[] | undefined;
      if (xhr.responseText) {
        try {
          payload = JSON.parse(xhr.responseText) as Record<string, unknown> | unknown[];
        } catch {
          payload = undefined;
        }
      }
      const errorInfo = resolveErrorInfo(payload);
      resolve({
        ok: xhr.status >= 200 && xhr.status < 300,
        status: xhr.status,
        endpoint,
        item: normalizeUploadItem(payload),
        runId: metaAfter.runId ?? metaBefore.runId,
        traceId: metaAfter.traceId ?? metaBefore.traceId,
        error: xhr.status >= 200 && xhr.status < 300 ? undefined : `HTTP ${xhr.status}`,
        progressMode,
        ...errorInfo,
      });
    };

    xhr.onerror = () => {
      const metaAfter = getObservabilityMeta();
      resolve({
        ok: false,
        status: 0,
        endpoint,
        runId: metaAfter.runId ?? metaBefore.runId,
        traceId: metaAfter.traceId ?? metaBefore.traceId,
        error: 'network_error',
        progressMode,
      });
    };

    xhr.ontimeout = () => {
      const metaAfter = getObservabilityMeta();
      resolve({
        ok: false,
        status: 0,
        endpoint,
        runId: metaAfter.runId ?? metaBefore.runId,
        traceId: metaAfter.traceId ?? metaBefore.traceId,
        error: 'timeout',
        progressMode,
      });
    };

    const form = new FormData();
    form.append('file', params.file, params.file.name);
    xhr.send(form);
  });
}
