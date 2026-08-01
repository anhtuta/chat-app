import { MESSAGE_TYPES } from "../components/chat-area/mediaUtils";
import type { ChatMessage } from "../types/chat";
import {
  MESSAGE_MODERATION_PERMISSIONS,
  buildLatestMessagePreviewFromMessage,
  canDeleteMessage,
  canEditMessage,
  isMessageOwner,
  truncateLatestPreview,
} from "./messageModeration";

function buildMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 1,
    content: "hello",
    messageType: MESSAGE_TYPES.TEXT,
    user: { username: "alice", fullname: "Alice" },
    ...overrides,
  };
}

describe("messageModeration", () => {
  describe("isMessageOwner", () => {
    it("matches username", () => {
      expect(isMessageOwner(buildMessage(), "alice")).toBe(true);
      expect(isMessageOwner(buildMessage(), "bob")).toBe(false);
      expect(isMessageOwner(buildMessage({ user: null }), "alice")).toBe(false);
    });
  });

  describe("canEditMessage", () => {
    it("allows owners to edit their own text messages", () => {
      expect(canEditMessage({ message: buildMessage(), username: "alice" })).toBe(true);
    });

    it("blocks editing media, deleted, pending, or system messages", () => {
      expect(canEditMessage({
        message: buildMessage({ messageType: MESSAGE_TYPES.IMAGE }),
        username: "alice",
      })).toBe(false);
      expect(canEditMessage({
        message: buildMessage({ deletedAt: "2026-01-01T00:00:00Z" }),
        username: "alice",
      })).toBe(false);
      expect(canEditMessage({
        message: buildMessage({ id: null, localUploadState: { status: "UPLOAD_IN_PROGRESS", progressPercent: 10, errorMessage: "" } }),
        username: "alice",
      })).toBe(false);
      expect(canEditMessage({
        message: buildMessage({ messageType: MESSAGE_TYPES.SYSTEM }),
        username: "alice",
      })).toBe(false);
    });

    it("allows moderators with EDIT_ANY_TEXT_MESSAGE to edit others' text", () => {
      expect(canEditMessage({
        message: buildMessage(),
        username: "bob",
        permissions: [],
      })).toBe(false);
      expect(canEditMessage({
        message: buildMessage(),
        username: "bob",
        permissions: [MESSAGE_MODERATION_PERMISSIONS.EDIT_ANY_TEXT_MESSAGE],
      })).toBe(true);
    });
  });

  describe("canDeleteMessage", () => {
    it("allows owners to delete own text or media", () => {
      expect(canDeleteMessage({ message: buildMessage(), username: "alice" })).toBe(true);
      expect(canDeleteMessage({
        message: buildMessage({ messageType: MESSAGE_TYPES.IMAGE, content: null }),
        username: "alice",
      })).toBe(true);
    });

    it("blocks deleting system or already-deleted messages", () => {
      expect(canDeleteMessage({
        message: buildMessage({ messageType: MESSAGE_TYPES.SYSTEM }),
        username: "alice",
      })).toBe(false);
      expect(canDeleteMessage({
        message: buildMessage({ deletedAt: "2026-01-01T00:00:00Z" }),
        username: "alice",
      })).toBe(false);
    });

    it("allows moderators with DELETE_ANY_MESSAGE to delete others' messages", () => {
      expect(canDeleteMessage({
        message: buildMessage(),
        username: "bob",
        permissions: [],
      })).toBe(false);
      expect(canDeleteMessage({
        message: buildMessage(),
        username: "bob",
        permissions: [MESSAGE_MODERATION_PERMISSIONS.DELETE_ANY_MESSAGE],
      })).toBe(true);
    });
  });

  describe("buildLatestMessagePreviewFromMessage", () => {
    it("returns deleted placeholder and truncates text", () => {
      expect(buildLatestMessagePreviewFromMessage(buildMessage({
        deletedAt: "2026-01-01T00:00:00Z",
      }))).toBe("Message deleted");
      expect(truncateLatestPreview("a".repeat(260))).toHaveLength(255);
      expect(buildLatestMessagePreviewFromMessage(buildMessage({
        messageType: MESSAGE_TYPES.IMAGE,
        attachments: [{ originalFilename: "a.jpg" }],
      }))).toBe("Photo");
    });
  });
});
