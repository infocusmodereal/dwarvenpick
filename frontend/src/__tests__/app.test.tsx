import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, vi } from 'vitest';
import App from '../App';

vi.mock('@monaco-editor/react', () => ({
    loader: {
        config: vi.fn()
    },
    default: ({ value, onChange }: { value?: string; onChange?: (value: string) => void }) => (
        <textarea
            data-testid="mock-monaco-editor"
            value={value ?? ''}
            onChange={(event) => onChange?.(event.target.value)}
        />
    )
}));

const resolveRequestUrl = (input: RequestInfo | URL): string => {
    if (typeof input === 'string') {
        return input;
    }

    if (input instanceof URL) {
        return input.toString();
    }

    if (input instanceof Request) {
        return input.url;
    }

    return String(input);
};

describe('App shell', () => {
    beforeEach(() => {
        globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = resolveRequestUrl(input);

            if (url.endsWith('/api/auth/me')) {
                return new Response(
                    JSON.stringify({
                        username: 'analyst',
                        displayName: 'Analyst User',
                        roles: ['USER'],
                        groups: []
                    }),
                    {
                        status: 200,
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    }
                );
            }

            if (url.endsWith('/api/datasources')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/auth/methods')) {
                return new Response(JSON.stringify({ methods: ['local', 'ldap'] }), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            return new Response('{}', { status: 200 });
        }) as unknown as typeof fetch;
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('renders workspace route', async () => {
        render(
            <MemoryRouter initialEntries={['/workspace']}>
                <App />
            </MemoryRouter>
        );

        expect(screen.getByText(/^dwarvenpick$/i)).toBeInTheDocument();
        expect((await screen.findAllByText(/^workbench$/i)).length).toBeGreaterThan(0);
        expect(screen.queryByRole('heading', { name: /query history/i })).not.toBeInTheDocument();
    });

    it('renders login route', async () => {
        render(
            <MemoryRouter initialEntries={['/login']}>
                <App />
            </MemoryRouter>
        );

        expect(screen.queryByText(/dwarvenpick login/i)).not.toBeInTheDocument();
        expect(screen.queryByRole('heading', { name: /welcome back/i })).not.toBeInTheDocument();
        expect(await screen.findByLabelText(/username/i)).toBeInTheDocument();
        expect(await screen.findByRole('button', { name: /sign in/i })).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /local/i })).not.toBeInTheDocument();
    });

    it('renders datasource management panel for system admin users', async () => {
        globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = resolveRequestUrl(input);

            if (url.endsWith('/api/auth/me')) {
                return new Response(
                    JSON.stringify({
                        username: 'admin',
                        displayName: 'Platform Admin',
                        roles: ['SYSTEM_ADMIN', 'USER'],
                        groups: []
                    }),
                    {
                        status: 200,
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    }
                );
            }

            if (url.endsWith('/api/datasources')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/auth/methods')) {
                return new Response(JSON.stringify({ methods: ['local', 'ldap'] }), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/admin/groups')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/admin/datasources')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/admin/datasource-access')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/admin/datasource-management')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/admin/drivers')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            if (url.endsWith('/api/auth/admin/users')) {
                return new Response(JSON.stringify([]), {
                    status: 200,
                    headers: {
                        'Content-Type': 'application/json'
                    }
                });
            }

            return new Response('{}', { status: 200 });
        }) as unknown as typeof fetch;

        render(
            <MemoryRouter initialEntries={['/workspace']}>
                <App />
            </MemoryRouter>
        );

        expect((await screen.findAllByText(/^governance$/i)).length).toBeGreaterThan(0);
    });
});
