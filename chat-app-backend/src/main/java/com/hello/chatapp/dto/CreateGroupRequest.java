package com.hello.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupRequest {

    @NotBlank(message = "Group name is required")
    @Size(max = 200, message = "name must be at most 200 characters")
    private String name;

    @Size(max = 1000, message = "description must be at most 1000 characters")
    private String description;

    @NotEmpty(message = "At least one participant is required")
    private List<Long> participantIds;
}
