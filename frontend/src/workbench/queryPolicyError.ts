type ApiErrorPayload = {
    error?: string | null;
};

export const governedQueryDeniedFallback =
    'The query was blocked by the selected credential profile policy.';

export const readGovernedQueryError = async (response: Response): Promise<string> => {
    try {
        const payload = (await response.json()) as ApiErrorPayload;
        const message = payload.error?.trim();
        if (message) {
            return message;
        }
    } catch {
        // Use the governed-query fallback when the response is not valid JSON.
    }

    return governedQueryDeniedFallback;
};
