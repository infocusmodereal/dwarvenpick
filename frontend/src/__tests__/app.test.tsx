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
});
