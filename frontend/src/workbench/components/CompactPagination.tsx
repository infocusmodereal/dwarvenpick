type Props = {
    pageIndex: number;
    totalPages: number | null;
    hasNextPage: boolean;
    disabled?: boolean;
    onPageChange: (pageIndex: number) => void;
    label: string;
};

export default function CompactPagination({
    pageIndex,
    totalPages,
    hasNextPage,
    disabled = false,
    onPageChange,
    label
}: Props) {
    return (
        <nav className="compact-pagination" aria-label={label}>
            <button
                type="button"
                title="Previous page"
                aria-label="Previous Page"
                disabled={disabled || pageIndex === 0}
                onClick={() => onPageChange(Math.max(0, pageIndex - 1))}
            >
                ‹
            </button>
            <span
                className="compact-pagination-position"
                aria-label={`Page ${pageIndex + 1} of ${totalPages ?? 'unknown'}`}
            >
                <strong>{pageIndex + 1}</strong>
                <span>of {totalPages ?? '…'}</span>
            </span>
            <button
                type="button"
                title="Next page"
                aria-label="Next Page"
                disabled={disabled || !hasNextPage}
                onClick={() => onPageChange(pageIndex + 1)}
            >
                ›
            </button>
        </nav>
    );
}
