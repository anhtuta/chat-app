package com.hello.chatapp.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGroupRequest {

    @Size(max = 200, message = "name must be at most 200 characters")
    private String name;

    @Size(max = 1000, message = "description must be at most 1000 characters")
    private String description;
}
