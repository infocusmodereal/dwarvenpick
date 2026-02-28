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
- Use **Explain** to request a query plan where supported.
- Save a query as a snippet from the editor.

## Results

- Results are shown as a table with paging controls.
- You can export the current result set to CSV (if your access rules allow export).
- Drag the horizontal handle above Results to resize the results panel.

## Explorer

- The Explorer shows databases/schemas/tables/columns for the active connection.
- Use it to navigate metadata and help author queries.

## Query History

- Filter by connection/status/time range and sort by newest/oldest.
- Paginate results and export the current page as CSV.

## Audit Events (SYSTEM_ADMIN)

- Filter audit events by action/actor/outcome/time range and sort by newest/oldest.
- Paginate results and export the current page as CSV.

## System Health (SYSTEM_ADMIN)

- Select a connection (and credential profile) to run engine-specific health checks.
- If the selected credentials do not have the required privileges, the page shows an explicit message.
