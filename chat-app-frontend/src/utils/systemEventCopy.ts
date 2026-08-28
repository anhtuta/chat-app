import { SYSTEM_EVENT_TYPES } from "../constant/systemEventTypes";
import type { ChatMessage, ChatUser } from "../types/chat";

/** Display name for a user on a structured system line. */
export function getDisplayUserName(user: ChatUser | null | undefined): string {
  if (!user) {
    return "Someone";
  }
  return user.fullname || user.username || "Someone";
}

/** Joins added-member names, or "Someone" when the list is empty. */
export function formatNameList(names: string[]): string {
  return names.filter((name) => name && name.trim()).join(", ") || "Someone";
}

/**
 * Client copy for structured {@code SYSTEM} messages.
 * Mirrors the backend event enum; does not call the API.
 */
export function formatStructuredSystemMessage(message: ChatMessage): string {
  const actorName = getDisplayUserName(message.systemEventActor);
  const subjectName = getDisplayUserName(message.user);
  const subjectNames = message.systemEventPayload?.subjectNames;
  const addedNames =
    subjectNames && subjectNames.length > 0
      ? formatNameList(subjectNames)
      : subjectName;

  switch (message.systemEventType) {
    case SYSTEM_EVENT_TYPES.USER_JOINED:
      return actorName === subjectName && (!subjectNames || subjectNames.length <= 1)
        ? `${subjectName} has joined the group`
        : `${actorName} has added ${addedNames}`;
    case SYSTEM_EVENT_TYPES.USER_LEFT:
      return `${subjectName} has left the group`;
    case SYSTEM_EVENT_TYPES.USER_KICKED:
      return `${subjectName} has been kicked out of the group by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_BANNED:
      return `${subjectName} has been banned by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_UNBANNED:
      return `${subjectName} has been unbanned by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_PROMOTED:
      return `${actorName} promoted ${subjectName}`;
    case SYSTEM_EVENT_TYPES.USER_DEMOTED:
      return `${actorName} demoted ${subjectName}`;
    case SYSTEM_EVENT_TYPES.LEADERSHIP_TRANSFERRED:
      return `${actorName} transferred leadership to ${subjectName}`;
    case SYSTEM_EVENT_TYPES.GROUP_NAME_UPDATED:
      return `${actorName} updated the group name`;
    case SYSTEM_EVENT_TYPES.GROUP_DESCRIPTION_UPDATED:
      return `${actorName} updated the group description`;
    case SYSTEM_EVENT_TYPES.GROUP_ARCHIVED:
      return `${actorName} archived the group`;
    default:
      return message.content || "System event";
  }
}
