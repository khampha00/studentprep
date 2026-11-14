import type { AxiosResponse } from 'axios';

const sanitizeFilenameSegment = (value: string | undefined | null, fallback: string): string => {
  if (!value) return fallback;
  const sanitized = value.trim().replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return sanitized || fallback;
};

/**
 * Formats a safe download filename containing the candidate's name and registration number.
 */
export function buildResultSlipFilename(
  studentName?: string | null,
  registrationNumber?: string | null,
  fallbackId?: string | null
): string {
  const safeName = sanitizeFilenameSegment(studentName, 'Student');
  const safeReg = sanitizeFilenameSegment(registrationNumber, fallbackId || 'Result');
  return `${safeName}_${safeReg}-Result-Slip.pdf`;
}

/**
 * Extracts a filename from a server Content-Disposition header when exposed, or returns fallback.
 */
export function extractFilenameFromDisposition(
  disposition: string | undefined,
  fallbackFilename: string
): string {
  if (!disposition) return fallbackFilename;

  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8Match?.[1]) {
    try {
      const decoded = decodeURIComponent(utf8Match[1].trim());
      if (decoded) return decoded;
    } catch {
      // ignore malformed URI sequence and fall back to standard filename=
    }
  }

  const asciiMatch = /filename="([^"]+)"|filename=([^;]+)/i.exec(disposition);
  const rawFilename = (asciiMatch?.[1] || asciiMatch?.[2] || '').trim();
  return rawFilename || fallbackFilename;
}

/**
 * Triggers a browser file download from an Axios PDF blob response, preferring the server filename
 * unless explicitly overridden via {@code preferServerFilename = false}.
 */
export function triggerPdfResponseDownload(
  response: AxiosResponse<Blob>,
  fallbackFilename: string,
  preferServerFilename = true
): void {
  const headerDisposition =
    preferServerFilename && typeof response.headers?.['content-disposition'] === 'string'
      ? response.headers['content-disposition']
      : undefined;
  const resolvedFilename = extractFilenameFromDisposition(headerDisposition, fallbackFilename);

  const blob = new Blob([response.data], { type: 'application/pdf' });
  const downloadUrl = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = downloadUrl;
  link.setAttribute('download', resolvedFilename);
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(downloadUrl);
}
