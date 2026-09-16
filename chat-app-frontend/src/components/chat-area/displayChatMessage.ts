import type { ChatMessage, PendingMediaMessage } from "../../types/chat";

export type DisplayChatMessage = ChatMessage | PendingMediaMessage;

export function getPendingLocalId(message: DisplayChatMessage): string | undefined {
  return "localId" in message ? message.localId : undefined;
}
