import { useEffect, useRef, type ReactNode } from 'react';

export default function HistoryDisclosure({
    label,
    trigger,
    end = false,
    children
}: {
    label: string;
    trigger: ReactNode;
    end?: boolean;
    children: ReactNode;
}) {
    const disclosure = useRef<HTMLDetailsElement>(null);
    useEffect(() => {
        const dismissOutside = (event: PointerEvent) => {
            const element = disclosure.current;
            if (element?.open && event.target instanceof Node && !element.contains(event.target)) {
                element.open = false;
            }
        };
        document.addEventListener('pointerdown', dismissOutside);
        return () => document.removeEventListener('pointerdown', dismissOutside);
    }, []);

    return (
        <details
            ref={disclosure}
            className={`history-disclosure${end ? ' align-end' : ''}`}
            onBlur={(event) => {
                if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget))
                    event.currentTarget.open = false;
            }}
            onKeyDown={(event) => {
                if (event.key !== 'Escape' || !event.currentTarget.open) return;
                event.stopPropagation();
                event.currentTarget.open = false;
                event.currentTarget.querySelector('summary')?.focus();
            }}
        >
            <summary aria-label={label} title={label}>
                {trigger}
            </summary>
            <div className="workbench-popover">{children}</div>
        </details>
    );
}
