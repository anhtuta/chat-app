package com.hello.chatapp.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Extra structured data for {@code SYSTEM} messages, stored as {@code messages.system_event_payload}.
 * Batch add-members uses {@code subjectNames} so history can render without joining users.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SystemEventPayload {

    private List<String> subjectNames;

    /**
     * Builds a payload for a batch add, or {@code null} when there are no names to store.
     */
    public static SystemEventPayload ofSubjectNames(List<String> subjectNames) {
        if (subjectNames == null || subjectNames.isEmpty()) {
            return null;
        }
        return new SystemEventPayload(List.copyOf(subjectNames));
    }
}
