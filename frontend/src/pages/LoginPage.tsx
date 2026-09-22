import { FormEvent, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';
import AppShell from '../components/AppShell';
import BrandMark from '../components/BrandMark';
import { MoonIcon, SunIcon } from '../components/ThemeIcons';
import { useTheme } from '../theme/ThemeContext';
import InlineNotice from '../workbench/components/InlineNotice';

type AuthMethod = 'local' | 'ldap' | 'oidc';

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

const authMethodPriority: AuthMethod[] = ['oidc', 'local', 'ldap'];
const authMethodFallback: AuthMethod[] = ['local', 'ldap'];

export default function LoginPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { theme, toggleTheme } = useTheme();
    const [supportedMethods, setSupportedMethods] = useState<AuthMethod[]>(authMethodFallback);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [passwordVisible, setPasswordVisible] = useState(false);
    const [rememberDevice, setRememberDevice] = useState(false);
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');

    const supportsOidc = supportedMethods.includes('oidc');
    const passwordMethods = supportedMethods.filter(
        (method): method is Exclude<AuthMethod, 'oidc'> => method === 'local' || method === 'ldap'
    );
    const showPasswordForm = passwordMethods.length > 0;

    useEffect(() => {
        const params = new URLSearchParams(location.search);
        if (params.get('error') === 'oidc') {
            setErrorMessage('SSO sign in failed. Please try again.');
        }
    }, [location.search]);

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
                    (method): method is AuthMethod =>
                        method === 'local' || method === 'ldap' || method === 'oidc'
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
                setSupportedMethods(authMethodFallback);
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
        setPasswordVisible(false);
        setErrorMessage('');

        if (supportedMethods.length === 0) {
            setErrorMessage('Sign in is currently unavailable. Contact an administrator.');
            return;
        }

        if (passwordMethods.length === 0) {
            setErrorMessage('Use SSO to sign in.');
            return;
        }

        setIsSubmitting(true);

        try {
            const csrfToken = await fetchCsrfToken();
            let lastErrorMessage = 'Invalid credentials. Please try again.';

            for (const method of passwordMethods) {
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
                        password,
                        rememberDevice
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
        <AppShell title="dwarvenpick" showTitle={false} topNav={false} className="auth-app-shell">
            <section className="panel login-card">
                <button
                    type="button"
                    className="icon-button login-theme-toggle"
                    onClick={toggleTheme}
                    title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
                    aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
                >
                    <span className="icon-button-glyph" aria-hidden>
                        {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
                    </span>
                </button>
                <div className="login-brand">
                    <BrandMark alt="" width={111} height={111} className="login-brand-mark" />
                    <strong>dwarvenpick</strong>
                </div>
                <div className="login-form">
                    {supportsOidc ? (
                        <button
                            type="button"
                            className="chip login-sso-button"
                            onClick={() => {
                                window.location.assign('/oauth2/authorization/oidc');
                            }}
                            disabled={isSubmitting}
                        >
                            Continue with SSO
                        </button>
                    ) : null}

                    {showPasswordForm ? (
                        <form onSubmit={handleSubmit}>
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
                            <div className="login-password-field">
                                <input
                                    id="password"
                                    name="password"
                                    type={passwordVisible ? 'text' : 'password'}
                                    autoComplete="current-password"
                                    value={password}
                                    onChange={(event) => setPassword(event.target.value)}
                                    required
                                />
                                <button
                                    type="button"
                                    className="icon-button login-password-toggle"
                                    aria-label={passwordVisible ? 'Hide password' : 'Show password'}
                                    title={passwordVisible ? 'Hide password' : 'Show password'}
                                    aria-pressed={passwordVisible}
                                    aria-controls="password"
                                    onClick={() => setPasswordVisible((visible) => !visible)}
                                >
                                    <svg
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="1.8"
                                        aria-hidden="true"
                                    >
                                        <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                                        <circle cx="12" cy="12" r="3" />
                                        {passwordVisible ? <path d="m3 3 18 18" /> : null}
                                    </svg>
                                </button>
                            </div>

                            <label
                                className="login-remember-device"
                                title="Keep this password sign-in on this device until the session expires. Use only on a trusted device. SSO follows your identity provider's settings."
                            >
                                <input
                                    type="checkbox"
                                    checked={rememberDevice}
                                    onChange={(event) => setRememberDevice(event.target.checked)}
                                />
                                Remember this device
                            </label>

                            <button
                                type="submit"
                                disabled={isSubmitting || passwordMethods.length === 0}
                            >
                                {isSubmitting ? 'Signing In...' : 'Sign In'}
                            </button>
                        </form>
                    ) : null}

                    {errorMessage ? <InlineNotice tone="error">{errorMessage}</InlineNotice> : null}
                </div>
            </section>
        </AppShell>
    );
}
