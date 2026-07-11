import type { SystemHealthResponse } from '../types';
import AerospikeSystemHealthView from './AerospikeSystemHealthView';
import MariaDbSystemHealthView from './MariaDbSystemHealthView';
import PostgresSystemHealthView from './PostgresSystemHealthView';
import StarRocksSystemHealthView from './StarRocksSystemHealthView';
import TrinoSystemHealthView from './TrinoSystemHealthView';
import VerticaSystemHealthView from './VerticaSystemHealthView';

type SystemHealthEngineViewProps = {
    response: SystemHealthResponse;
};

export default function SystemHealthEngineView({ response }: SystemHealthEngineViewProps) {
    switch (response.engine) {
        case 'POSTGRESQL':
            return <PostgresSystemHealthView response={response} />;
        case 'STARROCKS':
            return <StarRocksSystemHealthView response={response} />;
        case 'MARIADB':
        case 'MYSQL':
            return <MariaDbSystemHealthView response={response} />;
        case 'TRINO':
            return <TrinoSystemHealthView response={response} />;
        case 'AEROSPIKE':
            return <AerospikeSystemHealthView response={response} />;
        case 'VERTICA':
            return <VerticaSystemHealthView response={response} />;
        default: {
            const unregisteredEngine: never = response.engine;
            return (
                <div className="panel-inner">
                    <h3>System Health</h3>
                    <p className="muted-id">
                        No engine-specific health view is registered for{' '}
                        {String(unregisteredEngine)}.
                    </p>
                </div>
            );
        }
    }
}
