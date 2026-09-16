/**
 * Reads the user-facing `message` from a parsed `ErrorResponse` JSON body.
 */
export function messageFromApiError(body: unknown, fallbackMessage: string): string {
  if (body && typeof body === "object" && "message" in body) {
    const message = (body as { message: unknown }).message;
    if (typeof message === "string" && message.trim()) {
      return message.trim();
    }
  }
  return fallbackMessage;
}

export function toUserErrorMessage(error: unknown, fallbackMessage: string): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }
  return fallbackMessage;
}
