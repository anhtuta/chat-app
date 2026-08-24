package com.hello.chatapp.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import com.hello.chatapp.constant.MessageType;
import java.time.LocalDateTime;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Chat message row ({@code messages}): text, media, and system events.
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Primary user on the message row ({@code messages.user_id}).
     * <ul>
     * <li>{@link MessageType#TEXT} / media: the author who sent the message</li>
     * <li>{@link MessageType#SYSTEM}: the <em>subject</em> of the event (who joined, was kicked,
     * was promoted, etc.), not necessarily who performed the action</li>
     * </ul>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = true)
    private Group group;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 32)
    private MessageType messageType = MessageType.TEXT;

    // For SYSTEM messages, content stores a stable SystemEventType name
    // (for example USER_JOINED) instead of final human-readable text.
    @Column(nullable = true, length = 1000)
    private String content;

    /**
     * Optional extra subject display names for batch membership events (JSON array).
     * {@link #user} remains the first subject. Null for single-subject events.
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "system_event_subject_names", columnDefinition = "TEXT")
    private List<String> systemEventSubjectNames;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    /**
     * Second user reference ({@code messages.updated_by}). Meaning depends on message type:
     * <ul>
     * <li>{@link MessageType#TEXT}: who last edited the message content (with {@code updatedAt});
     * null until the first edit</li>
     * <li>{@link MessageType#SYSTEM}: the <em>actor</em> who performed the event (exposed to clients
     * as {@code systemEventActor}); may equal {@link #user} for self-actions such as leave
     * or self-join</li>
     * <li>media: typically unused for content edits (media is not editable)</li>
     * </ul>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Who soft-deleted this message ({@code messages.deleted_by}), with {@code deletedAt}.
     * Null while the message is not deleted. Own-message deletes and moderator deletes both
     * set this field; content/attachments are hidden from API responses once deleted.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by")
    private User deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * Optimistic lock counter ({@code messages.version}). Hibernate includes it in
     * {@code UPDATE … WHERE version = ?} and increments on a successful flush.
     * Concurrent edit/delete of a stale snapshot fails with 409 instead of last-write-wins.
     * Leave {@code null} on new transient instances so Hibernate treats them as unsaved.
     * Distinct from {@link #updatedAt}, which is business “last content edit” time.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @OneToMany(mappedBy = "message", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("attachmentOrder ASC, id ASC")
    private List<MessageMedia> attachments = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (messageType == null) {
            messageType = MessageType.TEXT;
        }
    }

    public Message(User user, String content) {
        this.user = user;
        this.messageType = MessageType.TEXT;
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    public void addAttachment(MessageMedia attachment) {
        if (attachment == null) {
            return;
        }
        attachment.setMessage(this);
        attachments.add(attachment);
    }
}
