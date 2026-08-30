import { SYSTEM_EVENT_TYPES } from "../constant/systemEventTypes";
import type { ChatMessage } from "../types/chat";
import {
  formatNameList,
  formatStructuredSystemMessage,
  getDisplayUserName,
} from "./systemEventCopy";

function buildSystemMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 1,
    messageType: "SYSTEM",
    user: { username: "bob", fullname: "Bob" },
    systemEventActor: { username: "alice", fullname: "Alice" },
    ...overrides,
  };
}

describe("systemEventCopy", () => {
  describe("getDisplayUserName", () => {
    it("prefers fullname, then username, then Someone", () => {
      expect(getDisplayUserName({ username: "alice", fullname: "Alice" })).toBe("Alice");
      expect(getDisplayUserName({ username: "alice" })).toBe("alice");
      expect(getDisplayUserName(null)).toBe("Someone");
    });
  });

  describe("formatNameList", () => {
    it("joins names and falls back when empty", () => {
      expect(formatNameList(["Bob", "Carol"])).toBe("Bob, Carol");
      expect(formatNameList(["", "  "])).toBe("Someone");
    });
  });

  describe("formatStructuredSystemMessage", () => {
    it("renders self-join vs added-by-actor membership copy", () => {
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_JOINED,
        user: { username: "bob", fullname: "Bob" },
        systemEventActor: { username: "bob", fullname: "Bob" },
      }))).toBe("Bob has joined the group");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_JOINED,
        systemEventPayload: { subjectNames: ["Bob", "Carol"] },
      }))).toBe("Alice has added Bob, Carol");
    });

    it("renders leave, kick, ban, unban, role, profile, and archive copy", () => {
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_LEFT,
      }))).toBe("Bob has left the group");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_KICKED,
      }))).toBe("Bob has been kicked out of the group by Alice");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_BANNED,
      }))).toBe("Bob has been banned by Alice");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_UNBANNED,
      }))).toBe("Bob has been unbanned by Alice");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_PROMOTED,
      }))).toBe("Alice promoted Bob");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.USER_DEMOTED,
      }))).toBe("Alice demoted Bob");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.LEADERSHIP_TRANSFERRED,
      }))).toBe("Alice transferred leadership to Bob");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.GROUP_NAME_UPDATED,
      }))).toBe("Alice updated the group name");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.GROUP_DESCRIPTION_UPDATED,
      }))).toBe("Alice updated the group description");
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: SYSTEM_EVENT_TYPES.GROUP_ARCHIVED,
      }))).toBe("Alice archived the group");
    });

    it("falls back when the event type is unknown", () => {
      expect(formatStructuredSystemMessage(buildSystemMessage({
        systemEventType: undefined,
        content: "legacy system line",
      }))).toBe("legacy system line");
    });
  });
});
