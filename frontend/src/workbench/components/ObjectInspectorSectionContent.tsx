import type { ObjectInspectorSectionResponse } from '../types';
import InlineNotice from './InlineNotice';

type ObjectInspectorSectionContentProps = {
    section: ObjectInspectorSectionResponse;
};

export default function ObjectInspectorSectionContent({
    section
}: ObjectInspectorSectionContentProps) {
    if (section.status === 'UNSUPPORTED') {
        return (
            <p className="explorer-empty" role="status">
                {section.message ?? 'This inspection is not available for the selected engine.'}
            </p>
        );
    }

    if (section.status === 'INSUFFICIENT_PRIVILEGES') {
        return (
            <InlineNotice tone="error">
                {section.message ?? 'The selected credential profile cannot inspect this section.'}
            </InlineNotice>
        );
    }

    if (section.status === 'ERROR') {
        return (
            <InlineNotice tone="error">
                {section.message ?? 'Object inspection failed.'}
            </InlineNotice>
        );
    }

    const sectionMessage = section.message?.trim() ? (
        <p className="object-inspector-message">{section.message}</p>
    ) : null;

    if (section.kind === 'TEXT') {
        return (
            <>
                {sectionMessage}
                <pre className="object-inspector-text">{section.text ?? ''}</pre>
            </>
        );
    }

    if (section.kind === 'KEY_VALUES' && section.keyValues && section.keyValues.length > 0) {
        return (
            <>
                {sectionMessage}
                <table className="object-inspector-table">
                    <tbody>
                        {section.keyValues.map((entry) => (
                            <tr key={`object-inspector-kv-${entry.key}`}>
                                <th scope="row">{entry.key}</th>
                                <td>{entry.value ?? ''}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </>
        );
    }

    if (section.kind === 'TABLE' && section.table) {
        return (
            <>
                {sectionMessage}
                <div className="object-inspector-table-wrap">
                    <table className="object-inspector-table">
                        <thead>
                            <tr>
                                {section.table.columns.map((column) => (
                                    <th key={`object-inspector-th-${column}`} scope="col">
                                        {column}
                                    </th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {section.table.rows.map((row, rowIndex) => (
                                <tr key={`object-inspector-row-${rowIndex}`}>
                                    {row.map((cell, cellIndex) => (
                                        <td key={`object-inspector-cell-${rowIndex}-${cellIndex}`}>
                                            {cell ?? ''}
                                        </td>
                                    ))}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </>
        );
    }

    return <p className="explorer-empty">Inspector data is not available.</p>;
}
