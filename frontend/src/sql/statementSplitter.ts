export type SqlStatementSegment = {
    sql: string;
    start: number;
    end: number;
};

const trimSegment = (
    sql: string,
    start: number,
    endExclusive: number
): SqlStatementSegment | null => {
    const raw = sql.slice(start, endExclusive);
    if (!raw.trim()) {
        return null;
    }

    const leftTrim = raw.match(/^\s*/)?.[0].length ?? 0;
    const rightTrim = raw.match(/\s*$/)?.[0].length ?? 0;
    const trimmedStart = start + leftTrim;
    const trimmedEndExclusive = endExclusive - rightTrim;

    return {
        sql: sql.slice(trimmedStart, trimmedEndExclusive),
        start: trimmedStart,
        end: trimmedEndExclusive
    };
};

export const splitSqlStatements = (sql: string): SqlStatementSegment[] => {
    const segments: SqlStatementSegment[] = [];
    let segmentStart = 0;

    let inSingleQuote = false;
    let inDoubleQuote = false;
    let inLineComment = false;
    let inBlockComment = false;

    let index = 0;
    while (index < sql.length) {
        const current = sql[index];
        const next = sql[index + 1];

        if (inLineComment) {
            if (current === '\n') {
                inLineComment = false;
            }
            index += 1;
            continue;
        }

        if (inBlockComment) {
            if (current === '*' && next === '/') {
                inBlockComment = false;
                index += 2;
                continue;
            }
            index += 1;
            continue;
        }

        if (inSingleQuote) {
            if (current === "'" && next === "'") {
                index += 2;
                continue;
            }
            if (current === "'") {
                inSingleQuote = false;
            }
            index += 1;
            continue;
        }

        if (inDoubleQuote) {
            if (current === '"' && next === '"') {
                index += 2;
                continue;
            }
            if (current === '"') {
                inDoubleQuote = false;
            }
            index += 1;
            continue;
        }

        if (current === '-' && next === '-') {
            inLineComment = true;
            index += 2;
            continue;
        }

        if (current === '/' && next === '*') {
            inBlockComment = true;
            index += 2;
            continue;
        }

        if (current === "'") {
            inSingleQuote = true;
            index += 1;
            continue;
        }

        if (current === '"') {
            inDoubleQuote = true;
            index += 1;
            continue;
        }

        if (current === ';') {
            const segment = trimSegment(sql, segmentStart, index);
            if (segment) {
                segments.push(segment);
            }
            segmentStart = index + 1;
        }

        index += 1;
    }

    const trailingSegment = trimSegment(sql, segmentStart, sql.length);
    if (trailingSegment) {
        segments.push(trailingSegment);
    }

    return segments;
};

export const statementAtCursor = (
    sql: string,
    cursorOffset: number
): SqlStatementSegment | null => {
    const statements = splitSqlStatements(sql);
    if (statements.length === 0) {
        return null;
    }

    const normalizedOffset = Math.max(0, Math.min(cursorOffset, sql.length));
    const directHit = statements.find(
        (statement) => normalizedOffset >= statement.start && normalizedOffset <= statement.end
    );
    if (directHit) {
        return directHit;
    }

    const next = statements.find((statement) => statement.start > normalizedOffset);
    if (next) {
        return next;
    }

    return statements[statements.length - 1];
};
