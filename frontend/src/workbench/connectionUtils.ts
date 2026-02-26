import { defaultPortByEngine } from './constants';
import type { DatasourceEngine, TlsMode } from './types';

export const optionsToInput = (options: Record<string, string>): string =>
    Object.entries(options)
        .filter(([key]) => key !== 'jdbcUrl')
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, value]) => `${key}=${value}`)
        .join('\n');

export const parseOptionsInput = (value: string): Record<string, string> => {
    const options: Record<string, string> = {};
    value
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0)
        .forEach((line) => {
            const separatorIndex = line.indexOf('=');
            if (separatorIndex <= 0) {
                return;
            }

            const key = line.slice(0, separatorIndex).trim();
            const optionValue = line.slice(separatorIndex + 1).trim();
            if (!key) {
                return;
            }

            options[key] = optionValue;
        });
    return options;
};

export const formatQueryParams = (options: Record<string, string>): string => {
    const entries = Object.entries(options).filter(([key]) => key !== 'jdbcUrl');
    if (entries.length === 0) {
        return '';
    }

    return (
        '?' +
        entries
            .sort(([left], [right]) => left.localeCompare(right))
            .map(([key, value]) => `${key}=${value}`)
            .join('&')
    );
};

export const buildJdbcUrlPreview = (
    engine: DatasourceEngine,
    host: string,
    port: string,
    database: string,
    tlsMode: TlsMode,
    verifyServerCertificate: boolean,
    options: Record<string, string>
): string => {
    const jdbcUrlOverride = options.jdbcUrl?.trim();
    if (jdbcUrlOverride) {
        return jdbcUrlOverride;
    }

    const normalizedHost = host.trim() || 'localhost';
    const resolvedPort = Number(port) || defaultPortByEngine[engine];
    const databaseSegment = database.trim() ? `/${database.trim()}` : '';
    const mergedOptions: Record<string, string> = { ...options };

    if (engine === 'POSTGRESQL') {
        mergedOptions.sslmode = tlsMode === 'REQUIRE' ? 'require' : 'disable';
        if (tlsMode === 'REQUIRE' && !verifyServerCertificate) {
            mergedOptions.ssfactory = 'org.postgresql.ssl.NonValidatingFactory';
        } else {
            delete mergedOptions.ssfactory;
        }
        return `jdbc:postgresql://${normalizedHost}:${resolvedPort}${databaseSegment}${formatQueryParams(mergedOptions)}`;
    }

    if (engine === 'MYSQL' || engine === 'STARROCKS') {
        mergedOptions.useSSL = String(tlsMode === 'REQUIRE');
        mergedOptions.requireSSL = String(tlsMode === 'REQUIRE');
        mergedOptions.verifyServerCertificate = String(verifyServerCertificate);
        return `jdbc:mysql://${normalizedHost}:${resolvedPort}${databaseSegment}${formatQueryParams(mergedOptions)}`;
    }

    if (engine === 'MARIADB') {
        mergedOptions.useSsl = String(tlsMode === 'REQUIRE');
        mergedOptions.trustServerCertificate = String(!verifyServerCertificate);
        return `jdbc:mariadb://${normalizedHost}:${resolvedPort}${databaseSegment}${formatQueryParams(mergedOptions)}`;
    }

    if (engine === 'TRINO') {
        mergedOptions.SSL = String(tlsMode === 'REQUIRE');
        if (tlsMode === 'REQUIRE' && !verifyServerCertificate) {
            mergedOptions.SSLVerification = 'NONE';
        } else {
            delete mergedOptions.SSLVerification;
        }
        return `jdbc:trino://${normalizedHost}:${resolvedPort}${databaseSegment}${formatQueryParams(mergedOptions)}`;
    }

    mergedOptions.TLSmode = tlsMode === 'REQUIRE' ? 'require' : 'disable';
    if (tlsMode === 'REQUIRE') {
        mergedOptions.tls_verify_host = String(verifyServerCertificate);
    } else {
        delete mergedOptions.tls_verify_host;
    }
    return `jdbc:vertica://${normalizedHost}:${resolvedPort}${databaseSegment}${formatQueryParams(mergedOptions)}`;
};
