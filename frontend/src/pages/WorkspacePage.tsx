import AppShell from '../components/AppShell';

export default function WorkspacePage() {
  return (
    <AppShell title="badgermole Workspace">
      <div className="workspace-grid">
        <aside className="panel sidebar">
          <h2>Connections</h2>
          <ul>
            <li>PostgreSQL</li>
            <li>MySQL</li>
            <li>MariaDB</li>
            <li>StarRocks</li>
            <li>Trino</li>
            <li>Vertica</li>
          </ul>
        </aside>

        <section className="panel editor">
          <h2>SQL Editor (Monaco placeholder)</h2>
          <pre>SELECT * FROM system.healthcheck;</pre>
          <div className="row">
            <button type="button">Run</button>
            <button type="button">Run Selection</button>
            <button type="button">Cancel</button>
            <button type="button">Export CSV</button>
          </div>
        </section>

        <section className="panel results">
          <h2>Results Grid</h2>
          <p>Server-side pagination and virtualization will be implemented in Milestones 6-7.</p>
        </section>
      </div>
    </AppShell>
  );
}
