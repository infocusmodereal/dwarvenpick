import { type RefObject, useCallback, useEffect, useRef, useState } from 'react';
import type { WorkspaceTab } from '../types';
import { EditorTabCloseIcon, EditorTabMenuIcon, IconGlyph } from './WorkbenchIcons';

type QueryTabsBarProps = {
    workspaceTabs: WorkspaceTab[];
    activeTabId: string;
    editorTabsRowRef: RefObject<HTMLDivElement>;
    onSelectTab: (tabId: string) => void;
    onCloseTab: (tabId: string) => void;
    onNewTab: () => void;
    onRenameTab: (tabId: string) => void;
    onDuplicateTab: (tabId: string) => void;
};

export default function QueryTabsBar({
    workspaceTabs,
    activeTabId,
    editorTabsRowRef,
    onSelectTab,
    onCloseTab,
    onNewTab,
    onRenameTab,
    onDuplicateTab
}: QueryTabsBarProps) {
    const [menuOpen, setMenuOpen] = useState(false);
    const [menuPosition, setMenuPosition] = useState<{ top: number; left: number } | null>(null);
    const menuRef = useRef<HTMLDivElement | null>(null);
    const menuAnchorRef = useRef<HTMLDivElement | null>(null);
    const activeTab = workspaceTabs.find((tab) => tab.id === activeTabId) ?? null;

    const closeMenu = useCallback(() => {
        setMenuOpen(false);
        setMenuPosition(null);
    }, []);

    useEffect(() => {
        closeMenu();
    }, [activeTabId, closeMenu]);

    useEffect(() => {
        const handleOutsideClick = (event: MouseEvent) => {
            const target = event.target as Node | null;
            if (
                !target ||
                (!menuRef.current?.contains(target) && !menuAnchorRef.current?.contains(target))
            ) {
                closeMenu();
            }
        };

        if (menuOpen) {
            document.addEventListener('mousedown', handleOutsideClick);
        }

        return () => {
            document.removeEventListener('mousedown', handleOutsideClick);
        };
    }, [closeMenu, menuOpen]);

    useEffect(() => {
        if (!menuOpen) {
            return;
        }

        const handleViewportChange = () => {
            closeMenu();
        };

        window.addEventListener('resize', handleViewportChange);
        window.addEventListener('scroll', handleViewportChange, true);
        return () => {
            window.removeEventListener('resize', handleViewportChange);
            window.removeEventListener('scroll', handleViewportChange, true);
        };
    }, [closeMenu, menuOpen]);

    return (
        <>
            <div className="editor-tabs-row" ref={editorTabsRowRef}>
                <div className="editor-tabs" role="tablist" aria-label="SQL tabs">
                    {workspaceTabs.map((tab) => (
                        <div
                            key={tab.id}
                            role="presentation"
                            className={tab.id === activeTabId ? 'editor-tab active' : 'editor-tab'}
                        >
                            <button
                                type="button"
                                role="tab"
                                className="editor-tab-trigger"
                                aria-selected={tab.id === activeTabId}
                                onClick={() => onSelectTab(tab.id)}
                                title={tab.title}
                            >
                                <span>{tab.title}</span>
                                {tab.isExecuting ? (
                                    <span className="editor-tab-running">Running</span>
                                ) : null}
                            </button>
                            <button
                                type="button"
                                className="editor-tab-close"
                                title="Close tab"
                                aria-label={`Close ${tab.title}`}
                                disabled={workspaceTabs.length <= 1}
                                onClick={(event) => {
                                    event.stopPropagation();
                                    onCloseTab(tab.id);
                                }}
                            >
                                <EditorTabCloseIcon />
                            </button>
                            {tab.id === activeTabId ? (
                                <div className="editor-tab-menu-anchor" ref={menuAnchorRef}>
                                    <button
                                        type="button"
                                        className="editor-tab-menu-trigger"
                                        title="Tab actions"
                                        aria-label="Tab actions"
                                        onClick={(event) => {
                                            const triggerRect =
                                                event.currentTarget.getBoundingClientRect();
                                            setMenuOpen((current) => {
                                                const next = !current;
                                                setMenuPosition(
                                                    next
                                                        ? {
                                                              top: triggerRect.bottom + 6,
                                                              left: Math.max(
                                                                  12,
                                                                  triggerRect.right - 188
                                                              )
                                                          }
                                                        : null
                                                );
                                                return next;
                                            });
                                        }}
                                    >
                                        <EditorTabMenuIcon />
                                    </button>
                                </div>
                            ) : null}
                        </div>
                    ))}
                    <button
                        type="button"
                        className="editor-tab-add"
                        onClick={onNewTab}
                        title="New tab"
                        aria-label="New tab"
                    >
                        <svg
                            viewBox="0 0 20 20"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="1.8"
                        >
                            <path d="M10 4v12" />
                            <path d="M4 10h12" />
                        </svg>
                    </button>
                </div>
            </div>
            {menuOpen && activeTab && menuPosition ? (
                <div
                    className="editor-tab-menu is-floating"
                    role="menu"
                    ref={menuRef}
                    style={{
                        top: `${menuPosition.top}px`,
                        left: `${menuPosition.left}px`
                    }}
                >
                    <button
                        type="button"
                        role="menuitem"
                        className="editor-tab-menu-item"
                        onClick={() => {
                            closeMenu();
                            onRenameTab(activeTab.id);
                        }}
                    >
                        <span className="editor-tab-menu-item-icon" aria-hidden>
                            <IconGlyph icon="rename" />
                        </span>
                        <span>Rename</span>
                    </button>
                    <button
                        type="button"
                        role="menuitem"
                        className="editor-tab-menu-item"
                        onClick={() => {
                            closeMenu();
                            onDuplicateTab(activeTab.id);
                        }}
                    >
                        <span className="editor-tab-menu-item-icon" aria-hidden>
                            <IconGlyph icon="duplicate" />
                        </span>
                        <span>Duplicate</span>
                    </button>
                </div>
            ) : null}
        </>
    );
}
