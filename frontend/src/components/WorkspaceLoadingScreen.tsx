import AppShell from './AppShell';

type WorkspaceLoadingScreenProps = {
    subtitle?: string;
};

export default function WorkspaceLoadingScreen({
    subtitle = 'Preparing your connections, explorer and SQL editor.'
}: WorkspaceLoadingScreenProps) {
    return (
        <AppShell title="dwarvenpick" showTitle={false} topNav={false}>
            <section className="panel login-card workspace-loading-card">
                <div className="login-brand workspace-loading-brand">
                    <div className="workspace-loading-visual" aria-hidden="true">
                        <span className="workspace-loading-ring" />
                        <span className="workspace-loading-ring" />
                        <img
                            src="/dwarvenpick-mark.svg"
                            alt=""
                            width={111}
                            height={111}
                            className="login-brand-mark workspace-loading-mark"
                        />
                    </div>
                    <strong>dwarvenpick</strong>
                </div>
                <div
                    className="workspace-loading-copy"
                    role="status"
                    aria-live="polite"
                    aria-busy="true"
                >
                    <p className="workspace-loading-title">Loading workspace</p>
                    <p className="workspace-loading-subtitle">{subtitle}</p>
                </div>
                <div className="workspace-loading-progress" aria-hidden="true">
                    <span />
                    <span />
                    <span />
                </div>
            </section>
        </AppShell>
    );
}
