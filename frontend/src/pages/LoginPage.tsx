import { FormEvent, useEffect, useState } from 'react';
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

type AuthMethodsResponse = {
    methods?: string[];
};

const authMethodPriority: AuthMethod[] = ['local', 'ldap'];

export default function LoginPage() {
    const navigate = useNavigate();
    const [supportedMethods, setSupportedMethods] = useState<AuthMethod[]>(authMethodPriority);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');

    useEffect(() => {
        let active = true;

        const loadAuthMethods = async () => {
            setErrorMessage('');
            try {
                const response = await fetch('/api/auth/methods', {
                    method: 'GET',
                    credentials: 'include'
                });
                if (!response.ok) {
                    throw new Error('Unable to load supported authentication methods.');
                }

                const payload = (await response.json()) as AuthMethodsResponse;
                const methods = (payload.methods ?? []).filter(
                    (method): method is AuthMethod => method === 'local' || method === 'ldap'
                );

                if (!active) {
                    return;
                }

                const prioritizedMethods = authMethodPriority.filter((method) =>
                    methods.includes(method)
                );
                setSupportedMethods(prioritizedMethods);

                if (prioritizedMethods.length === 0) {
                    setErrorMessage('Sign in is currently unavailable. Contact an administrator.');
                }
            } catch {
                if (!active) {
                    return;
                }

                // Fall back to trying local then LDAP when capability discovery is unavailable.
                setSupportedMethods(authMethodPriority);
            }
        };

        void loadAuthMethods();
        return () => {
            active = false;
        };
    }, []);

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

    const sanitizeServerError = (message: string): string => {
        const normalized = message.toLowerCase();
        if (
            normalized.includes('local authentication') ||
            normalized.includes('ldap authentication')
        ) {
            return 'Invalid credentials. Please try again.';
        }

        return message;
    };

    const readFriendlyError = async (response: Response): Promise<string> => {
        try {
            const payload = (await response.json()) as ApiErrorResponse;
            if (payload.error?.trim()) {
                return sanitizeServerError(payload.error);
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

        if (supportedMethods.length === 0) {
            setErrorMessage('Sign in is currently unavailable. Contact an administrator.');
            return;
        }

        setIsSubmitting(true);

        try {
            const csrfToken = await fetchCsrfToken();
            let lastErrorMessage = 'Invalid credentials. Please try again.';

            for (const method of supportedMethods) {
                const endpoint = method === 'local' ? '/api/auth/login' : '/api/auth/ldap/login';
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

                if (response.ok) {
                    navigate('/workspace', { replace: true });
                    return;
                }

                const responseMessage = await readFriendlyError(response);
                if (response.status === 401 || response.status === 403) {
                    lastErrorMessage = responseMessage;
                    continue;
                }

                if (response.status === 400 && responseMessage.toLowerCase().includes('disabled')) {
                    continue;
                }

                lastErrorMessage = responseMessage;
            }

            throw new Error(lastErrorMessage);
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
        <AppShell title="dwarvenpick" showTitle={false} topNav={false}>
            <section className="panel login-card">
                <div className="login-brand">
                    <img
                        src="/dwarvenpick-mark.svg"
                        alt=""
                        width={54}
                        height={54}
                        className="login-brand-mark"
                    />
                    <strong>dwarvenpick</strong>
                </div>
                <form className="login-form" onSubmit={handleSubmit}>
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

                    <button type="submit" disabled={isSubmitting || supportedMethods.length === 0}>
                        {isSubmitting ? 'Signing In...' : 'Sign In'}
                    </button>
                </form>
            </section>
        </AppShell>
    );
}
