import type { ReactNode } from 'react';
import { IconGlyph } from './WorkbenchIcons';

export type InlineNoticeTone = 'info' | 'success' | 'warning' | 'error';

type InlineNoticeProps = {
    tone: InlineNoticeTone;
    children: ReactNode;
    className?: string;
};

const noticeIconByTone = {
    info: 'info',
    success: 'shield-check',
    warning: 'activity',
    error: 'close'
} as const;

export default function InlineNotice({ tone, children, className = '' }: InlineNoticeProps) {
    const isError = tone === 'error';

    return (
        <p
            className={`inline-notice tone-${tone}${className ? ` ${className}` : ''}`}
            role={isError ? 'alert' : 'status'}
            aria-live={isError ? undefined : 'polite'}
        >
            <span className="inline-notice-icon" aria-hidden>
                <IconGlyph icon={noticeIconByTone[tone]} />
            </span>
            <span className="inline-notice-message">{children}</span>
        </p>
    );
}
