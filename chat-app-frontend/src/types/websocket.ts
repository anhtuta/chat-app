import type { StompSubscription } from "@stomp/stompjs";
import type { ChatMessage } from "./chat";
import type { GroupSummaryUpdate } from "./groups";

export type TopicCallback<TPayload> = (payload: TPayload) => void;

export type Unsubscribe = () => void;

export interface WebSocketOutboundMessage {
  content: string;
  groupId?: number;
}

export interface WebSocketContextValue {
  isConnected: boolean;
  subscribeSingleGroup: (topic: string, callback: TopicCallback<ChatMessage>) => Unsubscribe;
  unsubscribeSingleGroup: () => void;
  setGroupUpdatesHandler: (callback: TopicCallback<GroupSummaryUpdate> | null) => void;
  sendMessage: (destination: string, message: WebSocketOutboundMessage) => boolean;
}

/** Active chat channel subscription (`/topic/public` or `/topic/group.{id}`). */
export interface GroupSubscription {
  topic: string;
  callback: TopicCallback<ChatMessage>;
  subscription: StompSubscription | null;
}

/** Persistent per-user sidebar update subscription (`/topic/user.{username}.group-updates`). */
export interface UserGroupUpdatesSubscription {
  username: string;
  callback: TopicCallback<GroupSummaryUpdate> | null;
  subscription: StompSubscription | null;
}
