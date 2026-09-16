package com.hello.chatapp.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hello.chatapp.entity.Group;
import com.hello.chatapp.entity.User;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies group member-limit DTO mapping, PATCH presence, and negative-value rejection.
 */
class GroupMemberLimitDtoTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void createGroupRequest_allowsNullZeroAndPositiveMaxMembers() {
        assertThat(validator.validate(createGroupRequest(null))).isEmpty();
        assertThat(validator.validate(createGroupRequest(0))).isEmpty();
        assertThat(validator.validate(createGroupRequest(100))).isEmpty();
    }

    @Test
    void createGroupRequest_rejectsNegativeMaxMembers() {
        Set<ConstraintViolation<CreateGroupRequest>> violations = validator.validate(createGroupRequest(-1));

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .anyMatch(violation -> "maxMembers".equals(violation.getPropertyPath().toString())
                        && violation.getMessage().contains("must not be negative"));
    }

    @Test
    void updateGroupRequest_omittedMaxMembersLeavesPresenceFalse() throws Exception {
        UpdateGroupRequest request = OBJECT_MAPPER.readValue(
                "{\"name\":\"Study Group\"}",
                UpdateGroupRequest.class);

        assertThat(request.isMaxMembersPresent()).isFalse();
        assertThat(request.getMaxMembers()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void updateGroupRequest_explicitNullMarksPresenceWithUnlimitedValue() throws Exception {
        UpdateGroupRequest request = OBJECT_MAPPER.readValue(
                "{\"maxMembers\":null}",
                UpdateGroupRequest.class);

        assertThat(request.isMaxMembersPresent()).isTrue();
        assertThat(request.getMaxMembers()).isNull();
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void updateGroupRequest_zeroAndPositiveValuesArePresent() throws Exception {
        UpdateGroupRequest zero = OBJECT_MAPPER.readValue("{\"maxMembers\":0}", UpdateGroupRequest.class);
        UpdateGroupRequest positive = OBJECT_MAPPER.readValue("{\"maxMembers\":100}", UpdateGroupRequest.class);

        assertThat(zero.isMaxMembersPresent()).isTrue();
        assertThat(zero.getMaxMembers()).isZero();
        assertThat(positive.isMaxMembersPresent()).isTrue();
        assertThat(positive.getMaxMembers()).isEqualTo(100);
        assertThat(validator.validate(zero)).isEmpty();
        assertThat(validator.validate(positive)).isEmpty();
    }

    @Test
    void updateGroupRequest_rejectsNegativeMaxMembers() throws Exception {
        UpdateGroupRequest request = OBJECT_MAPPER.readValue("{\"maxMembers\":-5}", UpdateGroupRequest.class);

        assertThat(request.isMaxMembersPresent()).isTrue();
        assertThat(validator.validate(request))
                .anyMatch(violation -> "maxMembers".equals(violation.getPropertyPath().toString())
                        && violation.getMessage().contains("must not be negative"));
    }

    @Test
    void groupResponse_fromGroupMapsMaxMembers() {
        User creator = new User("alice", "secret", "Alice");
        creator.setId(1L);
        Group group = new Group("Study Group", creator);
        group.setId(10L);
        group.setMaxMembers(100);

        GroupResponse response = GroupResponse.fromGroup(group);

        assertThat(response.getMaxMembers()).isEqualTo(100);
    }

    @Test
    void groupResponse_fromGroupMapsNullMaxMembersAsUnlimited() {
        User creator = new User("alice", "secret", "Alice");
        Group group = new Group("Study Group", creator);

        GroupResponse response = GroupResponse.fromGroup(group);

        assertThat(response.getMaxMembers()).isNull();
    }

    private static CreateGroupRequest createGroupRequest(Integer maxMembers) {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setName("Study Group");
        request.setParticipantIds(List.of(2L));
        request.setMaxMembers(maxMembers);
        return request;
    }
}
