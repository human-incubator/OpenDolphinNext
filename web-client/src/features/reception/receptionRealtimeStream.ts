import { withBasePath } from '../../libs/http/basePath';
import { httpFetch } from '../../libs/http/httpClient';

const DEFAULT_API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/$/, '');
const RECEPTION_STREAM_PATH = '/realtime/reception';
const DEFAULT_RETRY_DELAY_MS = 1500;
const DEFAULT_MAX_RETRY_DELAY_MS = 10_000;

const isReceptionRealtimeStreamDisabled = () => import.meta.env.VITE_DISABLE_RECEPTION_REALTIME === '1';

export type ReceptionRealtimeConnectionStatus =
  | 'connecting'
  | 'open'
  | 'reconnecting'
  | 'closed'
  | 'unavailable';

export type ReceptionRealtimeEvent = {
  type?: string;
  facilityId?: string;
  date?: string;
  patientId?: string;
  requestNumber?: string;
  revision?: number;
  updatedAt?: string;
  runId?: string;
};

export type ReceptionRealtimeStreamOptions = {
  apiBaseUrl?: string;
  retryDelayMs?: number;
  maxRetryDelayMs?: number;
  onStatusChange?: (status: ReceptionRealtimeConnectionStatus) => void;
  onMessage?: (message: ReceptionRealtimeEvent) => void;
  onError?: (error: Error) => void;
};

const toError = (error: unknown) => (error instanceof Error ? error : new Error(String(error)));

type SseEventBlock = {
  id?: string;
  event?: string;
  data?: string;
};

const FETCH_STREAM_UNSUPPORTED_ERROR_CODE = 'RECEPTION_FETCH_STREAM_UNSUPPORTED';

const parseField = (line: string, field: string) => {
  if (!line.startsWith(field)) return null;
  if (line.length === field.length) return '';
  if (line[field.length] !== ':') return null;
  let value = line.slice(field.length + 1);
  if (value.startsWith(' ')) value = value.slice(1);
  return value;
};

const parseSseEventBlock = (block: string): SseEventBlock | null => {
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
    }
  }

  if (id === undefined && event === undefined && dataLines.length === 0) return null;
  return {
    id,
    event,
    data: dataLines.length > 0 ? dataLines.join('\n') : undefined,
  };
};

const streamSseEvents = async (
  response: Response,
  signal: AbortSignal,
  onEvent: (event: SseEventBlock) => void,
) => {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error(FETCH_STREAM_UNSUPPORTED_ERROR_CODE);
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
      const parsedEvent = parseSseEventBlock(block);
      if (parsedEvent) {
        onEvent(parsedEvent);
      }
      boundary = buffer.indexOf('\n\n');
    }
  }
};

const isFetchStreamSupported = () =>
  typeof window !== 'undefined' &&
  typeof window.fetch === 'function' &&
  typeof window.AbortController === 'function' &&
  typeof TextDecoder === 'function';

const parseEvent = (payload: string, eventType?: string, lastEventId?: string): ReceptionRealtimeEvent | null => {
  if (!payload || !payload.trim()) {
    if (!eventType) return null;
    return { type: eventType };
  }
  try {
    const raw = JSON.parse(payload) as Record<string, unknown>;
    const revisionFromLastEventId = lastEventId ? Number.parseInt(lastEventId, 10) : undefined;
    const revision =
      typeof raw.revision === 'number'
        ? raw.revision
        : Number.isFinite(revisionFromLastEventId)
          ? revisionFromLastEventId
          : undefined;
    return {
      type: typeof raw.type === 'string' ? raw.type : eventType,
      facilityId: typeof raw.facilityId === 'string' ? raw.facilityId : undefined,
      date: typeof raw.date === 'string' ? raw.date : undefined,
      patientId: typeof raw.patientId === 'string' ? raw.patientId : undefined,
      requestNumber: typeof raw.requestNumber === 'string' ? raw.requestNumber : undefined,
      revision,
      updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : undefined,
      runId: typeof raw.runId === 'string' ? raw.runId : undefined,
    };
  } catch {
    if (!eventType) return null;
    return { type: eventType };
  }
};

export function startReceptionRealtimeStream(options: ReceptionRealtimeStreamOptions) {
  const {
    apiBaseUrl = DEFAULT_API_BASE_URL,
    retryDelayMs = DEFAULT_RETRY_DELAY_MS,
    maxRetryDelayMs = DEFAULT_MAX_RETRY_DELAY_MS,
    onStatusChange,
    onMessage,
    onError,
  } = options;

  if (isReceptionRealtimeStreamDisabled()) {
    onStatusChange?.('unavailable');
    return () => {};
  }
  if (typeof window === 'undefined') {
    onStatusChange?.('unavailable');
    return () => {};
  }
  const eventSourceSupported = typeof window.EventSource === 'function';
  const fetchStreamSupported = isFetchStreamSupported();
  if (!fetchStreamSupported && !eventSourceSupported) {
    onStatusChange?.('unavailable');
    return () => {};
  }

  // VITE_API_BASE_URL may already include the sub-path (/medical_chart/api); withBasePath is idempotent.
  const streamUrl = withBasePath(`${apiBaseUrl}${RECEPTION_STREAM_PATH}`);
  let currentSource: EventSource | null = null;
  let currentController: AbortController | null = null;
  let reconnectTimer: number | null = null;
  let reconnectDelay = Math.max(250, retryDelayMs);
  let lastEventId: string | undefined;
  let useEventSourceFallback = !fetchStreamSupported && eventSourceSupported;
  let stopped = false;
  let connectedAtLeastOnce = false;

  const clearReconnectTimer = () => {
    if (reconnectTimer === null) return;
    window.clearTimeout(reconnectTimer);
    reconnectTimer = null;
  };

  const closeCurrentSource = () => {
    if (!currentSource) return;
    currentSource.close();
    currentSource = null;
  };

  const closeCurrentFetchStream = () => {
    if (!currentController) return;
    currentController.abort();
    currentController = null;
  };

  const closeCurrentConnection = () => {
    closeCurrentSource();
    closeCurrentFetchStream();
  };

  const emitStatus = (status: ReceptionRealtimeConnectionStatus) => {
    onStatusChange?.(status);
  };

  const handleParsedEvent = (eventType: string, payload: string, eventLastEventId?: string) => {
    if (eventType === 'reception.keepalive') {
      return;
    }
    const parsed = parseEvent(payload, eventType, eventLastEventId);
    if (parsed) {
      onMessage?.(parsed);
    }
  };

  const scheduleReconnect = () => {
    if (stopped) return;
    emitStatus('reconnecting');
    clearReconnectTimer();
    closeCurrentConnection();
    reconnectTimer = window.setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, maxRetryDelayMs);
  };

  const connectWithEventSource = () => {
    try {
      const source = new window.EventSource(streamUrl, { withCredentials: true });
      currentSource = source;

      source.onopen = () => {
        connectedAtLeastOnce = true;
        reconnectDelay = Math.max(250, retryDelayMs);
        emitStatus('open');
      };

      source.onmessage = (event) => handleParsedEvent('message', event.data, event.lastEventId);
      source.addEventListener('reception.updated', (event) => {
        const messageEvent = event as MessageEvent<string>;
        handleParsedEvent('reception.updated', messageEvent.data, messageEvent.lastEventId);
      });
      source.addEventListener('reception.replay-gap', (event) => {
        const messageEvent = event as MessageEvent<string>;
        handleParsedEvent('reception.replay-gap', messageEvent.data, messageEvent.lastEventId);
      });
      source.addEventListener('reception.keepalive', () => {
        // no-op
      });

      source.onerror = () => {
        if (stopped) return;
        onError?.(new Error('reception realtime stream error'));
        scheduleReconnect();
      };
    } catch (error) {
      onError?.(toError(error));
      scheduleReconnect();
    }
  };

  const connectWithFetchStream = async () => {
    const controller = new window.AbortController();
    currentController = controller;
    try {
      const headers = new Headers({
        Accept: 'text/event-stream',
      });
      if (lastEventId) {
        headers.set('Last-Event-ID', lastEventId);
      }
      const response = await httpFetch(streamUrl, {
        method: 'GET',
        headers,
        credentials: 'include',
        signal: controller.signal,
        notifySessionExpired: false,
      });

      if (stopped || controller.signal.aborted) return;
      if (!response.ok) {
        throw new Error(`reception realtime stream failed: ${response.status}`);
      }

      connectedAtLeastOnce = true;
      reconnectDelay = Math.max(250, retryDelayMs);
      emitStatus('open');

      await streamSseEvents(response, controller.signal, (event) => {
        const eventType = event.event?.trim() || 'message';
        const receivedEventId = event.id?.trim();
        const effectiveLastEventId = receivedEventId || lastEventId;
        if (receivedEventId) {
          lastEventId = receivedEventId;
        }
        handleParsedEvent(eventType, event.data ?? '', effectiveLastEventId);
      });

      if (stopped || controller.signal.aborted) return;
      onError?.(new Error('reception realtime stream closed'));
      scheduleReconnect();
    } catch (error) {
      if (stopped || controller.signal.aborted) return;

      if (toError(error).message === FETCH_STREAM_UNSUPPORTED_ERROR_CODE && eventSourceSupported && !useEventSourceFallback) {
        useEventSourceFallback = true;
        connect();
        return;
      }

      onError?.(toError(error));
      scheduleReconnect();
    }
  };

  const connect = () => {
    if (stopped) return;
    emitStatus(connectedAtLeastOnce ? 'reconnecting' : 'connecting');
    closeCurrentConnection();
    if (useEventSourceFallback) {
      connectWithEventSource();
      return;
    }
    void connectWithFetchStream();
  };

  connect();

  return () => {
    stopped = true;
    clearReconnectTimer();
    closeCurrentConnection();
    emitStatus('closed');
  };
}
