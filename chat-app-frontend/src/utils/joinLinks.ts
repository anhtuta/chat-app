/**
 * Returns an internal app path suitable for post-login redirects, or null if unsafe.
 *
 * Normalizes before safety checks so browser URL quirks cannot bypass them:
 * - ASCII control characters are stripped (parsers may drop them, e.g. "/\\t/x" → "//x")
 * - Backslashes become slashes (browsers treat "\\" like "/" in URLs)
 */
export function getSafeInternalPath(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }

  const normalized = value
    .trim()
    .replace(/[\u0000-\u001F\u007F]/g, "")
    .replace(/\\/g, "/");

  if (!normalized.startsWith("/") || normalized.startsWith("//") || normalized.includes("://")) {
    return null;
  }
  return normalized;
}

/**
 * Builds a shareable join URL for a raw join-link token.
 */
export function buildJoinLinkUrl(token: string, origin: string = window.location.origin): string {
  return `${origin}/join/${encodeURIComponent(token.trim())}`;
}
