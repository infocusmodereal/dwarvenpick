import { FormEvent, useEffect, useMemo, useState } from 'react';
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
    methods: string[];
};

export default function LoginPage() {
    const navigate = useNavigate();
    const [supportedMethods, setSupportedMethods] = useState<AuthMethod[]>([]);
    const [authMethod, setAuthMethod] = useState<AuthMethod | ''>('');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [loadingMethods, setLoadingMethods] = useState(true);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');

    useEffect(() => {
        let active = true;

        const loadAuthMethods = async () => {
            setLoadingMethods(true);
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
                const methods = payload.methods.filter(
                    (method): method is AuthMethod => method === 'local' || method === 'ldap'
                );

                if (!active) {
                    return;
                }

                setSupportedMethods(methods);
                setAuthMethod((current) => {
                    if (methods.length === 1) {
                        return methods[0];
                    }
                    if (current && methods.includes(current)) {
                        return current;
                    }
                    return '';
                });

                if (methods.length === 0) {
                    setErrorMessage(
                        'No supported authentication methods are enabled. Contact an administrator.'
                    );
                }
            } catch (error) {
                if (!active) {
                    return;
                }

                if (error instanceof Error) {
                    setErrorMessage(error.message);
                } else {
                    setErrorMessage('Unable to load supported authentication methods.');
                }
            } finally {
                if (active) {
                    setLoadingMethods(false);
                }
            }
        };

        void loadAuthMethods();
        return () => {
            active = false;
        };
    }, []);

    const authMethodLabel = useMemo(() => {
        if (authMethod === 'local') {
            return 'Local';
        }
        if (authMethod === 'ldap') {
            return 'LDAP';
        }
        return 'Select Method';
    }, [authMethod]);

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

        if (!authMethod) {
            setErrorMessage('Select an authentication method before signing in.');
            return;
        }

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
                <p>Select Local or LDAP authentication, then sign in to continue.</p>

                <form className="login-form" onSubmit={handleSubmit}>
                    <div
                        className="auth-methods"
                        role="radiogroup"
                        aria-label="Authentication method"
                    >
                        {loadingMethods ? (
                            <p>Loading authentication methods...</p>
                        ) : (
                            supportedMethods.map((method) => (
                                <button
                                    key={method}
                                    type="button"
                                    className={authMethod === method ? 'chip active' : 'chip'}
                                    onClick={() => setAuthMethod(method)}
                                    aria-pressed={authMethod === method}
                                >
                                    {method === 'local' ? 'Local' : 'LDAP'}
                                </button>
                            ))
                        )}
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

                    <button
                        type="submit"
                        disabled={isSubmitting || loadingMethods || supportedMethods.length === 0}
                    >
                        {isSubmitting
                            ? authMethod === 'local'
                                ? 'Signing In...'
                                : 'Authenticating...'
                            : `Sign In (${authMethodLabel})`}
                    </button>
                </form>
            </section>
        </AppShell>
    );
}
