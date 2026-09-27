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
