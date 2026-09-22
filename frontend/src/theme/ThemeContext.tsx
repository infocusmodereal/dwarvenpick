/* eslint-disable react-refresh/only-export-components */

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

export type ThemeMode = 'light' | 'dark';

type ThemeContextValue = {
    theme: ThemeMode;
    setTheme: (theme: ThemeMode) => void;
    toggleTheme: () => void;
};

const THEME_STORAGE_KEY = 'dwarvenpick.theme';

const ThemeContext = createContext<ThemeContextValue | null>(null);

const resolvePreferredTheme = (): ThemeMode => {
    if (typeof window === 'undefined') {
        return 'light';
    }

    const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') {
        return stored;
    }

    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

export const ThemeProvider = ({ children }: { children: React.ReactNode }) => {
    const [theme, setThemeState] = useState<ThemeMode>(() => resolvePreferredTheme());

    const setTheme = useCallback((nextTheme: ThemeMode) => {
        setThemeState(nextTheme);
        window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
    }, []);

    const toggleTheme = useCallback(() => {
        setTheme(theme === 'dark' ? 'light' : 'dark');
    }, [setTheme, theme]);

    useEffect(() => {
        document.documentElement.dataset.theme = theme;
        document.documentElement.style.colorScheme = theme;
    }, [theme]);

    const value = useMemo<ThemeContextValue>(
        () => ({
            theme,
            setTheme,
            toggleTheme
        }),
        [setTheme, theme, toggleTheme]
    );

    return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
};

export const useTheme = (): ThemeContextValue => {
    const context = useContext(ThemeContext);
    if (!context) {
        throw new Error('useTheme must be used within ThemeProvider.');
    }
    return context;
};
