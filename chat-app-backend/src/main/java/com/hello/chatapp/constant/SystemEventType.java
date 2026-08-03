package com.hello.chatapp.constant;

/**
 * Stable system-event codes stored in {@code messages.content} for {@link MessageType#SYSTEM} rows.
 * Clients render human text from this enum; sidebar previews use {@link #latestPreview()}.
 */
public enum SystemEventType {
    USER_JOINED,
    USER_LEFT,
    USER_KICKED,
    USER_BANNED,
    USER_UNBANNED,
    USER_PROMOTED,
    USER_DEMOTED,
    LEADERSHIP_TRANSFERRED,
    GROUP_NAME_UPDATED,
    GROUP_DESCRIPTION_UPDATED,
    GROUP_ARCHIVED;

    /**
     * Short human preview used for group latest-message / sidebar summaries.
     * Full chat copy is rendered on the client from the enum value itself.
     */
    public String latestPreview() {
        return switch (this) {
            case USER_JOINED -> "Member joined";
            case USER_LEFT -> "Member left";
            case USER_KICKED -> "Member removed";
            case USER_BANNED -> "Member banned";
            case USER_UNBANNED -> "Member unbanned";
            case USER_PROMOTED -> "Member promoted";
            case USER_DEMOTED -> "Member demoted";
            case LEADERSHIP_TRANSFERRED -> "Leadership transferred";
            case GROUP_NAME_UPDATED -> "Group name updated";
            case GROUP_DESCRIPTION_UPDATED -> "Group description updated";
            case GROUP_ARCHIVED -> "Group archived";
        };
    }
}
