package com.hello.chatapp.entity;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "group_bans", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"group_id", "user_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class GroupBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "banned_by", nullable = false)
    private User bannedBy;

    @Column(length = 500)
    private String reason;

    @Column(name = "banned_at", nullable = false)
    private LocalDateTime bannedAt;

    @PrePersist
    protected void onCreate() {
        if (bannedAt == null) {
            bannedAt = LocalDateTime.now();
        }
    }
}
