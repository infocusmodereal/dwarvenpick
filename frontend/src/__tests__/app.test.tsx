import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, vi } from 'vitest';
import App from '../App';

describe('App shell', () => {
    beforeEach(() => {
        globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = typeof input === 'string' ? input : input.toString();

            if (url.endsWith('/api/auth/me')) {
                return new Response(
                    JSON.stringify({
                        username: 'analyst',
                        displayName: 'Analyst User',
                        roles: ['USER']
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

            return new Response('{}', { status: 200 });
        }) as unknown as typeof fetch;
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('renders workspace route', () => {
        render(
            <MemoryRouter initialEntries={['/workspace']}>
                <App />
            </MemoryRouter>
        );

        expect(screen.getByText(/badgermole workspace/i)).toBeInTheDocument();
    });

    it('renders login route', () => {
        render(
            <MemoryRouter initialEntries={['/login']}>
                <App />
            </MemoryRouter>
        );

        expect(screen.getByText(/badgermole login/i)).toBeInTheDocument();
    });

    it('renders datasource management panel for system admin users', async () => {
        globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = typeof input === 'string' ? input : input.toString();

            if (url.endsWith('/api/auth/me')) {
                return new Response(
                    JSON.stringify({
                        username: 'admin',
                        displayName: 'Platform Admin',
                        roles: ['SYSTEM_ADMIN', 'USER']
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

            return new Response('{}', { status: 200 });
        }) as unknown as typeof fetch;

        render(
            <MemoryRouter initialEntries={['/workspace']}>
                <App />
            </MemoryRouter>
        );

        expect(await screen.findByText(/datasource management/i)).toBeInTheDocument();
    });
});
