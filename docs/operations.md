---
title: Operations
nav_order: 50
has_children: true
---

# Operations

Operator documentation for deploying and running `dwarvenpick`.


## Explorer metadata loads

The workspace requests `/api/datasources/{id}/schema-browser?stream=true`.
The response is JSON preceded by whitespace heartbeats, flushed every second.
On a detected HTTP disconnect, the server cancels the metadata task and evicts
its borrowed JDBC connection. This closes Trino's internal metadata statements
and cancels their coordinator queries. Keep proxy response buffering disabled
for this route; the response includes `X-Accel-Buffering: no` and `Cache-Control: no-store`.

`DWARVENPICK_SCHEMA_MAX_LOAD_SECONDS` (default `120`) bounds the streamed load,
including its queue wait, even if an intermediary delays disconnect detection.
Each backend admits at most four running metadata workers and sixteen queued
loads. A timeout, saturation, or load failure is returned as an `error` JSON
object because streaming has already committed the HTTP status. The workspace
displays that error. Authorization is checked before streaming begins.

The existing non-streaming JSON endpoint remains available for older clients;
it does not have disconnect-driven cancellation. This behavior applies to
Explorer loads, not independently submitted SQL executions.

## Identity and revision integrity upgrade

Apply V6 and V7 with application writers stopped, then deploy backend and frontend
together. V6 removes the redundant `rbac_group_members` table. The authorization
source, `app_user_groups`, is retained without unioning old administrative memberships,
which could otherwise restore revoked access. Keep a database backup before upgrading.

Preflight existing databases for orphaned user roles/memberships, credential profiles,
group access mappings and script versions, plus duplicate `(resource_id, revision)`
pairs. The new constraints intentionally reject inconsistent data; reconcile such
records before retrying the migration. Historical query and audit references are
retained independently of live accounts and connections. Restoring a backup is the
rollback path; the old backend must not run against the new membership schema.

V6 invalidates persisted sessions; everyone must sign in again. Before reopening
traffic on external-only deployments, bootstrap a verified administrator identity
using the offline database procedure in [Authentication](authentication.md#external-only-upgrade-bootstrap).
This does not require enabling local login or seeded credentials. Other external
users also need verified identity links.
Legacy account IDs are assigned once as `legacy:<username>`, while new accounts use
UUIDs. Both remain stable for the lifetime of the account.

Script update, content-save and restore endpoints now require `expectedRevision`.
Missing revisions return 428; stale revisions return 409 without changing the script
or adding a version. Reload and reconcile edits before retrying. Writes and their
versions commit together, with database row locking and unique revision numbers.
Old saved browser tabs without a revision must reopen the script before saving.

Runtime retention removes only terminal executions with no recent result access.
Stale owner recovery continues to cancel abandoned queries and rejects late result
writes. A database outage is surfaced as an error rather than result expiration.
An export whose results expire between pages fails explicitly; partial output must
not be treated as a complete export. This does not provide an unlimited export lease.
