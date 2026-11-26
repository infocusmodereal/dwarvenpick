const formatCsvCell = (value: string | null | undefined): string => {
    if (value === null || value === undefined) {
        return '';
    }

    const raw = String(value);
    const requiresQuotes =
        raw.includes(',') || raw.includes('"') || raw.includes('\n') || raw.includes('\r');

    if (!requiresQuotes) {
        return raw;
    }

    return `"${raw.replace(/"/g, '""')}"`;
};

export const toCsv = (headers: string[], rows: Array<Array<string | null | undefined>>): string => {
    const headerRow = headers.map((value) => formatCsvCell(value)).join(',') + '\n';
    const bodyRows = rows.map((row) => row.map((value) => formatCsvCell(value)).join(',') + '\n');
    return headerRow + bodyRows.join('');
};

export const downloadCsv = (fileName: string, csv: string) => {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(objectUrl);
};
