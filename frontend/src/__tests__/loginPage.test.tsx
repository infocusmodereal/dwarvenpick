import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from '../pages/LoginPage';
import WorkspaceLoadingScreen from '../components/WorkspaceLoadingScreen';
import { ThemeProvider } from '../theme/ThemeContext';

describe('Login presentation', () => {
    beforeEach(() => {
        vi.stubGlobal(
            'fetch',
            vi.fn(
                async (url: string) =>
                    new Response(
                        JSON.stringify(
                            url === '/api/auth/methods'
                                ? { methods: ['local', 'oidc'] }
                                : url === '/api/auth/csrf'
                                  ? { token: 'test-token', headerName: 'X-CSRF-TOKEN' }
                                  : { error: 'Invalid credentials.' }
                        ),
                        { status: url === '/api/auth/login' ? 401 : 200 }
                    )
            )
        );
    });

    afterEach(() => vi.unstubAllGlobals());

    it('keeps passwords masked by default and remasks on submit', async () => {
        render(
            <ThemeProvider>
                <MemoryRouter>
                    <LoginPage />
                </MemoryRouter>
            </ThemeProvider>
        );
        expect(await screen.findByRole('button', { name: 'Continue with SSO' })).toBeVisible();
        const password = screen.getByLabelText('Password');
        expect(password).toHaveAttribute('type', 'password');
        fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'admin' } });
        fireEvent.change(password, { target: { value: 'test-password' } });
        fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
        expect(password).toHaveAttribute('type', 'text');
        expect(fetch).toHaveBeenCalledTimes(1);
        const remember = screen.getByRole('checkbox', { name: 'Remember this device' });
        expect(remember).not.toBeChecked();
        fireEvent.click(remember);
        fireEvent.click(screen.getByRole('button', { name: 'Hide password' }));
        expect(password).toHaveAttribute('type', 'password');
        fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
        fireEvent.click(screen.getByRole('button', { name: 'Sign In' }));
        await waitFor(() => expect(password).toHaveAttribute('type', 'password'));
        expect(await screen.findByText('Invalid credentials.')).toBeVisible();
        expect(fetch).toHaveBeenCalledWith(
            '/api/auth/login',
            expect.objectContaining({
                body: JSON.stringify({
                    username: 'admin',
                    password: 'test-password',
                    rememberDevice: true
                })
            })
        );
        expect(password.closest('.auth-app-shell')).not.toBeNull();
    });

    it('uses the same background shell for workspace progress', () => {
        render(<WorkspaceLoadingScreen />);
        expect(screen.getByRole('status').closest('.auth-app-shell')).not.toBeNull();
    });
});
