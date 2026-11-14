import axios from 'axios';

export type UserRole = 'ROLE_ADMIN' | 'ROLE_SUPERVISOR' | 'ROLE_STUDENT' | (string & {});

export interface DecodedJwtPayload {
  sub?: string;
  role?: UserRole;
  exp?: number;
  jti?: string;
  token_type?: string;
}

export interface ProblemFieldError {
  field?: string;
  message?: string;
}

export interface ProblemPayload {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  message?: string;
  code?: string;
  instance?: string;
  errors?: ProblemFieldError[];
}

const TOKEN_STORAGE_KEY = 'token';

export function decodeJwtPayload(token: string | null | undefined): DecodedJwtPayload | null {
  if (!token || typeof token !== 'string') {
    return null;
  }
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) {
    return null;
  }
  try {
    const base64Url = parts[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const decoded = atob(padded);
    const parsed: unknown = JSON.parse(decoded);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    return parsed as DecodedJwtPayload;
  } catch {
    return null;
  }
}

export function getStoredToken(): string | null {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY);
  if (!token) {
    return null;
  }
  const payload = decodeJwtPayload(token);
  if (!payload || typeof payload.role !== 'string' || payload.role.trim() === '') {
    clearStoredToken();
    return null;
  }
  return token;
}

export function getStoredRole(): UserRole | null {
  const token = getStoredToken();
  if (!token) {
    return null;
  }
  return decodeJwtPayload(token)?.role ?? null;
}

export function isAdminRole(): boolean {
  return getStoredRole() === 'ROLE_ADMIN';
}

export function setStoredToken(token: string): void {
  localStorage.setItem(TOKEN_STORAGE_KEY, token);
  axios.defaults.headers.common.Authorization = `Bearer ${token}`;
}

export function clearStoredToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  delete axios.defaults.headers.common.Authorization;
}

export async function parseErrorResponseData(data: unknown): Promise<ProblemPayload | null> {
  if (!data) {
    return null;
  }
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    try {
      const text = await data.text();
      if (!text) return null;
      const parsed: unknown = JSON.parse(text);
      return typeof parsed === 'object' && parsed !== null ? (parsed as ProblemPayload) : null;
    } catch {
      return null;
    }
  }
  if (typeof data === 'string') {
    try {
      const parsed: unknown = JSON.parse(data);
      return typeof parsed === 'object' && parsed !== null ? (parsed as ProblemPayload) : { detail: data };
    } catch {
      return { detail: data };
    }
  }
  if (typeof data === 'object' && !Array.isArray(data)) {
    return data as ProblemPayload;
  }
  return null;
}

export function extractProblemDataSync(error: unknown): ProblemPayload | null {
  if (!axios.isAxiosError(error) || !error.response?.data) {
    return null;
  }
  const data: unknown = error.response.data;
  if (typeof data === 'string') {
    try {
      const parsed: unknown = JSON.parse(data);
      return typeof parsed === 'object' && parsed !== null ? (parsed as ProblemPayload) : { detail: data };
    } catch {
      return { detail: data };
    }
  }
  if (typeof data === 'object' && !Array.isArray(data) && !(typeof Blob !== 'undefined' && data instanceof Blob)) {
    return data as ProblemPayload;
  }
  return null;
}

export function formatProblemMessage(
  problem: ProblemPayload | null,
  status?: number,
  retryAfterHeader?: string,
  fallback = 'Request failed. Please try again.'
): string {
  const firstFieldError = problem?.errors?.[0]?.message;
  const baseMessage =
    problem?.detail?.trim() ||
    firstFieldError?.trim() ||
    problem?.message?.trim() ||
    problem?.title?.trim() ||
    fallback;

  if (status === 429 && retryAfterHeader) {
    const seconds = Number(retryAfterHeader);
    if (
      Number.isFinite(seconds) &&
      seconds > 0 &&
      !baseMessage.toLowerCase().includes('second') &&
      !baseMessage.toLowerCase().includes('retry')
    ) {
      return `${baseMessage} Try again in ${Math.ceil(seconds)}s.`;
    }
  }
  return baseMessage;
}

export function extractProblemMessage(error: unknown, fallback = 'Request failed. Please try again.'): string {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error && error.message ? error.message : fallback;
  }
  const problem = extractProblemDataSync(error);
  const retryAfter =
    typeof error.response?.headers?.['retry-after'] === 'string'
      ? error.response.headers['retry-after']
      : undefined;
  return formatProblemMessage(problem, error.response?.status, retryAfter, fallback);
}

export async function extractProblemMessageAsync(
  error: unknown,
  fallback = 'Request failed. Please try again.'
): Promise<string> {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error && error.message ? error.message : fallback;
  }
  const problem = await parseErrorResponseData(error.response?.data);
  const retryAfter =
    typeof error.response?.headers?.['retry-after'] === 'string'
      ? error.response.headers['retry-after']
      : undefined;
  return formatProblemMessage(problem, error.response?.status, retryAfter, fallback);
}

let authRedirectInProgress = false;

export function setAuthRedirecting(value: boolean): void {
  authRedirectInProgress = value;
}

export function isAuthRedirecting(): boolean {
  return authRedirectInProgress;
}

export function isSessionEvictedProblem(problem: ProblemPayload | null): boolean {
  if (!problem) {
    return false;
  }
  return (
    problem.code === 'SESSION_EVICTED' ||
    problem.detail === 'Session active on another device' ||
    problem.message === 'Session active on another device'
  );
}
