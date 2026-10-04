import { withBasePath } from '../http/basePath';
import { buildHttpHeaders } from '../http/httpClient';

const DEFAULT_API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '');
const CHART_EVENT_LAST_EVENT_ID_PREFIX = 'chart-events:lastEventId';
const CHART_EVENT_STREAM_PATH = '/chart-events';
const isChartEventStreamDisabled = () => import.meta.env.VITE_DISABLE_CHART_EVENT_STREAM === '1';

export const CHART_EVENT_REPLAY_GAP_EVENT = 'chart-events.replay-gap';

export type ChartEventStreamMessage = {
  id?: string;
  event?: string;
  data?: string;
};

export type ChartEventStreamOptions = {
  facilityId: string;
  userId: string;
  clientUuid?: string;
  apiBaseUrl?: string;
  retryDelayMs?: number;
  onReplayGap?: (message: ChartEventStreamMessage) => void;
  onMessage?: (message: ChartEventStreamMessage) => void;
  onError?: (error: Error) => void;
};

export const buildLastEventIdKey = (facilityId: string, userId: string, clientUuid: string) =>
  `${CHART_EVENT_LAST_EVENT_ID_PREFIX}:${facilityId}:${userId}:${clientUuid}`;

export const readStoredLastEventId = (facilityId: string, userId: string, clientUuid: string): string | null => {
  if (typeof sessionStorage === 'undefined') return null;
  try {
    return sessionStorage.getItem(buildLastEventIdKey(facilityId, userId, clientUuid));
  } catch {
    return null;
  }
};

export const storeLastEventId = (facilityId: string, userId: string, clientUuid: string, lastEventId: string) => {
  if (typeof sessionStorage === 'undefined') return;
  const trimmed = lastEventId.trim();
  if (!trimmed) return;
  try {
    sessionStorage.setItem(buildLastEventIdKey(facilityId, userId, clientUuid), trimmed);
  } catch {
    // ignore storage errors
  }
};

const parseField = (line: string, field: string) => {
  if (!line.startsWith(field)) return null;
  if (line.length === field.length) return '';
  if (line[field.length] !== ':') return null;
  let value = line.slice(field.length + 1);
  if (value.startsWith(' ')) value = value.slice(1);
  return value;
};

const parseEventBlock = (block: string): ChartEventStreamMessage | null => {
  const lines = block.split('\n');
  let id: string | undefined;
  let event: string | undefined;
  const dataLines: string[] = [];

  for (const rawLine of lines) {
    const line = rawLine.replace(/\r$/, '');
    if (!line) continue;
    if (line.startsWith(':')) continue;

    const idValue = parseField(line, 'id');
    if (idValue !== null) {
      id = idValue;
      continue;
    }

    const eventValue = parseField(line, 'event');
    if (eventValue !== null) {
      event = eventValue;
      continue;
    }

    const dataValue = parseField(line, 'data');
    if (dataValue !== null) {
      dataLines.push(dataValue);
      continue;
    }
  }

  if (id === undefined && event === undefined && dataLines.length === 0) return null;
  return {
    id,
    event,
    data: dataLines.length > 0 ? dataLines.join('\n') : undefined,
  };
};

const wait = (ms: number, signal?: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(() => resolve(), ms);
    if (!signal) return;
    signal.addEventListener(
      'abort',
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });

const streamEvents = async (
  response: Response,
  onMessage: (message: ChartEventStreamMessage) => void,
  signal: AbortSignal,
) => {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('chart-events stream body missing');
  }
  const decoder = new TextDecoder();
  let buffer = '';

  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) break;
    if (!value) continue;
    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, '\n');

    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const event = parseEventBlock(block);
      if (event) {
        onMessage(event);
      }
      boundary = buffer.indexOf('\n\n');
    }
  }
};

export function handleChartEventStreamMessage(options: {
  facilityId: string;
  userId: string;
  clientUuid: string;
  message: ChartEventStreamMessage;
  onReplayGap?: (message: ChartEventStreamMessage) => void;
  onMessage?: (message: ChartEventStreamMessage) => void;
}) {
  const { facilityId, userId, clientUuid, message, onReplayGap, onMessage } = options;
  if (message.id) {
    storeLastEventId(facilityId, userId, clientUuid, message.id);
  }
  if (message.event === CHART_EVENT_REPLAY_GAP_EVENT) {
    onReplayGap?.(message);
  }
  onMessage?.(message);
}

export function startChartEventStream(options: ChartEventStreamOptions) {
  const {
    facilityId,
    userId,
    clientUuid,
    apiBaseUrl = DEFAULT_API_BASE_URL,
    retryDelayMs = 3000,
    onReplayGap,
    onMessage,
    onError,
  } = options;

  if (isChartEventStreamDisabled()) {
    return () => {};
  }

  const controller = new AbortController();
  const { signal } = controller;
  let streamUnavailable = false;

  const run = async () => {
    while (!signal.aborted && !streamUnavailable) {
      try {
        const resolvedUserId = userId?.trim();
        const resolvedClientUuid = clientUuid?.trim();
        if (!resolvedUserId || !resolvedClientUuid) {
          throw new Error('chart-events actor context is missing');
        }
        const lastEventId = readStoredLastEventId(facilityId, resolvedUserId, resolvedClientUuid);
        // VITE_API_BASE_URL may already include the sub-path (/medical_chart/api); withBasePath is idempotent.
        const streamUrl = withBasePath(`${apiBaseUrl}${CHART_EVENT_STREAM_PATH}`);
        const headers = new Headers(
          buildHttpHeaders({
            headers: {
              Accept: 'text/event-stream',
            },
          }, streamUrl),
        );
        headers.set('X-Facility-Id', facilityId);
        if (!headers.has('clientUUID')) {
          headers.set('clientUUID', resolvedClientUuid);
        }
        if (lastEventId) {
          headers.set('Last-Event-ID', lastEventId);
        }

        const response = await fetch(streamUrl, {
          method: 'GET',
          cache: 'no-store',
          headers,
          credentials: 'include',
          signal,
        });
        if (response.status === 404) {
          streamUnavailable = true;
          throw new Error('chart-events stream unavailable (404)');
        }
        if (
          response.status === 400 ||
          response.status === 401 ||
          response.status === 403 ||
          response.status === 500 ||
          response.status === 502 ||
          response.status === 503
        ) {
          // Treat server-side unavailability/auth issues as "stream not provided"
          // to avoid infinite reconnect and console spam during development.
          streamUnavailable = true;
          throw new Error(`chart-events stream unavailable (${response.status})`);
        }
        if (!response.ok) {
          throw new Error(`chart-events stream failed: ${response.status}`);
        }
        await streamEvents(
          response,
          (message) =>
            handleChartEventStreamMessage({
              facilityId,
              userId: resolvedUserId,
              clientUuid: resolvedClientUuid,
              message,
              onReplayGap,
              onMessage,
            }),
          signal,
        );
      } catch (error) {
        if (signal.aborted) break;
        const err = error instanceof Error ? error : new Error(String(error));
        onError?.(err);
      }

      if (signal.aborted || streamUnavailable) break;
      await wait(retryDelayMs, signal);
    }
  };

  void run();

  return () => controller.abort();
}
