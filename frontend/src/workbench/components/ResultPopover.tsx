import { useLayoutEffect, useRef, type ReactNode, type RefObject } from 'react';

/** Keep result controls usable near either edge of a resized/expanded panel. */
export default function ResultPopover({
    label,
    anchor,
    children,
    className
}: {
    label: string;
    anchor: RefObject<HTMLButtonElement | null>;
    children: ReactNode;
    className: string;
}) {
    const popup = useRef<HTMLDivElement>(null);
    useLayoutEffect(() => {
        const position = () => {
            const rect = anchor.current?.getBoundingClientRect();
            const element = popup.current;
            if (!rect || !element) return;
            const below = window.innerHeight - rect.bottom - 12;
            const above = rect.top - 12;
            const upwards = below < 340 && above > below;
            element.style.position = 'fixed';
            element.style.top = upwards ? 'auto' : `${rect.bottom + 6}px`;
            element.style.bottom = upwards ? `${window.innerHeight - rect.top + 6}px` : 'auto';
            element.style.right = 'auto';
            element.style.left = `${Math.max(8, Math.min(rect.right - element.offsetWidth, window.innerWidth - element.offsetWidth - 8))}px`;
            element.style.maxHeight = `${Math.max(100, Math.min(420, upwards ? above : below))}px`;
        };
        position();
        window.addEventListener('resize', position);
        window.addEventListener('scroll', position, true);
        return () => {
            window.removeEventListener('resize', position);
            window.removeEventListener('scroll', position, true);
        };
    }, [anchor]);
    return (
        <div
            ref={popup}
            className={`workbench-popover ${className}`}
            role="dialog"
            aria-label={label}
        >
            {children}
        </div>
    );
}
