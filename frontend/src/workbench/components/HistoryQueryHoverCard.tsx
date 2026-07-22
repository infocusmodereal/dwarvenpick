import type { MouseEvent as ReactMouseEvent } from 'react';
import type { QueryHoverCard } from '../historyQueryPreview';

type HistoryQueryHoverCardProps = {
    copiedExecutionId: string;
    hoveredQuery: QueryHoverCard | null;
    onCopy: (executionId: string, queryText: string) => Promise<void>;
    onMouseEnter: () => void;
    onMouseLeave: () => void;
};

export default function HistoryQueryHoverCard({
    copiedExecutionId,
    hoveredQuery,
    onCopy,
    onMouseEnter,
    onMouseLeave
}: HistoryQueryHoverCardProps) {
    if (!hoveredQuery) {
        return null;
    }

    return (
        <div
            id={`history-query-hover-${hoveredQuery.executionId}`}
            className="history-query-hover-card"
            role="tooltip"
            style={{
                top: hoveredQuery.top,
                left: hoveredQuery.left,
                width: hoveredQuery.width
            }}
            onMouseEnter={onMouseEnter}
            onMouseLeave={onMouseLeave}
        >
            <code className="history-query-full">{hoveredQuery.queryText}</code>
            <button
                type="button"
                className="history-query-copy"
                onClick={(event: ReactMouseEvent<HTMLButtonElement>) => {
                    event.preventDefault();
                    event.stopPropagation();
                    void onCopy(hoveredQuery.executionId, hoveredQuery.queryText);
                }}
            >
                {copiedExecutionId === hoveredQuery.executionId ? 'Copied' : 'Copy'}
            </button>
        </div>
    );
}
