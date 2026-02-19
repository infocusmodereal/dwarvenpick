import { PropsWithChildren } from 'react';

type AppShellProps = PropsWithChildren<{
    title: string;
}>;

export default function AppShell({ title, children }: AppShellProps) {
    return (
        <div className="app-shell">
            <header className="app-header">
                <h1>{title}</h1>
            </header>
            <main className="app-main">{children}</main>
        </div>
    );
}
