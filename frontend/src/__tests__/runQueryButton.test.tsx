import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RunQueryButton from '../workbench/components/RunQueryButton';
import { analysisSql, queryCapabilities } from '../workbench/queryCapabilities';

describe('Run split button', () => {
    it('defaults to statement, disables selection, and provides script through the menu', () => {
        const onRun = vi.fn(),
            onScript = vi.fn(),
            onCancel = vi.fn(),
            onSelection = vi.fn();
        const props = {
            disabled: false,
            running: false,
            hasSelection: false,
            onRun,
            onScript,
            onCancel,
            onSelection
        };
        const { rerender } = render(<RunQueryButton {...props} />);
        fireEvent.click(screen.getByRole('button', { name: 'Run' }));
        expect(onRun).toHaveBeenCalledOnce();
        fireEvent.click(screen.getByRole('button', { name: 'Run options' }));
        expect(screen.getByRole('menuitem', { name: 'Run selection' })).toBeDisabled();
        fireEvent.click(screen.getByRole('menuitem', { name: /Run script/ }));
        expect(onScript).toHaveBeenCalledOnce();
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();
        rerender(<RunQueryButton {...props} hasSelection />);
        fireEvent.click(screen.getByRole('button', { name: 'Run options' }));
        fireEvent.click(screen.getByRole('menuitem', { name: 'Run selection' }));
        expect(onSelection).toHaveBeenCalledOnce();
        rerender(<RunQueryButton {...props} running />);
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(onCancel).toHaveBeenCalledOnce();
    });
    it('preserves dialect analysis semantics outside presentation components', () => {
        for (const engine of ['POSTGRESQL', 'MYSQL', 'MARIADB', 'TRINO', 'STARROCKS', 'VERTICA'])
            expect(queryCapabilities(engine).explain).toBe(true);
        expect(queryCapabilities('AEROSPIKE').explain).toBe(false);
        expect(queryCapabilities('UNKNOWN').analyze).toBe(false);
        expect(analysisSql('select 1;', queryCapabilities('POSTGRESQL'))).toBe(
            'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) select 1'
        );
        expect(analysisSql('select 1', queryCapabilities('MYSQL'))).toBe(
            'EXPLAIN FORMAT=JSON select 1'
        );
        expect(analysisSql('EXPLAIN select 1', queryCapabilities('TRINO'))).toBe(
            'EXPLAIN select 1'
        );
    });
});
