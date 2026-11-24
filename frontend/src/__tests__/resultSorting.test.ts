import { describe, expect, it } from 'vitest';
import { compareResultValues } from '../workbench/utils';

describe('result sorting', () => {
    it('preserves BIGINT and decimal precision', () => {
        expect(
            compareResultValues('9007199254740992', '9007199254740993', 'asc', 'int8')
        ).toBeLessThan(0);
        expect(
            compareResultValues('-0.000000000000000002', '-0.000000000000000001', 'asc', 'NUMERIC')
        ).toBeLessThan(0);
    });

    it('keeps empty strings and numeric identifiers as text', () => {
        expect(compareResultValues('', '0', 'asc', 'varchar')).toBeLessThan(0);
        expect(compareResultValues('0002', '2', 'asc', 'varchar')).toBeLessThan(0);
    });
});
