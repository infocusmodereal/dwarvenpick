---
title: Workbench
nav_order: 3
---

# Workbench

The Workbench is the main SQL editor and results viewer.

## Theme

- Use the user menu in the left rail to toggle between Light mode and Dark mode.
- The main navigation rail opens collapsed by default; use the rail toggle to expand it when you want labels.

## Editor

- Create tabs for multiple queries.
- Run selection or run the full query.
- Use **Save to Scripts** to open the current editor contents as a draft in the Resource Manager with the active connection pre-selected.

### Autocomplete

The editor provides basic autocomplete:

- SQL keywords
- Schemas/tables/columns discovered by **Explorer** for the active connection
- StarRocks external catalog objects as fully qualified `catalog.database.table` references

Use `Ctrl+Space` / `Cmd+Space` to open suggestions.

### Validate

Use **Validate** to check SQL syntax and planning without running the query. Validation is engine-aware and uses
`EXPLAIN` under the hood.

Notes:

- Validation may still require privileges to read metadata.
- Validation refuses `EXPLAIN ANALYZE` and `EXPLAIN` of write statements because those plan modes may execute work or
  validate a write path.
- Some engines can return line/column information; when available, the editor shows inline markers.

### Explain vs Analyze

- **Explain** requests a query plan where supported.
- **Analyze** is a deeper plan mode. Some engines may execute the query (for example PostgreSQL `EXPLAIN ANALYZE` and
  Trino `EXPLAIN ANALYZE`). Use it deliberately on large tables.

### Run Script (multi-statement)

Use **Run > Run script** for semicolon-delimited SQL scripts. The backend splits statements and executes them one-by-one.

Options:

- **Stop on error**: stop at the first failing statement (recommended for safe operations).
- **Transaction mode**:
    - `AUTOCOMMIT`: each statement commits independently.
    - `TRANSACTION`: attempt to run the whole script in one transaction and roll back on failure (where supported).

Read-only access rules apply to **every** statement in a script.

## Scripts

- The **Scripts** section is a server-backed Resource Manager for SQL files.
- Scripts and their version history are stored in the application database.
- Save scripts into **Private** or **Shared** spaces.
- Shared scripts require a group and can optionally allow group members to update content.
- Organize scripts with folder paths, tags, and a folder tree view.
- Associate a script with a fixed connection when you want it to open against a specific target.
- Open or run a saved script directly into the Workbench.
- Script-backed tabs autosave content changes back to the server.
- Edit mode includes version history so owners can review and restore previous saved revisions.

## Execution and results

The toolbar below the SQL editor contains **Run**, **Format SQL**, **Options**,
**Query Tools**, and **Save**. Run executes the selected SQL, or the statement at
the cursor when there is no selection. Its dropdown also offers selection-only
execution and Run script. Use Ctrl/Cmd+Enter for the current statement/selection,
or Shift+Ctrl/Cmd+Enter for the complete script. During execution, Run becomes
Cancel. Script transaction and stop-on-error settings remain in Options; access
policy row/runtime limits remain enforced by the server.

Results, Query Plan, and Stats share a compact execution summary. Query Plan
shows the engine's textual/tabular plan from an explicit Explain or Analyze
operation; opening the tab never submits SQL. Analyze is marked when it executes
the SQL (PostgreSQL and Trino). Unsupported connectors show an empty state.
Stats displays reported counts, execution duration, limits, application queue time,
and timestamps. The execution API does not currently expose engine CPU, scan,
shuffle, or peak-memory metrics, so the UI does not synthesize those values.

Search and sorting operate **only on the loaded page** and never rerun SQL.
Rows per page controls backend result pagination, independently of the query row
limit. Columns can be hidden/restored for the current result; the native table
does not support pinning, reordering, or manual widths. Density changes row
height/padding and is remembered locally. Expand hides the editor within the
Workbench; Restore or Escape brings it back without browser fullscreen.

Select cells by clicking or focusing and pressing Enter/Space; select rows using
the row checkboxes. The result action menu offers copying selected cells/rows or
all loaded rows, governed CSV export of all materialized result rows in original
order, and JSON export of the loaded page. JSON requires the executed credential profile to permit export and the loaded page to fit within the reported export row cap. JSON uses a columns/rows envelope to
preserve duplicate column names and nulls. Local search, sorting, and hidden
columns do not change export contents. The same menu clears sorting/search,
resets the table view, or clears results without changing SQL or history.

Zero-row queries remain successful empty result sets. JDBC update counts are
marked explicitly in result metadata so DDL/DML displays affected rows without
an artificial table; an ordinary SELECT column named `affected_rows` remains a
table column. Older saved results without that marker retain their table view.

## Explorer

- The Explorer shows databases/schemas/tables/columns for the active connection.
- StarRocks external catalog databases are shown as `catalog.database`, so inserting a table reference produces `catalog.database.table`.
- Use it to navigate metadata and help author queries.
- Drag the vertical handle between Explorer and the editor to resize Explorer horizontally. Double-click the handle to reset.
- Use **Search** to filter schemas/tables/columns (DataGrip-style).
- Use the **Inspect** icon on a table/view to open the Object Inspector:
    - DDL / `SHOW CREATE` (engine-specific)
    - Indexes, constraints, partitions (when available)
    - Size & basic statistics (when available)
    - If the selected credentials do not have the required privileges, the inspector shows an explicit message per section.

## Query History

- Filter by connection/status/time range and sort by newest/oldest.
- Query history, active query status, and buffered result pages are stored in the application database, so they survive
  backend restarts, redeployments, and routing to a different backend replica.
- When configured, governed write-capable requests store the submitted justification in status, history, and audit.
- Paginate through persisted history and export the current page as CSV.
- History retention is controlled by `dwarvenpick.query.history-retention-days`.
- Optional SQL text redaction is controlled by `dwarvenpick.query.query-text-redaction-days`.

## Audit Events (SYSTEM_ADMIN)

- Filter audit events by action/actor/outcome/time range and sort by newest/oldest.
- Paginate results and export the current page as CSV.
- Audit event retention is controlled by `dwarvenpick.query.audit-retention-days`.

## System Health (SYSTEM_ADMIN)

- Select a connection and a **sysadmin credential profile** to run engine-specific health checks.
- Only credential profiles marked as **sysadmin** are shown in the picker.
- If no connections have a sysadmin credential profile, the page shows an explicit message.
- If the selected credentials do not have the required privileges, the page shows an explicit message.

### Control Plane

The System Health page also includes a lightweight control plane for the selected connection:

- Real-time view (polling) of queued/running queries and connection pool saturation across backend replicas.
- Latency summary (windowed) and latest error samples.
- Admin actions: pause/resume the connection, cancel/kill queued/running queries (optionally filtered by actor),
  and export queued/running queries as CSV.
- Paused connection state is stored in the application database and survives backend restarts.

Explain and Analyze keep their execution state and plan pagination separate from the query result. Requesting or refreshing a plan preserves the Results rows, current page, sorting, search, column visibility, and query metrics. Plan errors appear only in Query Plan. Running a new query clears the previous plan. Stats separates execution metrics from submitted/completed timestamps. The result toolbar uses Show columns and a density selector without a repeated visible label.
