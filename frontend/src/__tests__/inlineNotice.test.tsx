import { render, screen } from '@testing-library/react';
import InlineNotice from '../workbench/components/InlineNotice';

describe('InlineNotice', () => {
    it('announces errors immediately with the matching visual tone', () => {
        render(<InlineNotice tone="error">The query could not be submitted.</InlineNotice>);

        const notice = screen.getByRole('alert');
        expect(notice).toHaveClass('inline-notice', 'tone-error');
        expect(notice).toHaveTextContent('The query could not be submitted.');
    });

    it.each(['info', 'success', 'warning'] as const)(
        'announces %s feedback politely without relying on color alone',
        (tone) => {
            render(<InlineNotice tone={tone}>Feedback message</InlineNotice>);

            const notice = screen.getByRole('status');
            expect(notice).toHaveClass('inline-notice', `tone-${tone}`);
            expect(notice).toHaveAttribute('aria-live', 'polite');
            expect(notice.querySelector('.inline-notice-icon')).toBeInTheDocument();
        }
    );
});
