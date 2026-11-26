import type { QueryHistoryEntryResponse } from './types';

export const historyQueryText = (entry: QueryHistoryEntryResponse) =>
    entry.queryTextRedacted ? '[REDACTED]' : (entry.queryText ?? '[empty]');

export const copyQueryText = async (value: string) => {
    if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        return;
    }

    const textArea = document.createElement('textarea');
    textArea.value = value;
    textArea.setAttribute('readonly', 'true');
    textArea.style.position = 'fixed';
    textArea.style.left = '-9999px';
    document.body.appendChild(textArea);
    textArea.select();
    const copied = document.execCommand('copy');
    textArea.remove();
    if (!copied) {
        throw new Error('Clipboard copy command was rejected.');
    }
};
