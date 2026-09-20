package com.seastella.troubleshooting.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * The conversation on a request: guided checks, live chat and system notes in
 * one thread (SoW §6.1, CHT-04 to CHT-10).
 *
 * <p>Reading needs the request in scope; {@code after} returns only newer
 * messages, so a polling client fetches little. Writing is the Captain's and
 * the Coordinator's, and only while a live agent is engaged.
 */
@RestController
@RequestMapping("/api/v1/service-requests/{requestId}/conversation")
class LiveChatController {

    private final LiveChatService chat;

    LiveChatController(LiveChatService chat) {
        this.chat = chat;
    }

    @GetMapping
    ResponseEntity<LiveChatService.ChatView> view(@PathVariable Long requestId,
                                                  @RequestParam(required = false) Long after) {
        return ResponseEntity.ok(chat.view(requestId, after));
    }

    /** Find a word in this thread (CHT-09). */
    @GetMapping("/search")
    ResponseEntity<List<LiveChatService.MessageView>> search(@PathVariable Long requestId,
                                                             @RequestParam String q) {
        return ResponseEntity.ok(chat.search(requestId, q));
    }

    @PostMapping("/messages")
    @PreAuthorize("hasAnyRole('CAPTAIN','SERVICE_COORDINATOR')")
    ResponseEntity<LiveChatService.MessageView> send(@PathVariable Long requestId, @RequestBody MessageBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                chat.send(requestId, body == null ? null : body.body(), body == null ? null : body.clientMsgId()));
    }

    /** A photograph, a video or a document, sent in the chat (CHT-08). */
    @PostMapping("/attachments")
    @PreAuthorize("hasAnyRole('CAPTAIN','SERVICE_COORDINATOR')")
    ResponseEntity<LiveChatService.MessageView> attach(@PathVariable Long requestId,
                                                        @RequestParam("file") MultipartFile file,
                                                        @RequestParam(required = false) String caption,
                                                        @RequestParam(required = false) String clientMsgId)
            throws IOException {
        byte[] content = file == null ? new byte[0] : file.getBytes();
        return ResponseEntity.status(HttpStatus.CREATED).body(chat.sendAttachment(
                requestId, file == null ? null : file.getOriginalFilename(), content, caption, clientMsgId));
    }

    /** Marks the thread read as far as a message, or to the end (CHT-07). */
    @PostMapping("/read")
    ResponseEntity<LiveChatService.ReadView> read(@PathVariable Long requestId, @RequestBody(required = false) ReadBody body) {
        return ResponseEntity.ok(chat.markRead(requestId, body == null ? null : body.lastMessageId()));
    }

    record MessageBody(String body, String clientMsgId) {}

    record ReadBody(Long lastMessageId) {}
}
