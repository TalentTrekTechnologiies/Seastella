package com.seastella.troubleshooting.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's request threads, newest first, with unread counts: what the
 * chat button in the app shows. Scope decides which threads are listed.
 */
@RestController
@RequestMapping("/api/v1/conversations")
class ConversationInboxController {

    private final LiveChatService chat;

    ConversationInboxController(LiveChatService chat) {
        this.chat = chat;
    }

    @GetMapping
    ResponseEntity<LiveChatService.Inbox> inbox() {
        return ResponseEntity.ok(chat.inbox());
    }
}
