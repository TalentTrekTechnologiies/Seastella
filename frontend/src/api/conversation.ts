import { api, downloadFile, uploadFile } from './client';

/**
 * The conversation on a service request (SoW §6.1).
 *
 * <p>One thread, not two: the guided checks, the live chat with the
 * Coordinator and the platform's own notes are the same transcript, and the
 * status says where it is — ASSISTANT while the checks run, LIVE once a
 * Captain has escalated, CLOSED when the request moves on.
 */

export interface ChatAttachment {
  documentId: number;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  image: boolean;
  video: boolean;
}

export interface ChatMessage {
  id: number;
  kind: 'USER' | 'SYSTEM' | 'ASSISTANT';
  senderName?: string;
  senderRole?: string;
  body: string;
  sentAt: string;
  mine: boolean;
  attachment?: ChatAttachment;
}

export interface ChatView {
  status: 'NONE' | 'ASSISTANT' | 'LIVE' | 'CLOSED';
  canSend: boolean;
  openedAt?: string;
  escalatedAt?: string;
  closedAt?: string;
  /** How many lines this reader has not seen. */
  unreadCount: number;
  lastReadMessageId?: number;
  /** How far the other side has read — the "seen" mark on your own last line. */
  readByOthersMessageId?: number;
  messages: ChatMessage[];
}

export interface ReadState {
  lastReadMessageId?: number;
  unreadCount: number;
}

export const fetchChat = (requestId: number) => api.get<ChatView>(`/api/v1/service-requests/${requestId}/conversation`);

export const sendChatMessage = (requestId: number, body: string, clientMsgId: string) =>
  api.post<ChatMessage>(`/api/v1/service-requests/${requestId}/conversation/messages`, { body, clientMsgId });

/** Marks the thread read as far as a message, or to the end when none is given. */
export const markChatRead = (requestId: number, lastMessageId?: number) =>
  api.post<ReadState>(`/api/v1/service-requests/${requestId}/conversation/read`, { lastMessageId });

export const searchChat = (requestId: number, q: string) =>
  api.get<ChatMessage[]>(`/api/v1/service-requests/${requestId}/conversation/search?q=${encodeURIComponent(q)}`);

/** A photograph, a video or a document, sent in the chat. */
export const sendChatAttachment = (requestId: number, file: File, caption: string, clientMsgId: string) =>
  uploadFile<ChatMessage>(`/api/v1/service-requests/${requestId}/conversation/attachments`, file, {
    caption: caption.trim() || undefined,
    clientMsgId,
  });

/** Where the bytes live. The server re-checks scope on every read. */
export const attachmentPath = (documentId: number) => `/api/v1/documents/${documentId}/content`;

export const downloadAttachment = (attachment: ChatAttachment) =>
  downloadFile(attachmentPath(attachment.documentId), attachment.fileName);
