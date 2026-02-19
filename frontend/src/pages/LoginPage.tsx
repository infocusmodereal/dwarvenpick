import AppShell from '../components/AppShell';

export default function LoginPage() {
    return (
        <AppShell title="badgermole Login">
            <section className="panel">
                <h2>Authentication</h2>
                <p>Skeleton screen for Local and LDAP login flows.</p>
                <div className="row">
                    <button type="button">Login (Local)</button>
                    <button type="button">Login (LDAP)</button>
                </div>
            </section>
        </AppShell>
    );
}
