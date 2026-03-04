import type {
    ExplorerGlyph,
    IconButtonProps,
    IconGlyph as IconGlyphType,
    RailGlyph
} from '../types';
import {
    activityIcon,
    alignStartHorizontalIcon,
    circlePlayIcon,
    downloadIcon,
    explorerInspectIcon,
    explorerIconSvgByGlyph,
    explorerInsertIcon,
    fileTextIcon,
    pencilIcon,
    playIcon,
    plusIcon,
    railIconSvgByGlyph,
    refreshCwIcon,
    settingsIcon,
    shieldCheckIcon,
    tabCloseIcon,
    tabMenuIcon,
    trashIcon
} from '../icons';

export const IconGlyph = ({ icon }: { icon: IconGlyphType }) => {
    if (icon === 'new') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: plusIcon
                }}
            />
        );
    }

    if (icon === 'rename') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: pencilIcon
                }}
            />
        );
    }

    if (icon === 'duplicate') {
        return (
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.7">
                <rect x="6.2" y="6.2" width="9.8" height="9.8" rx="1.4" />
                <path d="M4 12.8V4.9A1.1 1.1 0 0 1 5.1 3.8H13" />
            </svg>
        );
    }

    if (icon === 'refresh') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: refreshCwIcon
                }}
            />
        );
    }

    if (icon === 'play') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: playIcon
                }}
            />
        );
    }

    if (icon === 'circle-play') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: circlePlayIcon
                }}
            />
        );
    }

    if (icon === 'align-start-horizontal') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: alignStartHorizontalIcon
                }}
            />
        );
    }

    if (icon === 'activity') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: activityIcon
                }}
            />
        );
    }

    if (icon === 'file-text') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: fileTextIcon
                }}
            />
        );
    }

    if (icon === 'shield-check') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: shieldCheckIcon
                }}
            />
        );
    }

    if (icon === 'settings') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: settingsIcon
                }}
            />
        );
    }

    if (icon === 'copy') {
        return (
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.7">
                <rect x="6.1" y="5.8" width="9.4" height="10.2" rx="1.4" />
                <path d="M4.2 12.2V4.6A1.2 1.2 0 0 1 5.4 3.4h7.5" />
            </svg>
        );
    }

    if (icon === 'delete') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: trashIcon
                }}
            />
        );
    }

    if (icon === 'info') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: railIconSvgByGlyph.info
                }}
            />
        );
    }

    if (icon === 'download') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: downloadIcon
                }}
            />
        );
    }

    if (icon === 'close') {
        return (
            <span
                className="icon-raw-glyph"
                aria-hidden
                dangerouslySetInnerHTML={{
                    __html: tabCloseIcon
                }}
            />
        );
    }

    return (
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="m5 5 10 10" />
            <path d="m15 5-10 10" />
        </svg>
    );
};

export const IconButton = ({
    icon,
    title,
    onClick,
    disabled = false,
    variant = 'default'
}: IconButtonProps) => (
    <button
        type="button"
        className={variant === 'danger' ? 'icon-button danger-button' : 'icon-button'}
        onClick={onClick}
        disabled={disabled}
        aria-label={title}
        title={title}
    >
        <span aria-hidden className="icon-button-glyph">
            <IconGlyph icon={icon} />
        </span>
    </button>
);

export const RailIcon = ({ glyph }: { glyph: RailGlyph }) => (
    <span
        className="rail-icon-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: railIconSvgByGlyph[glyph] ?? railIconSvgByGlyph.info
        }}
    />
);

export const ExplorerIcon = ({ glyph }: { glyph: ExplorerGlyph }) => (
    <span
        className="explorer-icon-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: explorerIconSvgByGlyph[glyph] ?? explorerIconSvgByGlyph.database
        }}
    />
);

export const ExplorerInsertIcon = () => (
    <span
        className="explorer-insert-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: explorerInsertIcon
        }}
    />
);

export const ExplorerInspectIcon = () => (
    <span
        className="explorer-insert-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: explorerInspectIcon
        }}
    />
);

export const ExplorerRefreshIcon = () => (
    <span
        className="explorer-icon-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: refreshCwIcon
        }}
    />
);

export const EditorTabCloseIcon = () => (
    <span
        className="editor-tab-icon-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: tabCloseIcon
        }}
    />
);

export const EditorTabMenuIcon = () => (
    <span
        className="editor-tab-icon-glyph"
        aria-hidden
        dangerouslySetInnerHTML={{
            __html: tabMenuIcon
        }}
    />
);

export const InfoHint = ({ text }: { text: string }) => (
    <span className="info-hint" title={text} aria-label={text}>
        <IconGlyph icon="info" />
    </span>
);
