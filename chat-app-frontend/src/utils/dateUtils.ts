/**
 * Formats a timestamp as a short relative string, e.g. "5min ago", "2d ago".
 */
export function formatRelativeTime(timestamp?: string | number | Date | null): string {
  if (!timestamp) {
    return "just now";
  }

  const targetTime = new Date(timestamp).getTime();

  if (Number.isNaN(targetTime)) {
    return "just now";
  }

  const diffInSeconds = Math.max(0, Math.floor((Date.now() - targetTime) / 1000));

  if (diffInSeconds < 60) {
    return "just now";
  }

  const units = [
    { label: "y", seconds: 60 * 60 * 24 * 365 },
    { label: "mo", seconds: 60 * 60 * 24 * 30 },
    { label: "d", seconds: 60 * 60 * 24 },
    { label: "h", seconds: 60 * 60 },
    { label: "min", seconds: 60 },
  ];

  for (const unit of units) {
    if (diffInSeconds >= unit.seconds) {
      const value = Math.floor(diffInSeconds / unit.seconds);
      return `${value}${unit.label} ago`;
    }
  }

  return "just now";
}

/**
 * Formats a timestamp as an absolute local date/time string, e.g. "29/04/2026, 14:30".
 * Uses the en-GB locale for a stable day/month order.
 */
export function formatAbsoluteTimeVi(timestamp?: string | number | Date | null): string {
  if (!timestamp) {
    return "";
  }

  const date = new Date(timestamp);

  if (Number.isNaN(date.getTime())) {
    return "";
  }

  // E.g. "29/04/2026, 14:30"
  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}
