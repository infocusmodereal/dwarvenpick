import railWorkbenchIcon from '../assets/lucide/layout-panel-top.svg?raw';
import railHistoryIcon from '../assets/lucide/history.svg?raw';
import railSnippetsIcon from '../assets/lucide/file-text.svg?raw';
import railAuditIcon from '../assets/lucide/shield-check.svg?raw';
import railAdminIcon from '../assets/lucide/settings.svg?raw';
import railHealthIcon from '../assets/lucide/activity.svg?raw';
import railConnectionsIcon from '../assets/lucide/database.svg?raw';
import railCollapseIcon from '../assets/lucide/panel-left-close.svg?raw';
import railMenuIcon from '../assets/lucide/panel-left-open.svg?raw';
import railInfoIcon from '../assets/lucide/info.svg?raw';
import explorerDatabaseIcon from '../assets/lucide/database.svg?raw';
import explorerSchemaIcon from '../assets/lucide/folder-tree.svg?raw';
import explorerTableIcon from '../assets/lucide/table-properties.svg?raw';
import explorerColumnIcon from '../assets/lucide/columns-3.svg?raw';
import explorerInsertIcon from '../assets/lucide/arrow-up-right.svg?raw';
import chevronRightIcon from '../assets/lucide/chevron-right.svg?raw';
import chevronDownIcon from '../assets/lucide/chevron-down.svg?raw';
import refreshCwIcon from '../assets/lucide/refresh-cw.svg?raw';
import tabCloseIcon from '../assets/lucide/x.svg?raw';
import tabMenuIcon from '../assets/lucide/ellipsis.svg?raw';
import downloadIcon from '../assets/lucide/download.svg?raw';
import plusIcon from '../assets/lucide/plus.svg?raw';
import pencilIcon from '../assets/lucide/pencil.svg?raw';
import trashIcon from '../assets/lucide/trash-2.svg?raw';
import sortUpIcon from '../assets/lucide/arrow-up.svg?raw';
import sortDownIcon from '../assets/lucide/arrow-down.svg?raw';
import sortNeutralIcon from '../assets/lucide/arrow-up-down.svg?raw';

import type { DatasourceEngine, ExplorerGlyph, RailGlyph } from './types';

export const datasourceIconByEngine: Record<DatasourceEngine, string> = {
    POSTGRESQL: '/db-icons/postgresql.svg',
    MYSQL: '/db-icons/mysql.svg',
    MARIADB: '/db-icons/mariadb.svg',
    TRINO: '/db-icons/trino.svg',
    STARROCKS: '/db-icons/starrocks.svg',
    VERTICA: '/db-icons/vertica.svg'
};

export const railIconSvgByGlyph: Record<RailGlyph, string> = {
    workbench: railWorkbenchIcon,
    history: railHistoryIcon,
    snippets: railSnippetsIcon,
    audit: railAuditIcon,
    health: railHealthIcon,
    admin: railAdminIcon,
    connections: railConnectionsIcon,
    collapse: railCollapseIcon,
    menu: railMenuIcon,
    info: railInfoIcon
};

export const explorerIconSvgByGlyph: Record<ExplorerGlyph, string> = {
    database: explorerDatabaseIcon,
    schema: explorerSchemaIcon,
    table: explorerTableIcon,
    column: explorerColumnIcon
};

export const resolveDatasourceIcon = (engine?: string): string => {
    if (!engine) {
        return '/db-icons/database.svg';
    }

    const normalized = engine.toUpperCase();
    if (normalized in datasourceIconByEngine) {
        return datasourceIconByEngine[normalized as DatasourceEngine];
    }

    return '/db-icons/database.svg';
};

export {
    chevronDownIcon,
    chevronRightIcon,
    downloadIcon,
    explorerInsertIcon,
    pencilIcon,
    plusIcon,
    refreshCwIcon,
    sortDownIcon,
    sortNeutralIcon,
    sortUpIcon,
    tabCloseIcon,
    tabMenuIcon,
    trashIcon
};
