# Backfill

Backfill is the process of adding data to a database that was not present in the original data. It is used to ensure that the database is consistent and up to date.

Example:

- Add `role VARCHAR(32) not null default 'MEMBER'` to `group_participants`.
- Backfill each group's creator participant row as `LEADER`.
- Backfill all other participants as `MEMBER`.

In this specific context, backfill means writing a SQL script to retroactively update existing rows in `group_participants` database table, ensuring older records conform to a newly introduced business rule.

# Retroactive

Retroactive data changes mean updating records that were created before a new feature was built.

With the same example in [](#backfill):

- The new code doesn't know what to do with the users from the past.
- Without a retroactive update, old data is broken.
- The Flyway script goes back in time, looks at the historical records created months ago, and forces them to fit the new rule by assigning `LEADER` or `MEMBER` status.
