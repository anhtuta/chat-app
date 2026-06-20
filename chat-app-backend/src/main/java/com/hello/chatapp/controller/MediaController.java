package com.hello.chatapp.controller;

import com.hello.chatapp.dto.CompleteMediaMessageRequest;
import com.hello.chatapp.dto.MessageResponse;
import com.hello.chatapp.dto.PrepareMediaMessageRequest;
import com.hello.chatapp.dto.PrepareMediaMessageResponse;
import com.hello.chatapp.dto.RequestMultipartPartUrlsRequest;
import com.hello.chatapp.dto.RequestMultipartPartUrlsResponse;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.service.MediaUploadSessionService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media/messages")
public class MediaController {

    private final MediaUploadSessionService mediaUploadSessionService;

    public MediaController(MediaUploadSessionService mediaUploadSessionService) {
        this.mediaUploadSessionService = mediaUploadSessionService;
    }

    /**
     * Start an upload session for one future media message.
     */
    @PostMapping("/prepare")
    public ResponseEntity<PrepareMediaMessageResponse> prepareUploadSession(
            @Valid @RequestBody PrepareMediaMessageRequest request,
            HttpSession session) {
        User user = getAuthenticatedUser(session);
        return ResponseEntity.ok(mediaUploadSessionService.prepareUploadSession(user, request));
    }

    /**
     * <p>
     * For multipart attachments only — return presigned URLs so the client can upload file chunks
     * directly to object storage (MinIO/S3). Why it exists:
     * - Large files are split into parts client-side.
     * - Presigned URLs are short-lived; the client requests them in batches (partNumbers) instead of
     * getting hundreds upfront at prepare time.
     * - On first call, the backend assigns multipartUploadId and marks the upload UPLOAD_IN_PROGRESS.
     * 
     * Note: can call this endpoint multiple times (e.g. request parts 1–5, upload, then request 6–10).
     * </p>
     */
    @PostMapping("/upload-sessions/{uploadSessionId}/attachments/{attachmentId}/parts")
    public ResponseEntity<RequestMultipartPartUrlsResponse> requestMultipartPartUrls(
            @PathVariable String uploadSessionId,
            @PathVariable String attachmentId,
            @Valid @RequestBody RequestMultipartPartUrlsRequest request,
            HttpSession session) {
        User user = getAuthenticatedUser(session);
        return ResponseEntity.ok(
                mediaUploadSessionService.requestMultipartPartUrls(user, uploadSessionId, attachmentId, request));
    }

    /**
     * Call this after every attachment in the session has been uploaded to storage.
     * This API will verify uploads, run malware scan gating, create the final Message, and publish it over WebSocket.
     */
    @PostMapping("/upload-sessions/{uploadSessionId}/complete")
    public ResponseEntity<MessageResponse> completeUploadSession(
            @PathVariable String uploadSessionId,
            @Valid @RequestBody CompleteMediaMessageRequest request,
            HttpSession session) {
        User user = getAuthenticatedUser(session);
        return ResponseEntity.ok(mediaUploadSessionService.completeUploadSession(user, uploadSessionId, request));
    }

    private User getAuthenticatedUser(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return user;
    }
}
