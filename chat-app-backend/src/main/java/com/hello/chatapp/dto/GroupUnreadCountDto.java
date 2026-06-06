package com.hello.chatapp.dto;

public class GroupUnreadCountDto {
    private final Long groupId;
    private final long unreadCount;

    public GroupUnreadCountDto(Long groupId, long unreadCount) {
        this.groupId = groupId;
        this.unreadCount = unreadCount;
    }

    public Long getGroupId() {
        return groupId;
    }

    public long getUnreadCount() {
        return unreadCount;
    }
}
