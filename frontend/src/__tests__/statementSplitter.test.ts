import { describe, expect, it } from 'vitest';
import { splitSqlStatements, statementAtCursor } from '../sql/statementSplitter';

describe('statementSplitter', () => {
    it('splits semicolon-delimited statements and trims whitespace', () => {
        const sql = ' SELECT 1; \n\nSELECT 2 ;';
        const statements = splitSqlStatements(sql);

        expect(statements).toHaveLength(2);
        expect(statements[0].sql).toBe('SELECT 1');
        expect(statements[1].sql).toBe('SELECT 2');
    });

    it('ignores semicolons inside comments and quoted literals', () => {
        const sql = `
            SELECT 'a;b' AS text;
            -- this ; should not split
            SELECT 2 /* keep ; inside block comment */;
            SELECT "semi;colon" FROM demo;
        `;

        const statements = splitSqlStatements(sql);
        expect(statements).toHaveLength(3);
        expect(statements[0].sql).toContain("SELECT 'a;b' AS text");
        expect(statements[1].sql).toContain('SELECT 2');
        expect(statements[2].sql).toContain('SELECT "semi;colon"');
    });

    it('returns statement containing cursor offset', () => {
        const sql = 'SELECT 1;\nSELECT 2;\nSELECT 3;';
        const offset = sql.indexOf('2');
        const statement = statementAtCursor(sql, offset);

        expect(statement?.sql).toBe('SELECT 2');
    });
});
