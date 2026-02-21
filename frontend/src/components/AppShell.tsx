import { PropsWithChildren } from 'react';

type AppShellProps = PropsWithChildren<{
    title: string;
    showTitle?: boolean;
}>;

export default function AppShell({ title, showTitle = true, children }: AppShellProps) {
    return (
        <div className="app-shell">
            {showTitle ? (
                <header className="app-header">
                    <h1>{title}</h1>
                </header>
            ) : null}
            <main className="app-main">{children}</main>
        </div>
    );
}
