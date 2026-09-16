package com.hello.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PATCH body for {@code PATCH /api/groups/{groupId}}.
 * {@code maxMembers} is presence-aware so omitted, explicit {@code null}, {@code 0},
 * and positive values can be distinguished.
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateGroupRequest {

    @Size(max = 200, message = "name must be at most 200 characters")
    private String name;

    @Size(max = 1000, message = "description must be at most 1000 characters")
    private String description;

    /**
     * Optional member cap when {@link #isMaxMembersPresent()} is {@code true}.
     * Explicit {@code null} and {@code 0} mean unlimited. Values below {@code 0} are rejected.
     */
    @Min(value = 0, message = "maxMembers must not be negative")
    private Integer maxMembers;

    @JsonIgnore
    private boolean maxMembersPresent;

    /**
     * Jackson setter used so an explicit JSON {@code maxMembers} (including {@code null})
     * is distinct from an omitted field.
     *
     * @param maxMembers requested cap, or {@code null} for unlimited
     */
    @JsonSetter("maxMembers")
    public void setMaxMembers(Integer maxMembers) {
        this.maxMembers = maxMembers;
        this.maxMembersPresent = true;
    }
}
