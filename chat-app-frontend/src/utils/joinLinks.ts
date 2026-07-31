/**
 * Returns an internal app path suitable for post-login redirects, or null if unsafe.
 */
export function getSafeInternalPath(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  const trimmed = value.trim();
  if (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.includes("://")) {
    return null;
  }
  return trimmed;
}

/**
 * Builds a shareable join URL for a raw join-link token.
 */
export function buildJoinLinkUrl(token: string, origin: string = window.location.origin): string {
  return `${origin}/join/${encodeURIComponent(token.trim())}`;
}
