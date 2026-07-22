export type QueryHoverCard = {
    executionId: string;
    queryText: string;
    top: number;
    left: number;
    width: number;
};

const HOVER_CARD_MAX_WIDTH = 672;
const HOVER_CARD_MIN_WIDTH = 320;
const HOVER_CARD_MAX_HEIGHT = 224;
const HOVER_CARD_MARGIN = 16;

const clamp = (value: number, min: number, max: number) => {
    return Math.min(Math.max(value, min), max);
};

export const getQueryHoverCard = (
    trigger: HTMLElement,
    executionId: string,
    queryText: string
): QueryHoverCard => {
    const rect = trigger.getBoundingClientRect();
    const availableWidth = Math.max(
        HOVER_CARD_MIN_WIDTH,
        window.innerWidth - HOVER_CARD_MARGIN * 2
    );
    const width = Math.min(HOVER_CARD_MAX_WIDTH, availableWidth);
    const left = clamp(
        rect.right - width,
        HOVER_CARD_MARGIN,
        Math.max(HOVER_CARD_MARGIN, window.innerWidth - width - HOVER_CARD_MARGIN)
    );
    const preferredTop = rect.bottom + 6;
    const top =
        preferredTop + HOVER_CARD_MAX_HEIGHT > window.innerHeight
            ? Math.max(HOVER_CARD_MARGIN, rect.top - HOVER_CARD_MAX_HEIGHT - 6)
            : preferredTop;

    return { executionId, queryText, top, left, width };
};

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
