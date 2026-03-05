---
title: Workbench
nav_order: 3
---

# Workbench

The Workbench is the main SQL editor and results viewer.

## Theme

- Use the user menu in the left rail to toggle between Light mode and Dark mode.

## Editor

- Create tabs for multiple queries.
- Run selection or run the full query.
- Save a query as a snippet from the editor.

### Autocomplete

The editor provides basic autocomplete:

- SQL keywords
- Schemas/tables/columns discovered by **Explorer** for the active connection

Use `Ctrl+Space` / `Cmd+Space` to open suggestions.

### Validate

Use **Validate** to check SQL syntax and planning without running the query. Validation is engine-aware and uses
`EXPLAIN` under the hood.

Notes:

- Validation may still require privileges to read metadata.
- Some engines can return line/column information; when available, the editor shows inline markers.

### Explain vs Analyze

- **Explain** requests a query plan where supported.
- **Analyze** is a deeper plan mode. Some engines may execute the query (for example PostgreSQL `EXPLAIN ANALYZE` and
  Trino `EXPLAIN ANALYZE`). Use it deliberately on large tables.

### Run Script (multi-statement)

Use **Run Script** for semicolon-delimited SQL scripts. The backend splits statements and executes them one-by-one.

Options:

- **Stop on error**: stop at the first failing statement (recommended for safe operations).
- **Transaction mode**:
  - `AUTOCOMMIT`: each statement commits independently.
  - `TRANSACTION`: attempt to run the whole script in one transaction and roll back on failure (where supported).

Read-only access rules apply to **every** statement in a script.

## Results

- Results are shown as a table with paging controls.
- You can export the current result set to CSV (if your access rules allow export).
- Drag the horizontal handle above Results to resize the results panel.
- Script runs include a per-statement summary (succeeded/failed) to make it clear where a script stopped or failed.

## Explorer

- The Explorer shows databases/schemas/tables/columns for the active connection.
- Use it to navigate metadata and help author queries.
- Use **Search** to filter schemas/tables/columns (DataGrip-style).
- Use the **Inspect** icon on a table/view to open the Object Inspector:
  - DDL / `SHOW CREATE` (engine-specific)
  - Indexes, constraints, partitions (when available)
  - Size & basic statistics (when available)
  - If the selected credentials do not have the required privileges, the inspector shows an explicit message per section.

## Query History

- Filter by connection/status/time range and sort by newest/oldest.
- Paginate results and export the current page as CSV.

## Audit Events (SYSTEM_ADMIN)

- Filter audit events by action/actor/outcome/time range and sort by newest/oldest.
- Paginate results and export the current page as CSV.

## System Health (SYSTEM_ADMIN)

- Select a connection and a **sysadmin credential profile** to run engine-specific health checks.
- Only credential profiles marked as **sysadmin** are shown in the picker.
- If no connections have a sysadmin credential profile, the page shows an explicit message.
- If the selected credentials do not have the required privileges, the page shows an explicit message.

### Control Plane

The System Health page also includes a lightweight control plane for the selected connection:

- Real-time view (polling) of queued/running queries and connection pool saturation.
- Latency summary (windowed) and latest error samples.
- Admin actions: pause/resume the connection, cancel/kill queued/running queries (optionally filtered by actor),
  and export queued/running queries as CSV.
