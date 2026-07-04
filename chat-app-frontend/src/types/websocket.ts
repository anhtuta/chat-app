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
  subscribeGroupUpdates: (topic: string, callback: TopicCallback<GroupSummaryUpdate>) => Unsubscribe;
  sendMessage: (destination: string, message: WebSocketOutboundMessage) => boolean;
}

export interface ChatTopicSubscriptionEntry {
  topic: string;
  callback: TopicCallback<ChatMessage>;
  subscription: StompSubscription | null;
}

export interface PersonalSubscriptionEntry<TPayload> {
  callback: TopicCallback<TPayload>;
  subscription: StompSubscription | null;
  refCount: number;
  cleanupTimer: ReturnType<typeof setTimeout> | null;
}
