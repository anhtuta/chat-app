# Group Roles And Permissions

## Intro

This feature adds role-based permissions to each chat group. Today, the backend only knows whether a user is a participant in a group; after this change, each participant will have a deterministic group role that controls moderation, membership management, group settings, and message actions.

The initial static roles are `LEADER`, `CO_LEADER`, `ELDER`, and `MEMBER`. The design should allow more static roles later without moving to user-defined custom roles.

## Functional Requirements

### Roles And Hierarchy

- `LEADER`: exactly one per group. The group creator becomes leader when the group is created.
- `CO_LEADER`: high-trust moderator role with almost the same permissions as leader, except leadership transfer and leader management.
- `ELDER`: trusted member role that can invite/add members and kick allowed targets.
- `MEMBER`: default role for newly joined users.

Role hierarchy:

| Role        | Rank | Notes                                                                      |
| ----------- | ---- | -------------------------------------------------------------------------- |
| `LEADER`    | 1    | Highest role. Exactly one active leader per group.                         |
| `CO_LEADER` | 2    | Can manage users at the same or lower rank, but cannot manage the leader.  |
| `ELDER`     | 3    | Can add members and kick users at the same or lower rank if policy allows. |
| `MEMBER`    | 4    | Default role. Can send messages and manage own messages.                   |

Lower rank number means higher privilege. For future static roles, the backend should define the rank and permissions in code so behavior remains deterministic.

### Permission Matrix

| Action                                      | Leader                | Co-Leader      | Elder          | Member         |
| ------------------------------------------- | --------------------- | -------------- | -------------- | -------------- |
| Read group messages                         | Yes                   | Yes            | Yes            | Yes            |
| Send group messages                         | Yes                   | Yes            | Yes            | Yes            |
| Edit own text message                       | Yes                   | Yes            | Yes            | Yes            |
| Delete own text or media message            | Yes                   | Yes            | Yes            | Yes            |
| Edit another user's text message            | Yes                   | Yes            | No             | No             |
| Edit media message                          | No                    | No             | No             | No             |
| Delete another user's text or media message | Yes                   | Yes            | No             | No             |
| Create/share group join link                | Yes                   | Yes            | Yes            | No             |
| Self-join from valid link                   | Yes, as member        | Yes, as member | Yes, as member | Yes, as member |
| Add members directly                        | Yes                   | Yes            | Yes            | No             |
| Kick members                                | Yes                   | Yes            | Yes            | No             |
| Ban members                                 | Yes                   | Yes            | No             | No             |
| Unban members                               | Yes                   | Yes            | No             | No             |
| Promote/demote roles                        | Yes                   | Yes            | No             | No             |
| Update group name/description               | Yes                   | Yes            | No             | No             |
| Transfer leadership                         | Yes                   | No             | No             | No             |
| Leave group                                 | Yes, with constraints | Yes            | Yes            | Yes            |

Target-role rules:

- A user can only kick, ban, promote, or demote another user with the same role rank or a lower role rank, except that no one can kick, ban, promote, or demote the leader.
- The leader can transfer leadership to any current group member.
- `CO_LEADER` cannot transfer leadership.
- A user cannot kick, ban, promote, or demote themselves. A leader stepping down must use the leadership-transfer flow.
- A banned user cannot rejoin the group until manually unbanned.
- A kicked user can rejoin if they receive or still have a valid join path and are not banned.

### Group Creation And Membership

- When a user creates a group, the creator is inserted into `group_participants` with role `LEADER`.
- Each group must have exactly one active leader.
- Initial invited participants and self-joined participants are inserted with role `MEMBER`.
- Users can self-join a group only through a valid join link.
- Only `LEADER`, `CO_LEADER`, and `ELDER` can create or send a join link.
- A join link must not allow banned users to rejoin.
- Bans are permanent until a privileged user manually unbans the banned user.

### Leadership And Account Lifecycle

- Only the current `LEADER` can transfer leadership.
- The leader can transfer leadership to any current group member.
- A leader cannot leave, delete their account, or become inactive unless they transfer leadership first.
- If the leader is the last member, the group should be archived instead of hard deleted.
- There is no automatic inactive-user process today.
- If automatic inactivity is added later, the fallback should promote the oldest eligible member to leader.

### Group Archive Policy

Use archive/soft-delete instead of hard delete when the last member leaves.

Recommended behavior:

- Keep the group row, participant history, messages, media references, and moderation audit records.
- Mark the group as archived with fields such as `archived_at`, `archived_by`, and `archive_reason`.
- Exclude archived groups from normal active group lists.
- Keep archived group history available only through explicit history/admin/audit flows if the product needs it.

This is better than hard delete because message history, media references, unread/read state, and system events remain consistent. It is also clearer than only "soft delete" because the group did exist and may still be useful for historical/audit reads.

### Message Editing And Deletion

- All roles can edit their own text messages.
- All roles can delete their own text or media messages.
- `LEADER` and `CO_LEADER` can edit another user's text messages.
- `LEADER` and `CO_LEADER` can delete another user's text or media messages.
- No role can edit media messages. Media messages can only be deleted.
- Message edits must save edit history.
- Message deletion should be soft delete so history, pagination, media references, latest-message behavior, and audit trails remain safe.
- If a privileged user edits someone else's message, the response should include enough metadata for the frontend to show who edited it. The editor's role can be inferred from `updated_by` when needed.

### System Events

Membership and group-management events should appear in chat history as system messages.

Events that should create system messages include:

- User joined the group.
- User left the group.
- User was kicked.
- User was banned.
- User was unbanned, if visible history is desired.
- User was promoted.
- User was demoted.
- Leadership was transferred.
- Group name was updated.
- Group description was updated.
- Group was archived.

System messages should be stored as structured events, not as pre-rendered human text such as `"User1 has been kicked out of the group by User2"`.

Recommended approach:

- Store a `messages` row with `message_type = SYSTEM`.
- Store a stable `SystemEventType` enum value in `messages.content`, not a pre-rendered sentence. The application can use that enum value to infer and render the final user-facing system text.
- Let the application/frontend infer localized display text from the event type and metadata.

### Backend Authorization

- Existing binary membership checks must become permission checks.
- Permission checks should be centralized in a backend service, not repeated ad hoc in controllers and interceptors.
- The authorization service must check both actor permissions and target-role rules.
- WebSocket send, WebSocket subscribe, media upload, group history, mark-read, member management, role management, group settings, and message moderation must all use the same authorization rules.

## Non-Functional Requirements

- **Consistency:** Leadership transfer, role updates, kicks, bans, and archive operations must run in transactions.
- **Race safety:** Exactly-one-leader must be enforced by the database and service logic, not only by application checks.
- **Auditability:** Role changes, membership changes, group updates, message edits, and message deletes must preserve enough metadata for history and investigation.
- **Backward compatibility:** Existing groups must be migrated safely. Existing creators become `LEADER`; other participants become `MEMBER`.
- **Determinism:** Roles are static and code-defined. Future static roles can be added by defining rank and permissions in code.
- **Security:** Banned users must be blocked from direct add, self-join, WebSocket access, media upload, and message history.
- **Frontend support:** Responses should include current user role and enough permissions/role data for role-aware UI gating.
- **Localization readiness:** System messages should be stored as structured events so text can be rendered by the application.

## Use Cases

- Create a group and make the creator the leader.
- Invite initial participants as members.
- Generate/share a join link as leader, co-leader, or elder.
- Self-join a group from a valid join link.
- Add a user directly as leader, co-leader, or elder.
- Kick a user with the same or lower role rank.
- Ban a user with the same or lower role rank and prevent future rejoin.
- Unban a user manually.
- Promote a member to elder or co-leader.
- Demote a co-leader or elder.
- Transfer leadership from the current leader to any member.
- Leave a group.
- Archive the group when the last member leaves.
- Update group name or description.
- Send text and media messages.
- Edit own text message.
- Delete own text or media message.
- Edit another user's text message as leader or co-leader.
- Delete another user's text or media message as leader or co-leader.
- Show structured system events in message history.
- Show role-aware member lists and controls in the frontend.

## Possible Solutions

### 1. How to represent roles and permissions?

#### 1.1. Add Role To `group_participants` And Centralize Permission Checks

- How it works
  - Add a `role` column to `group_participants`.
  - Backfill existing group creators as `LEADER`; backfill all other participants as `MEMBER`.
  - Add a Java enum such as `GroupRole` with explicit rank and permission methods.
  - Add a service such as `GroupAuthorizationService` that evaluates both permissions and target-role rules.
  - Replace direct `existsByGroupAndUser` authorization checks with permission-specific checks.
- Pros
  - Smallest schema change for the current backend.
  - Keeps each user's role scoped to a specific group.
  - Works naturally with the current `GroupParticipant` entity.
  - Supports future static roles by adding enum values, ranks, and permissions in code.
- Cons
  - Permission changes require deployment.
  - Requires careful migration and transaction handling to guarantee exactly one leader.
  - Direct repository membership checks must be replaced consistently to avoid bypasses.
- Recommendation for our problem: Yes

#### 1.2. Add Role And Permission Tables

- How it works
  - Add tables such as `group_roles`, `group_permissions`, and `group_role_permissions`.
  - Store role-to-permission mappings in the database.
  - Assign each participant a role ID.
  - Evaluate permissions by joining role and permission tables.
- Pros
  - More flexible if admins need custom roles or runtime permission changes later.
  - Permission matrix can be inspected or changed without code changes.
- Cons
  - Larger design than the current requirements need.
  - Requires admin UX and stronger validation around editable role definitions.
  - Increases query and caching complexity.
- Recommendation for our problem: No
- When I'd use it
  - Use this only if custom user-defined roles become a product requirement.

#### 1.3. Treat `groups.created_by` As Implicit Leader

- How it works
  - Keep creator in `groups.created_by`.
  - Assign non-leader roles in `group_participants`.
  - Infer leader permissions from `groups.created_by`.
- Pros
  - Avoids an initial creator-role backfill.
- Cons
  - Makes leadership transfer awkward because `created_by` stops meaning "creator".
  - Splits authorization state across `groups` and `group_participants`.
  - Makes the exactly-one-leader invariant less clear.
- Recommendation for our problem: No
- When I'd use it
  - Only for a system where leadership can never transfer.

### 2. How to store visible system events?

#### 2.1. Store Structured `SYSTEM` Messages

- How it works
  - Insert a `messages` row with `message_type = SYSTEM`.
  - Store structured metadata for the event.
  - Render display text in the application layer.
- Pros
  - System events naturally appear in chronological message history.
  - Keeps localization and wording flexible.
  - Avoids storing generated English sentences as source data.
  - Aligns with the existing `SYSTEM` message type reserved in the media-message design.
- Cons
  - Requires `MessageResponse` and frontend rendering changes.
  - Requires a schema decision for event metadata.
- Recommendation for our problem: Yes

#### 2.2. Store Rendered Text In `messages.content`

- How it works
  - Insert a `SYSTEM` message whose `content` is the final display sentence.
- Pros
  - Simple to render.
  - Minimal frontend logic.
- Cons
  - Hard to localize or reword later.
  - User names and role names become baked into historical text.
  - Harder to build filters/audit views from structured event data.
- Recommendation for our problem: No

### 3. How to remove a group when the last member leaves?

#### 3.1. Archive The Group

- How it works
  - Keep the group and messages.
  - Mark the group as archived.
  - Hide archived groups from normal group lists.
- Pros
  - Preserves message history and audit events.
  - Avoids broken references from messages, media, and edit history.
  - Allows future admin/history views.
- Cons
  - Requires active-vs-archived filtering in queries.
  - Requires clear behavior for archived group WebSocket topics and joins.
- Recommendation for our problem: Yes

#### 3.2. Hard Delete The Group

- How it works
  - Delete the group row and cascade or manually delete related rows.
- Pros
  - Removes old data aggressively.
- Cons
  - Conflicts with keeping messages.
  - Can break message history, media references, unread state, and audit trails.
- Recommendation for our problem: No

## High level Architecture/Design

### Component Diagram

```mermaid
flowchart TB
    GroupController --> GroupService
    GroupMembershipController --> GroupMembershipService
    MessageController --> MessageModerationService
    WebSocketController --> GroupAuthorizationService
    WebSocketSecurityChannelInterceptor --> GroupAuthorizationService
    MediaUploadSessionService --> GroupAuthorizationService
    GroupMembershipService --> GroupAuthorizationService
    MessageModerationService --> GroupAuthorizationService
    GroupAuthorizationService --> GroupParticipantRepository
    GroupMembershipService --> GroupBanRepository
    GroupMembershipService --> GroupJoinLinkRepository
    GroupMembershipService --> GroupParticipantRepository
    GroupMembershipService --> SystemMessageService
    GroupService --> SystemMessageService
    MessageModerationService --> MessageRepository
    MessageModerationService --> MessageEditHistoryRepository
    SystemMessageService --> MessageRepository
```

### Core entities/models

- `Group`: existing group metadata. Add group description and archive fields if needed:
  - `description`
  - `archived_at`
  - `archived_by`
  - `archive_reason`
- `GroupParticipant`: existing membership row. Add:
  - `role`
  - When a user leaves or is kicked from a group, delete the `group_participants` relationship row instead of preserving an inactive membership row.
- `GroupRole`: enum values `LEADER`, `CO_LEADER`, `ELDER`, `MEMBER`; includes rank and static permission mapping.
- `GroupPermission`: enum values such as `READ_MESSAGES`, `SEND_MESSAGES`, `CREATE_JOIN_LINK`, `ADD_MEMBERS`, `KICK_MEMBERS`, `BAN_MEMBERS`, `MANAGE_ROLES`, `MANAGE_GROUP_DETAILS`, `EDIT_ANY_TEXT_MESSAGE`, `DELETE_ANY_MESSAGE`, `TRANSFER_LEADERSHIP`.
- TODO since GroupRole and GroupPermission are static, we can store them in a static variable as cache and load them from the database during startup.
- `GroupBan`: records permanent bans:
  - `group_id`
  - `user_id`
  - `banned_by`
  - `reason`
  - `banned_at`
  - unique `(group_id, user_id)`
- `GroupJoinLink`: join links generated by privileged users:
  - `group_id`
  - `token_hash`
  - `created_by`
  - `created_at`
  - optional `expires_at`
  - optional `revoked_at`
- `Message`: add moderation/system fields as needed:
  - `updated_by`
  - `updated_at`
  - `deleted_by`
  - `deleted_at`
  - For `SYSTEM` messages, store the `SystemEventType` enum value in `content`.
- `MessageEditHistory`: records text edits:
  - `message_id`
  - `old_content`
  - `updated_by`
  - `updated_at`

Prefer `EnumType.STRING` for persisted enum values. Do not store role weights as integers in the database unless there is a strong reason; keep rank behavior in code.

### API Draft

Groups:

- `POST /api/groups`
  - Create group.
  - Creator becomes `LEADER`.
  - Accept optional `description`.
  - Initial participants become `MEMBER`.
- `GET /api/groups`
  - Return active groups for current user.
  - Include `currentUserRole` (omit `currentUserPermissions`; derive from role or load via details).
- `GET /api/groups/{groupId}`
  - Return group details for the current member.
  - Include `currentUserRole` and `currentUserPermissions`.
- `PATCH /api/groups/{groupId}`
  - Update group name and description.
  - Requires `MANAGE_GROUP_DETAILS`.
  - Creates a structured `SYSTEM` message.
- `GET /api/groups/{groupId}/members`
  - List members with roles.
  - Requires membership.

Join links:

- `POST /api/groups/{groupId}/join-links`
  - Create a join link.
  - Requires `CREATE_JOIN_LINK`.
- `POST /api/groups/join-links/{token}/join`
  - Self-join from a valid link.
  - Adds user as `MEMBER`.
  - Rejects banned users and archived groups.
- `DELETE /api/groups/{groupId}/join-links/{joinLinkId}`
  - Revoke a join link.
  - Requires `CREATE_JOIN_LINK` or stronger group-management permission.

Membership:

- `POST /api/groups/{groupId}/members`
  - Add a user directly as `MEMBER`.
  - Requires `ADD_MEMBERS`.
  - Rejects banned users and archived groups.
- `DELETE /api/groups/{groupId}/members/{userId}`
  - Kick a user.
  - Requires `KICK_MEMBERS`.
  - Requires actor to be allowed to manage the target role.
  - Creates a structured `SYSTEM` message.
- `POST /api/groups/{groupId}/bans`
  - Ban a user and delete their participant row if present.
  - Requires `BAN_MEMBERS`.
  - Requires actor to be allowed to manage the target role.
  - Creates a structured `SYSTEM` message.
- `DELETE /api/groups/{groupId}/bans/{userId}`
  - Manually unban a user.
  - Requires `BAN_MEMBERS`.
- `PATCH /api/groups/{groupId}/members/{userId}/role`
  - Promote or demote a participant.
  - Requires `MANAGE_ROLES`.
  - Requires actor to be allowed to manage the target role.
  - Creates a structured `SYSTEM` message.
- `POST /api/groups/{groupId}/leadership-transfer`
  - Transfer leadership from current leader to another participant.
  - Requires current user to be `LEADER`.
  - Creates a structured `SYSTEM` message.
- `DELETE /api/groups/{groupId}/members/me`
  - Leave group.
  - If current user is leader and not last member, reject with "transfer leadership first".
  - If current user is the last member, archive the group.
  - Creates a structured `SYSTEM` message.

Messages:

- `PATCH /api/messages/{messageId}`
  - Edit a text message.
  - Sender can edit own text message.
  - Leader/co-leader can edit another user's text message.
  - Reject media messages.
  - Save edit history.
- `DELETE /api/messages/{messageId}`
  - Soft-delete a text or media message.
  - Sender can delete own message.
  - Leader/co-leader can delete another user's message.
- `GET /api/messages/groups/{groupId}`
  - Include `SYSTEM` messages in chronological history.
  - Return structured system-event `content` for application-level rendering.

WebSocket:

- `/group.send`
  - Requires `SEND_MESSAGES`.
- `/topic/group.{groupId}`
  - Subscribe requires `READ_MESSAGES`.
- `/topic/user.{username}.group-updates`
  - Subscribe must validate authenticated username matches the topic username.
- Group membership, role, and system-message events should be published so online clients update without refresh.

### Backend Enforcement Points

Replace direct authorization uses of `existsByGroupAndUser` outside repositories with `GroupAuthorizationService`.

Known enforcement points:

- `MessageController.getGroupMessages`: require `READ_MESSAGES`.
- `WebSocketController.sendGroupMessage`: require `SEND_MESSAGES`.
- `WebSocketSecurityChannelInterceptor.validateSubscription`: require `READ_MESSAGES` for group topics.
- `MediaUploadSessionService.validateScopeAndMembership`: require `SEND_MESSAGES`.
- `GroupService.markGroupAsRead`: require membership/read access.
- `GroupSummaryUpdatePublisher`: ensure removed or banned users stop receiving personal updates.
- New join-link endpoints: require `CREATE_JOIN_LINK` for link creation and ban/archive checks for joining.
- New member-management endpoints: require action-specific permissions and target-role checks.
- New group update endpoints: require `MANAGE_GROUP_DETAILS`.
- New message edit/delete endpoints: require own-message permission or moderation permission.

## Recommendation

Recommendation path:

1. Phase 1: Add role, ban, archive, join-link, and system-event schema with safe backfill.
2. Phase 2: Add centralized group authorization service and replace existing membership checks.
3. Phase 3: Add member list, join-link, self-join, add member, kick, ban, unban, promote, demote, leave, and transfer-leadership APIs.
4. Phase 4: Add group details update APIs and response DTO changes for role-aware UI.
5. Phase 5: Add structured system messages for membership, role, and group-profile events.
6. Phase 6: Add text edit and text/media delete moderation APIs, edit history, soft delete, and response DTO updates.
7. Phase 7: Build group details/settings UI on top of `GET /api/groups`, `GET /api/groups/{groupId}`, and `PATCH /api/groups/{groupId}`.
8. Phase 8: Build member-list and role-visibility UI on top of `GET /api/groups/{groupId}/members` and the role/permission DTO fields from Phase 4.
9. Phase 9: Build membership-management UI for add, kick, ban, unban, promote, demote, transfer-leadership, and leave-group flows from the Phase 3 APIs.
10. Phase 10: Build join-link management and self-join UI on top of the Phase 3 join-link APIs.
11. Phase 11: Build message moderation UI for edit/delete on top of `PATCH /api/messages/{messageId}` and `DELETE /api/messages/{messageId}`.
12. Phase 12: Re-introduce real-time notifications only after the above UI exists and can be manually exercised.
13. Phase 13: Add integration/E2E tests for the role matrix, edge cases, UI flows, and realtime behavior.

## Implementation details

Phases 1, 2, 3, 4, 5, 6, and 7 have been implemented. Later phases are still draft-only.

Planned implementation is Solution 1: add role to `group_participants`, add `group_bans`, add join links, archive groups instead of hard deleting them, store structured system events as `SYSTEM` messages, and centralize permissions in a backend authorization service.

### Phase 1: Schema And Entity Changes

Status: Implemented.

What changed:

- Added Flyway migration `V8__add_group_roles_and_permissions_phase1.sql`.
- Added rollback migration `down/U8__drop_group_roles_and_permissions_phase1.sql`.
- Added `role varchar(32) not null default 'MEMBER'` to `group_participants`.
- Backfilled each existing group's creator participant row as `LEADER`.
- Backfilled all other existing participants as `MEMBER`.
- Updated group creation so new group creators are stored as `LEADER`.
- Added a partial unique index to enforce one leader per group:

```sql
CREATE UNIQUE INDEX ux_group_participants_one_leader
ON public.group_participants (group_id)
WHERE role = 'LEADER';
```

- Added `group_bans` with unique `(group_id, user_id)`.
- Added `group_join_links` for future self-join links.
- Added group `description`, `archived_at`, `archived_by`, and `archive_reason` fields.
- Added message edit/delete audit fields to `messages`.
- System messages now use `message_type = SYSTEM` and store the `SystemEventType` enum value in `messages.content`, without extra system-event columns.
- Added `message_edit_history`.
- Added Java enum `GroupRole`.
- Added JPA entities `GroupBan`, `GroupJoinLink`, and `MessageEditHistory`.
- Updated `Group`, `GroupParticipant`, and `Message` entities for the new columns.

Why it changed:

- Phase 1 creates the data foundation for later authorization, membership management, join links, group archive, message moderation, and structured system events.
- The creator-as-leader write path was included now so new groups created after the migration do not start without a leader.

API/contract/config impacts:

- No new public API is available yet.
- Existing group creation now writes the creator's participant role as `LEADER`.
- Existing responses do not yet expose role, archive, moderation, or system-event metadata.
- Flyway migration must be applied before running the backend with Hibernate schema validation.

Rollout, migration, and backward-compatibility notes:

- Existing group creators are backfilled as `LEADER`.
- Existing non-creator participants remain `MEMBER`.
- Existing groups are not archived by default.
- Existing messages keep null edit/delete/system-event metadata.
- Rollback removes the Phase 1 tables, indexes, and columns, including role data.

### Phase 2: Authorization Service

Status: Implemented.

What changed:

- Added `GroupRole` and `GroupPermission` enums.
- Added `GroupAuthorizationService`.
- Added `GroupBanRepository` so the authorization layer can reject banned users centrally.
- Encoded current static role permissions in code for `LEADER`, `CO_LEADER`, `ELDER`, and `MEMBER`.
- Implemented these authorization methods:
  - `requireMember(user, groupId)`
  - `requirePermission(user, groupId, permission)`
  - `requireCanManageTarget(actor, groupId, target, permission)`
  - `requireCanEditMessage(user, message)`
  - `requireCanDeleteMessage(user, message)`
  - `requireNotBanned(user, groupId)`
  - `requireUserTopicAccess(user, topicUsername)`
- Replaced direct membership checks with `GroupAuthorizationService` in:
  - `MessageController`
  - `WebSocketController`
  - `WebSocketSecurityChannelInterceptor`
  - `MediaUploadSessionService`
  - `GroupService.markGroupAsRead`
- Added unit tests for `GroupAuthorizationService`.

Why it changed:

- Authorization rules now live in one place instead of being duplicated across controllers, services, and WebSocket code.
- This reduces drift and creates a single service that later phases can reuse for join links, member management, role changes, and moderation APIs.

API/contract/config impacts:

- No new public API endpoints were added in Phase 2.
- Existing read/send/upload/mark-read behavior now goes through permission checks rather than raw membership checks.
- Personal topic subscriptions `/topic/user.{username}.group-updates` are now validated against the authenticated username.
- Protected group access now resolves membership directly from `group_participants`, **so invalid `groupId` and non-membership are treated the same** unless a later feature explicitly needs a separate existence check.

Rollout, migration, and backward-compatibility notes:

- No schema changes were needed for Phase 2 beyond the Phase 1 schema foundation.
- Current permission behavior for existing features remains effectively the same because all current roles can read/send, while higher-privilege permissions are now encoded for future phases.

### Phase 3: Membership Management

Status: Implemented.

What changed:

- Added request/response DTOs for member, role, ban, join-link, and leadership-transfer APIs.
- Added `GroupJoinLinkRepository`.
- Expanded `GroupParticipantRepository` with group member list, participant lookup, and participant count queries.
- Added `GroupMembershipService`.
- Added `GroupMembershipController` as the single controller for member management and join-link operations.
- Implemented:
  - member list
  - direct add member
  - join-link creation
  - self-join by token
  - join-link revocation
  - kick member
  - ban member
  - unban member
  - promote/demote member role
  - leave group
  - leadership transfer
- Rejects banned users in direct add and self-join flows.
- Rejects membership changes for archived groups.
- Archives a group when the last member leaves.
- Deletes the `group_participants` relationship when a user leaves, is kicked, or is banned.
- Ensures transfer leadership updates the old leader to `MEMBER` before assigning `LEADER` to the new leader.
- Ensures kick, ban, promote, and demote checks compare actor and target role ranks.

Why it changed:

- This phase exposes the role and membership lifecycle APIs that the frontend needs for group moderation.
- `GroupMembershipController` owns join-link endpoints too, so the API surface stays grouped around membership instead of splitting into a separate join controller.

API/contract/config impacts:

- Added `GET /api/groups/{groupId}/members`.
- Added `POST /api/groups/{groupId}/members`.
- Added `DELETE /api/groups/{groupId}/members/{userId}`.
- Added `DELETE /api/groups/{groupId}/members/me`.
- Added `PATCH /api/groups/{groupId}/members/{userId}/role`.
- Added `POST /api/groups/{groupId}/bans`.
- Added `DELETE /api/groups/{groupId}/bans/{userId}`.
- Added `POST /api/groups/{groupId}/leadership-transfer`.
- Added `POST /api/groups/{groupId}/join-links`.
- Added `DELETE /api/groups/{groupId}/join-links/{joinLinkId}`.
- Added `POST /api/groups/join-links/{token}/join`.
- Join links return the raw token only when created; only `token_hash` is stored.

Rollout, migration, and backward-compatibility notes:

- No additional schema migration was needed beyond Phase 1.
- Phase 3 does not emit `SYSTEM` messages yet. Visible membership and role-change system messages are still planned for Phase 5.

### Phase 4: Group Details And DTOs

Status: Implemented.

What changed:

- Extended `CreateGroupRequest` so new groups can include an optional `description`.
- Added `UpdateGroupRequest`.
- Extended `GroupResponse` with:
  - `description`
  - `currentUserRole`
  - `currentUserPermissions`
- Added `GET /api/groups/{groupId}` for member-scoped group details.
- Added `PATCH /api/groups/{groupId}` for name/description updates.
- Updated group creation, group list, and group detail responses to include role-aware frontend gating data.
- Filtered archived groups out of the normal `GET /api/groups` list query.
- Kept member list responses returning role, joined time, and user summary fields introduced in Phase 3.
- Slimmed `PATCH /api/groups/{groupId}` so it returns group metadata only (no `unreadCount`, `currentUserRole`, or `currentUserPermissions` refresh); those values are unchanged by name/description edits and the client keeps its existing copies.
- Slimmed `GET /api/groups` so list items include `currentUserRole` and unread counts but omit `currentUserPermissions` (permissions are role-derived and returned by `GET /api/groups/{groupId}` when needed).

Why it changed:

- The frontend needs the current user’s role and permissions on each group payload so it can show or hide moderation and settings controls without extra round trips.
- Group metadata now needs a dedicated read/update path beyond the basic list response.
- Archived groups should not appear in the normal active group list.
- Recomputing unread/role/permission fields on every group-profile edit is redundant work for fields this mutation does not change.
- Expanding the full permission list on every sidebar group is redundant with `currentUserRole` and wastes work for a surface that does not need permission chips.

API/contract/config impacts:

- `POST /api/groups` now accepts optional `description`.
- Added `GET /api/groups/{groupId}`.
- Added `PATCH /api/groups/{groupId}`.
- `GroupResponse` now includes `description`, `currentUserRole`, and `currentUserPermissions`.
- `GET /api/groups` now returns only active (non-archived) groups.
- `GET /api/groups` returns `currentUserRole` but leaves `currentUserPermissions` empty; use `GET /api/groups/{groupId}` for the permission list.
- `PATCH /api/groups/{groupId}` still uses `GroupResponse`, but callers should treat role/permission/unread fields on that response as unset and preserve prior client state.

Rollout, migration, and backward-compatibility notes:

- No new schema migration was needed because `groups.description` and archive fields were already added in Phase 1.
- Existing clients that ignore new JSON fields remain compatible.
- Clients that blindly replace local group state from the PATCH body should stop overwriting unread/role/permission fields from that response.

### Phase 5: Structured System Messages

Status: Implemented.

What changed:

- Added `SystemMessageService`.
- Persisted `SYSTEM` messages for:
  - join
  - leave
  - kick
  - ban
  - unban
  - promote
  - demote
  - leadership transfer
  - group name update
  - group description update
  - group archive
- Kept `messages.content` as the stable `SystemEventType` value.
- Exposed system-message metadata in `MessageResponse` / `MessageResponseMapper` so the frontend can render inferred text.
- Updated the frontend chat message model/rendering to use structured system-event metadata when available.

Why it changed:

- Membership and group-profile actions should appear in chat history as durable events.
- The frontend needs structured event metadata instead of pre-rendered English sentences in persisted message rows.

API/contract/config impacts:

- Group message history now includes persisted `SYSTEM` messages for membership and group-profile events.
- `MessageResponse` now includes `systemEventType` and `systemEventActor` for structured system-event rendering.
- Group latest-message summaries now use derived system previews like `Member joined` or `Group archived`.

Rollout, migration, and backward-compatibility notes:

- No new schema migration was needed because Phase 1 already reserved `SYSTEM` messages and audit fields.
- Legacy transient `[SYSTEM] ...` WebSocket notifications still work; the frontend now prefers structured metadata when present.

### Phase 6: Message Moderation

Status: Implemented.

What changed:

- Added `PATCH /api/messages/{messageId}` for text-message edits.
- Added `DELETE /api/messages/{messageId}` for soft delete.
- Added `MessageModerationService`.
- Added `MessageEditHistoryRepository`.
- Persisted a `MessageEditHistory` row for every successful text edit.
- Extended `MessageResponse` / `MessageResponseMapper` with:
  - `updatedBy`
  - `updatedAt`
  - `deletedBy`
  - `deletedAt`
- Hid deleted message content and attachments from API responses while keeping the row for history/audit purposes.
- Refreshed group latest-message summaries after edits/deletes so sidebar state stays coherent.
- Updated frontend chat message rendering to show deleted placeholders and edited markers.

Why it changed:

- Message edits and deletes need permission-aware moderation behavior without losing chronology or audit history.
- Soft delete keeps pagination, ordering, and message history stable while preventing deleted content/media from being re-served to clients.

API/contract/config impacts:

- Added `PATCH /api/messages/{messageId}`.
- Added `DELETE /api/messages/{messageId}`.
- `MessageResponse` now includes edit/delete metadata.
- Deleted messages now return null content and no attachments, plus `deletedBy` / `deletedAt`.

Rollout, migration, and backward-compatibility notes:

- No new schema migration was needed because Phase 1 already added message moderation audit fields and `message_edit_history`.
- Older clients that ignore the new response fields remain compatible, but they will not show edit/delete state unless updated.

### Phase 7: Group Details And Settings UI

Status: Implemented.

What changed:

- Added a small group details/settings dialog for the currently selected group chat.
- The dialog now shows:
  - group name
  - group description
  - current user role
  - current user permissions
- Added frontend API helpers for:
  - `GET /api/groups`
  - `GET /api/groups/{groupId}`
  - `PATCH /api/groups/{groupId}`
- Wired role-aware edit affordances from `currentUserRole` / `currentUserPermissions`.
- Added name/description edit UI for users who have `MANAGE_GROUP_DETAILS`.
- Kept this phase intentionally small: it does not include member actions, join links, or moderation controls yet.

Why it changed:

- Phase 4 already exposed the data and API contracts needed for a basic settings UI.
- The product needed a real UI entry point to manually exercise role-aware group metadata before moving on to member-management, moderation, or realtime work.

API/contract/config impacts:

- The frontend now consumes `GET /api/groups/{groupId}` directly for the selected group's details dialog.
- The frontend now consumes `PATCH /api/groups/{groupId}` for group name/description edits.
- No backend schema or contract changes were required beyond the Phase 4 DTO/API work that was already implemented.

Rollout, migration, and backward-compatibility notes:

- No schema migration was needed.
- Older clients remain compatible; they just will not show the new group-details/settings surface until updated.

### Phase 8: Member List And Role Visibility UI

Status: Planned.

What should change:

- Build the member roster UI backed by `GET /api/groups/{groupId}/members`.
- Show each member's:
  - display name
  - username
  - joined time
  - current role
- Make role-based action visibility explicit in the UI:
  - who can see moderation controls
  - who can see settings controls
  - who is protected from management because they are leader
- Keep this phase read-heavy first; it should help us verify the role matrix visually before wiring all mutation actions.

Why this phase exists:

- The user should be able to inspect roles and membership state before trying add/kick/ban/promote flows.
- This phase provides a low-risk UI slice to validate the Phase 3 member DTOs and Phase 4 permission data.

### Phase 9: Membership Management UI

TODO review this phase first, do not let AI implement it now!

Status: Planned.

What should change:

- Add role-aware controls for the Phase 3 membership APIs:
  - `POST /api/groups/{groupId}/members`
  - `DELETE /api/groups/{groupId}/members/{userId}`
  - `POST /api/groups/{groupId}/bans`
  - `DELETE /api/groups/{groupId}/bans/{userId}`
  - `PATCH /api/groups/{groupId}/members/{userId}/role`
  - `POST /api/groups/{groupId}/leadership-transfer`
  - `DELETE /api/groups/{groupId}/members/me`
- Add confirmation/error UX for destructive actions:
  - kick
  - ban
  - leadership transfer
  - leave group
- Make sure UI gating follows the backend permission matrix instead of duplicating custom frontend-only rules.

Why this phase exists:

- Phase 3 already built the core group-management APIs, but they are not product-usable until the UI can call them.
- Splitting these actions from the member-list phase keeps the rollout small and easier to debug.

### Phase 10: Join Link Management And Self-Join UI

TODO review this phase first, do not let AI implement it now!

Status: Planned.

What should change:

- Add UI for:
  - `POST /api/groups/{groupId}/join-links`
  - `DELETE /api/groups/{groupId}/join-links/{joinLinkId}`
  - `POST /api/groups/join-links/{token}/join`
- Support common product actions:
  - create link
  - copy/share token
  - revoke link
  - join from a token or join screen, if the product keeps that entry point
- Show expiry/revocation state clearly if the UI exposes join-link history.

Why this phase exists:

- Join links are a distinct user journey from direct member management and deserve their own small UI phase.
- It also gives us a focused place to verify banned/archive join rejection behavior before realtime is added.

### Phase 11: Message Moderation UI

TODO review this phase first, do not let AI implement it now!

Status: Planned.

What should change:

- Add message action UI backed by:
  - `PATCH /api/messages/{messageId}`
  - `DELETE /api/messages/{messageId}`
- Support:
  - edit own text message
  - delete own text or media message
  - leader/co-leader moderation actions for allowed target messages
- Add inline edit UX with save/cancel/error feedback.
- After successful moderation, refresh or locally update the current message list so the user can immediately see:
  - edited state
  - deleted placeholder
  - latest-message summary changes

Why this phase exists:

- Phase 6 already built the backend moderation behavior, but there is no UI to trigger it yet.
- This should be implemented before realtime so we can manually test moderation flows in the normal product UI first.

### Phase 12: Real-Time Notifications

TODO review this phase first, do not let AI implement it now!

Status: Planned.

What should change:

- Re-introduce WebSocket updates for:
  - membership changes
  - role changes
  - group-profile changes
  - visible system-message changes
- Ensure kicked or banned users stop receiving group summary updates.
- Validate personal WebSocket topic subscription by authenticated username.
- Ensure archived groups no longer accept sends, joins, or subscriptions.
- Keep the implementation intentionally smaller than the reverted attempt: only add the realtime paths needed by the finished UI flows from Phases 7-11.

Why this phase exists:

- Realtime is much easier to validate once each underlying action already has a working UI path.
- This reduces the risk of building a long/complex realtime layer before we even know how the product wants each action to feel.

### Phase 13: Tests

Status: Planned.

What should change:

- Test group creator becomes leader.
- Test new participants and self-joined users become members.
- Test exactly-one-leader invariant and leadership transfer to any member.
- Test co-leader cannot transfer leadership or manage leader.
- Test role matrix for join links, add, kick, ban, unban, promote, demote, group update, send message, edit message, and delete message.
- Test actor can manage only same-rank or lower-rank target roles, excluding leader.
- Test kicked users can rejoin when invited or using a valid join link.
- Test banned users cannot rejoin or be added until manually unbanned.
- Test elder can create join links, add members, and kick allowed targets.
- Test leader cannot leave/delete account without transfer unless last member.
- Test last-member leave archives the group and preserves messages.
- Test structured system messages are returned in group history.
- Test WebSocket subscribe/send checks.
- Test media upload respects `SEND_MESSAGES`.
- Add UI/E2E coverage for the new frontend phases:
  - group settings edit flow
  - member-list visibility by role
  - membership-management actions
  - join-link creation/revocation/join
  - message edit/delete actions
  - realtime sidebar/chat updates after Phase 12 lands

## Future Higher-Scale Path

- Add a dedicated audit/event table for all membership and moderation actions if system messages are not enough for admin investigation.
- Add more static roles by extending `GroupRole` rank and permission mappings in code.
- Move from hard-coded roles to configurable role-permission tables only if custom roles become a product requirement.
- Cache group roles/permissions for hot WebSocket paths if database checks become too expensive.
- Revisit per-user group summary fan-out from `11_GROUP_SUMMARY_UPDATE_FANOUT_SCALING.md` before supporting very large groups.
