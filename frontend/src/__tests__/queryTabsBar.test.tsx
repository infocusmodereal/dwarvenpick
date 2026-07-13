import { createRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import QueryTabsBar from '../workbench/components/QueryTabsBar';
import { buildWorkspaceTab } from '../workbench/useWorkspaceTabs';

const queryOne = {
    ...buildWorkspaceTab('datasource-1', 'Query 1'),
    id: 'tab-1',
    isExecuting: true
};
const queryTwo = {
    ...buildWorkspaceTab('datasource-2', 'Query 2'),
    id: 'tab-2'
};

const callbacks = () => ({
    onSelectTab: vi.fn(),
    onCloseTab: vi.fn(),
    onNewTab: vi.fn(),
    onRenameTab: vi.fn(),
    onDuplicateTab: vi.fn()
});

describe('QueryTabsBar', () => {
    it('preserves tab roles, running state, selection, close and new-tab actions', () => {
        const actions = callbacks();
        const tabsRowRef = createRef<HTMLDivElement>();
        render(
            <QueryTabsBar
                workspaceTabs={[queryOne, queryTwo]}
                activeTabId="tab-1"
                editorTabsRowRef={tabsRowRef}
                {...actions}
            />
        );

        expect(screen.getByRole('tablist', { name: 'SQL tabs' })).toBeInTheDocument();
        expect(screen.getByRole('tab', { name: /Query 1/ })).toHaveAttribute(
            'aria-selected',
            'true'
        );
        expect(screen.getByRole('tab', { name: 'Query 2' })).toHaveAttribute(
            'aria-selected',
            'false'
        );
        expect(screen.getByText('Running')).toBeInTheDocument();
        expect(tabsRowRef.current).toHaveClass('editor-tabs-row');

        fireEvent.click(screen.getByRole('tab', { name: 'Query 2' }));
        fireEvent.click(screen.getByRole('button', { name: 'Close Query 1' }));
        fireEvent.click(screen.getByRole('button', { name: 'New tab' }));

        expect(actions.onSelectTab).toHaveBeenCalledWith('tab-2');
        expect(actions.onCloseTab).toHaveBeenCalledWith('tab-1');
        expect(actions.onNewTab).toHaveBeenCalledOnce();
        screen.getAllByTitle('Close tab').forEach((button) => {
            expect(button).not.toBeDisabled();
        });
    });

    it('disables closing the final tab', () => {
        render(
            <QueryTabsBar
                workspaceTabs={[queryOne]}
                activeTabId="tab-1"
                editorTabsRowRef={createRef<HTMLDivElement>()}
                {...callbacks()}
            />
        );

        expect(screen.getByRole('button', { name: 'Close Query 1' })).toBeDisabled();
    });

    it('routes rename and duplicate actions for the active tab', () => {
        const actions = callbacks();
        render(
            <QueryTabsBar
                workspaceTabs={[queryOne, queryTwo]}
                activeTabId="tab-1"
                editorTabsRowRef={createRef<HTMLDivElement>()}
                {...actions}
            />
        );

        fireEvent.click(screen.getByRole('button', { name: 'Tab actions' }));
        expect(screen.getByRole('menu')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('menuitem', { name: 'Rename' }));
        expect(actions.onRenameTab).toHaveBeenCalledWith('tab-1');
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'Tab actions' }));
        fireEvent.click(screen.getByRole('menuitem', { name: 'Duplicate' }));
        expect(actions.onDuplicateTab).toHaveBeenCalledWith('tab-1');
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    });

    it('dismisses the menu on active-tab changes, outside clicks and viewport changes', () => {
        const actions = callbacks();
        const { rerender } = render(
            <QueryTabsBar
                workspaceTabs={[queryOne, queryTwo]}
                activeTabId="tab-1"
                editorTabsRowRef={createRef<HTMLDivElement>()}
                {...actions}
            />
        );

        fireEvent.click(screen.getByRole('button', { name: 'Tab actions' }));
        rerender(
            <QueryTabsBar
                workspaceTabs={[queryOne, queryTwo]}
                activeTabId="tab-2"
                editorTabsRowRef={createRef<HTMLDivElement>()}
                {...actions}
            />
        );
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'Tab actions' }));
        fireEvent.mouseDown(document.body);
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'Tab actions' }));
        fireEvent(window, new Event('resize'));
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: 'Tab actions' }));
        fireEvent.scroll(window);
        expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    });
});
