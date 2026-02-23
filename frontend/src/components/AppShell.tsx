import { PropsWithChildren, useEffect, useRef, useState } from 'react';

type AppShellUser = {
    displayName: string;
    username: string;
    email?: string;
    onLogout: () => void;
};

type AppShellProps = PropsWithChildren<{
    title: string;
    showTitle?: boolean;
    user?: AppShellUser | null;
    topNav?: boolean;
    className?: string;
}>;

export default function AppShell({
    title,
    showTitle = true,
    user,
    topNav = true,
    className = '',
    children
}: AppShellProps) {
    const [menuOpen, setMenuOpen] = useState(false);
    const menuRef = useRef<HTMLDivElement | null>(null);

    useEffect(() => {
        const handleDocumentClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (!target || !menuRef.current?.contains(target)) {
                setMenuOpen(false);
            }
        };

        if (menuOpen) {
            document.addEventListener('mousedown', handleDocumentClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleDocumentClick);
        };
    }, [menuOpen]);

    return (
        <div className={className ? `app-shell ${className}` : 'app-shell'}>
            {topNav ? (
                <header className="top-nav">
                    <span className="top-nav-brand">{title}</span>
                    {user ? (
                        <div className="top-nav-user" ref={menuRef}>
                            <button
                                type="button"
                                className="top-nav-user-trigger"
                                onClick={() => setMenuOpen((open) => !open)}
                                aria-expanded={menuOpen}
                                aria-haspopup="menu"
                            >
                                <span className="top-nav-user-name">{user.displayName}</span>
                                <span className="top-nav-user-handle">@{user.username}</span>
                            </button>
                            {menuOpen ? (
                                <div className="top-nav-user-menu" role="menu">
                                    {user.email ? (
                                        <p className="top-nav-user-email">{user.email}</p>
                                    ) : null}
                                    <button
                                        type="button"
                                        className="top-nav-logout"
                                        role="menuitem"
                                        onClick={() => {
                                            setMenuOpen(false);
                                            user.onLogout();
                                        }}
                                    >
                                        Logout
                                    </button>
                                </div>
                            ) : null}
                        </div>
                    ) : null}
                </header>
            ) : null}
            {showTitle ? <header className="app-header">{title}</header> : null}
            <main className="app-main">{children}</main>
        </div>
    );
}
