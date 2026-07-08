import { useRef, useState } from 'react';
import type { ControlPlaneDatasourceStatusResponse } from './types';

export const useControlPlaneState = () => {
    const [controlPlaneWindowSeconds, setControlPlaneWindowSeconds] = useState(900);
    const [controlPlaneActorFilter, setControlPlaneActorFilter] = useState('');
    const [controlPlaneResponse, setControlPlaneResponse] =
        useState<ControlPlaneDatasourceStatusResponse | null>(null);
    const [loadingControlPlane, setLoadingControlPlane] = useState(false);
    const [controlPlaneError, setControlPlaneError] = useState('');
    const [controlPlaneAutoRefresh, setControlPlaneAutoRefresh] = useState(true);
    const controlPlanePollingTimerRef = useRef<number | null>(null);
    const controlPlaneRequestInFlightRef = useRef(false);

    return {
        controlPlaneActorFilter,
        controlPlaneAutoRefresh,
        controlPlaneError,
        controlPlanePollingTimerRef,
        controlPlaneRequestInFlightRef,
        controlPlaneResponse,
        controlPlaneWindowSeconds,
        loadingControlPlane,
        setControlPlaneActorFilter,
        setControlPlaneAutoRefresh,
        setControlPlaneError,
        setControlPlaneResponse,
        setControlPlaneWindowSeconds,
        setLoadingControlPlane
    };
};
