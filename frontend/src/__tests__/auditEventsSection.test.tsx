import { fireEvent, render, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AuditEventsSection from '../workbench/sections/AuditEventsSection';
import type { AuditEventResponse } from '../workbench/types';

const event = (index: number): AuditEventResponse => ({
    type: `query.event.${index}`,
    actor: `analyst-${index}`,
    outcome: 'succeeded',
    details: { executionId: `exec-${index}` },
    timestamp: `2026-07-22T00:${String(index).padStart(2, '0')}:00Z`
});

const renderSection = (overrides: Partial<ComponentProps<typeof AuditEventsSection>> = {}) =>
    render(
        <AuditEventsSection
            hidden={false}
            auditActionOptions={['query.execute']}
            auditTypeFilter=""
            onAuditTypeFilterChange={vi.fn()}
            auditActorFilter=""
            onAuditActorFilterChange={vi.fn()}
            auditOutcomeOptions={['succeeded']}
            auditOutcomeFilter=""
            onAuditOutcomeFilterChange={vi.fn()}
            auditFromFilter=""
            onAuditFromFilterChange={vi.fn()}
            auditToFilter=""
            onAuditToFilterChange={vi.fn()}
            loadingAuditEvents={false}
            errorMessage=""
            onRefresh={vi.fn()}
            auditSortOrder="newest"
            onToggleSortOrder={vi.fn()}
            onClearFilters={vi.fn()}
            events={[event(1)]}
            {...overrides}
        />
    );

afterEach(() => {
    vi.restoreAllMocks();
});

describe('AuditEventsSection', () => {
    it('passes filter actions through and paginates loaded events', () => {
        const onAuditActorFilterChange = vi.fn();
        renderSection({
            events: Array.from({ length: 11 }, (_, index) => event(index + 1)),
            onAuditActorFilterChange
        });

        fireEvent.change(screen.getByLabelText('Actor'), { target: { value: 'analyst-11' } });
        fireEvent.change(screen.getByLabelText('Rows per page'), { target: { value: '10' } });
        expect(screen.getByText('analyst-1')).toBeInTheDocument();
        expect(screen.queryByText('analyst-11')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Next Page' }));

        expect(onAuditActorFilterChange).toHaveBeenCalledWith('analyst-11');
        expect(screen.getByText('analyst-11')).toBeInTheDocument();
        expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    });

    it('renders distinct loading, empty, and error states', () => {
        const { rerender } = renderSection({ events: [], loadingAuditEvents: true });
        expect(screen.getByText('Loading audit events...')).toBeInTheDocument();

        rerender(
            <AuditEventsSection
                hidden={false}
                auditActionOptions={[]}
                auditTypeFilter=""
                onAuditTypeFilterChange={vi.fn()}
                auditActorFilter=""
                onAuditActorFilterChange={vi.fn()}
                auditOutcomeOptions={[]}
                auditOutcomeFilter=""
                onAuditOutcomeFilterChange={vi.fn()}
                auditFromFilter=""
                onAuditFromFilterChange={vi.fn()}
                auditToFilter=""
                onAuditToFilterChange={vi.fn()}
                loadingAuditEvents={false}
                errorMessage="Audit is temporarily unavailable."
                onRefresh={vi.fn()}
                auditSortOrder="newest"
                onToggleSortOrder={vi.fn()}
                onClearFilters={vi.fn()}
                events={[]}
            />
        );

        expect(screen.getByRole('alert')).toHaveTextContent('Audit is temporarily unavailable.');
        expect(screen.getByText('No audit events found for current filters.')).toBeInTheDocument();
    });
});
