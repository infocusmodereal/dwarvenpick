import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AppShell from '../components/AppShell';

type AuthMethod = 'local' | 'ldap';

type CsrfTokenResponse = {
    token: string;
    headerName: string;
    parameterName: string;
};

type ApiErrorResponse = {
    error?: string;
};

export default function LoginPage() {
    const navigate = useNavigate();
    const [authMethod, setAuthMethod] = useState<AuthMethod>('local');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');

    const fetchCsrfToken = async (): Promise<CsrfTokenResponse> => {
        const response = await fetch('/api/auth/csrf', {
            method: 'GET',
            credentials: 'include'
        });

        if (!response.ok) {
            throw new Error('Unable to initialize a secure login session.');
        }

        return (await response.json()) as CsrfTokenResponse;
    };

    const readFriendlyError = async (response: Response): Promise<string> => {
        try {
            const payload = (await response.json()) as ApiErrorResponse;
            if (payload.error?.trim()) {
                return payload.error;
            }
        } catch {
            // Use fallback messages when the response is not valid JSON.
        }

        if (response.status === 401) {
            return 'Invalid credentials. Please try again.';
        }

        if (response.status === 403) {
            return 'Access denied. Confirm your account is enabled and try again.';
        }

        return 'Sign in failed. Please try again shortly.';
    };

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setErrorMessage('');
        setIsSubmitting(true);

        try {
            const csrfToken = await fetchCsrfToken();
            const endpoint = authMethod === 'local' ? '/api/auth/login' : '/api/auth/ldap/login';
            const response = await fetch(endpoint, {
                method: 'POST',
                credentials: 'include',
                headers: {
                    'Content-Type': 'application/json',
                    [csrfToken.headerName]: csrfToken.token
                },
                body: JSON.stringify({
                    username,
                    password
                })
            });

            if (!response.ok) {
                throw new Error(await readFriendlyError(response));
            }

            navigate('/workspace', { replace: true });
        } catch (error) {
            if (error instanceof Error) {
                setErrorMessage(error.message);
            } else {
                setErrorMessage('Sign in failed. Please try again shortly.');
            }
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <AppShell title="badgermole Login">
            <section className="panel">
                <h2>Authentication</h2>
                <p>Choose Local or LDAP sign-in and continue to your query workspace.</p>

                <form className="login-form" onSubmit={handleSubmit}>
                    <div
                        className="auth-methods"
                        role="radiogroup"
                        aria-label="Authentication method"
                    >
                        <button
                            type="button"
                            className={authMethod === 'local' ? 'chip active' : 'chip'}
                            onClick={() => setAuthMethod('local')}
                            aria-pressed={authMethod === 'local'}
                        >
                            Local
                        </button>
                        <button
                            type="button"
                            className={authMethod === 'ldap' ? 'chip active' : 'chip'}
                            onClick={() => setAuthMethod('ldap')}
                            aria-pressed={authMethod === 'ldap'}
                        >
                            LDAP
                        </button>
                    </div>

                    <label htmlFor="username">Username</label>
                    <input
                        id="username"
                        name="username"
                        autoComplete="username"
                        value={username}
                        onChange={(event) => setUsername(event.target.value)}
                        required
                    />

                    <label htmlFor="password">Password</label>
                    <input
                        id="password"
                        name="password"
                        type="password"
                        autoComplete="current-password"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        required
                    />

                    {errorMessage ? (
                        <p className="form-error" role="alert">
                            {errorMessage}
                        </p>
                    ) : null}

                    <button type="submit" disabled={isSubmitting}>
                        {isSubmitting
                            ? authMethod === 'local'
                                ? 'Signing In...'
                                : 'Authenticating...'
                            : authMethod === 'local'
                              ? 'Sign In (Local)'
                              : 'Sign In (LDAP)'}
                    </button>
                </form>
            </section>
        </AppShell>
    );
}
