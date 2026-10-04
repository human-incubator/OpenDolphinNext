/**
 * Sub-path deployment helpers.
 *
 * When the web client is served under a sub-path (e.g. `/medical_chart/` behind a reverse proxy),
 * every same-origin request must stay under that prefix. Build with `VITE_BASE_PATH=/medical_chart`.
 *
 * - `getBasePath()` returns the normalized prefix without a trailing slash (`/medical_chart`), or `/`.
 * - `withBasePath(path)` prefixes root-relative paths (`/api/...`) with the base path. It is idempotent
 *   (already-prefixed paths are returned as-is) and leaves absolute URLs (`https://...`, `//host/...`),
 *   relative paths, `data:`/`blob:` URLs untouched.
 * - `stripBasePath(pathname)` removes the prefix so path-based classification can keep using `/api/...`.
 */

export const normalizeBasePath = (value?: string | null): string => {
  if (!value) return '/';
  const trimmed = value.trim();
  if (!trimmed) return '/';
  const withLeadingSlash = trimmed.startsWith('/') ? trimmed : `/${trimmed}`;
  if (withLeadingSlash === '/') return '/';
  const withoutTrailingSlash = withLeadingSlash.replace(/\/+$/, '');
  return withoutTrailingSlash || '/';
};

let basePathOverride: string | undefined;

/** Test helper: override the base path without mutating import.meta.env. Pass undefined to reset. */
export const setBasePathForTests = (value?: string) => {
  basePathOverride = value === undefined ? undefined : normalizeBasePath(value);
};

export const getBasePath = (): string => {
  if (basePathOverride !== undefined) return basePathOverride;
  return normalizeBasePath(import.meta.env.VITE_BASE_PATH);
};

const hasBasePrefix = (path: string, base: string) =>
  path === base || path.startsWith(`${base}/`) || path.startsWith(`${base}?`) || path.startsWith(`${base}#`);

export const withBasePath = (path: string): string => {
  const base = getBasePath();
  if (base === '/') return path;
  if (typeof path !== 'string') return path;
  // Only root-relative paths. `//host` is protocol-relative (absolute) and must be left alone.
  if (!path.startsWith('/') || path.startsWith('//')) return path;
  if (hasBasePrefix(path, base)) return path;
  return `${base}${path}`;
};

export const stripBasePath = (pathname: string): string => {
  const base = getBasePath();
  if (base === '/') return pathname;
  if (pathname === base) return '/';
  if (pathname.startsWith(`${base}/`)) return pathname.slice(base.length);
  return pathname;
};
