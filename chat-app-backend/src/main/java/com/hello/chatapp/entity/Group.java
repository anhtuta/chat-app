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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
    private Instant createdAt;

    @Column(name = "latest_message", length = 1000)
    private String latestMessage;

    @Column(name = "latest_message_sender", length = 255)
    private String latestMessageSender;

    @Column(name = "latest_message_at")
    private Instant latestMessageAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archived_by")
    private User archivedBy;

    @Column(name = "archive_reason", length = 64)
    private String archiveReason;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Group(String name, User createdBy) {
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Treats legacy naive wall-clock timestamps as UTC during the datetime migration.
     */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt == null ? null : createdAt.toInstant(ZoneOffset.UTC);
    }

    /**
     * Transitional helper for tests and still-naive callers during the phased UTC migration.
     * Interprets the provided wall-clock value as UTC.
     */
    public void setLatestMessageAt(Instant latestMessageAt) {
        this.latestMessageAt = latestMessageAt;
    }

    /**
     * Transitional helper for tests and still-naive callers during the phased UTC migration.
     * Interprets the provided wall-clock value as UTC.
     */
    public void setLatestMessageAt(LocalDateTime latestMessageAt) {
        this.latestMessageAt = latestMessageAt == null ? null : latestMessageAt.toInstant(ZoneOffset.UTC);
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    /**
     * Treats legacy naive wall-clock timestamps as UTC during the datetime migration.
     */
    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt == null ? null : archivedAt.toInstant(ZoneOffset.UTC);
    }
}
