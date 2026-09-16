package com.hello.chatapp.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Persisted chat group, including optional member capacity ({@code maxMembers}).
 */
@Entity
@Table(name = "groups")
@Getter
@Setter
@NoArgsConstructor
public class Group {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    /**
     * Optional active-member cap. {@code null} and {@code 0} mean unlimited.
     * Positive values are the configured capacity. Never store a negative value.
     */
    @Column(name = "max_members")
    private Integer maxMembers;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "latest_message", length = 1000)
    private String latestMessage;

    @Column(name = "latest_message_sender", length = 255)
    private String latestMessageSender;

    @Column(name = "latest_message_at")
    private LocalDateTime latestMessageAt;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private User archivedBy;

    @Column(name = "archive_reason", length = 64)
    private String archiveReason;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Group(String name, User createdBy) {
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }
}
