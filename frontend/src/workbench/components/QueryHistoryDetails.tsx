import { useEffect, useRef, useState } from 'react';
import { copyQueryText, historyQueryText } from '../historyQueryText';
import type { QueryHistoryEntryResponse } from '../types';
import { formatExecutionTimestamp, toStatusToneClass } from '../utils';
import HistoryDisclosure from './HistoryDisclosure';
import InlineNotice from './InlineNotice';
import { EditorTabMenuIcon, IconButton, LabeledActionButton } from './WorkbenchIcons';

export function QueryHistoryStatus({ status }: { status: string }) {
    return (
        <span className={`${toStatusToneClass(status)} history-status`}>
            {status.toLowerCase()}
        </span>
    );
}

export default function QueryHistoryDetails({
    entry,
    onClose,
    onOpenEntry
}: {
    entry: QueryHistoryEntryResponse;
    onClose: () => void;
    onOpenEntry: (entry: QueryHistoryEntryResponse, rerun: boolean) => void;
}) {
    const panel = useRef<HTMLElement>(null);
    const [copyStatus, setCopyStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
    const canUseQuery = Boolean(entry.queryText && !entry.queryTextRedacted);
    const sql = historyQueryText(entry);
    const diagnosis = entry.errorSummary || (entry.status === 'CANCELED' ? entry.message : '');

    useEffect(() => {
        panel.current?.focus({ preventScroll: true });
    }, []);

    useEffect(() => {
        if (copyStatus === 'idle') return;
        const timeout = window.setTimeout(() => setCopyStatus('idle'), 2200);
        return () => window.clearTimeout(timeout);
    }, [copyStatus]);

    const copy = async () => {
        try {
            await copyQueryText(sql);
            setCopyStatus('copied');
        } catch {
            setCopyStatus('failed');
        }
    };

    return (
        <aside
            ref={panel}
            tabIndex={-1}
            className="query-history-details"
            id="query-history-details"
            aria-labelledby="query-history-detail-heading"
            onKeyDown={(event) => {
                if (event.key === 'Escape') onClose();
            }}
        >
            <header className="history-detail-heading">
                <h3 id="query-history-detail-heading">Execution details</h3>
                <IconButton icon="close" title="Close details" onClick={onClose} />
            </header>
            <div className="history-detail-summary">
                <QueryHistoryStatus status={entry.status} />
                {typeof entry.durationMs === 'number' && (
                    <span>{entry.durationMs.toLocaleString()} ms</span>
                )}
                <span>
                    {entry.rowCount.toLocaleString()} {entry.rowCount === 1 ? 'row' : 'rows'}
                </span>
            </div>
            {diagnosis && (
                <InlineNotice tone={entry.status === 'CANCELED' ? 'warning' : 'error'}>
                    {diagnosis}
                </InlineNotice>
            )}
            {entry.rowLimitReached && (
                <InlineNotice tone="warning">
                    Query row limit reached. Results may be partial.
                </InlineNotice>
            )}
            <div className="history-detail-heading">
                <h4>SQL</h4>
                <IconButton
                    icon="copy"
                    title="Copy SQL"
                    onClick={() => void copy()}
                    disabled={!canUseQuery}
                />
            </div>
            <pre className="history-detail-sql">{sql}</pre>
            {copyStatus === 'copied' && (
                <InlineNotice tone="success">Query text copied to clipboard.</InlineNotice>
            )}
            {copyStatus === 'failed' && (
                <InlineNotice tone="error">Unable to copy query text.</InlineNotice>
            )}
            <div className="history-detail-actions">
                <LabeledActionButton
                    icon="script"
                    label="Open in editor"
                    title="Open query in editor"
                    disabled={!canUseQuery}
                    onClick={() => onOpenEntry(entry, false)}
                />
                {canUseQuery && (
                    <HistoryDisclosure
                        label="More query actions"
                        trigger={<EditorTabMenuIcon />}
                        end
                    >
                        <button type="button" onClick={() => onOpenEntry(entry, true)}>
                            Rerun
                        </button>
                    </HistoryDisclosure>
                )}
            </div>
            <dl className="history-detail-fields">
                <div>
                    <dt>Execution ID</dt>
                    <dd className="history-execution-id">{entry.executionId}</dd>
                </div>
                <div>
                    <dt>Submitted</dt>
                    <dd>{formatExecutionTimestamp(entry.submittedAt)}</dd>
                </div>
                <div>
                    <dt>Connection</dt>
                    <dd>{entry.datasourceId}</dd>
                </div>
                <div>
                    <dt>User</dt>
                    <dd>{entry.actor}</dd>
                </div>
                <div>
                    <dt>Credential profile</dt>
                    <dd>{entry.credentialProfile}</dd>
                </div>
            </dl>
            <details
                className="history-detail-limits"
                open={Boolean(entry.justification?.trim()) || undefined}
            >
                <summary>Limits &amp; justification</summary>
                <dl className="history-detail-fields">
                    <div>
                        <dt>Query limit</dt>
                        <dd>{entry.maxRowsPerQuery.toLocaleString()}</dd>
                    </div>
                    <div>
                        <dt>Runtime limit</dt>
                        <dd>{entry.maxRuntimeSeconds} s</dd>
                    </div>
                    {entry.justification?.trim() && (
                        <div>
                            <dt>Justification</dt>
                            <dd>{entry.justification}</dd>
                        </div>
                    )}
                </dl>
            </details>
        </aside>
    );
}
