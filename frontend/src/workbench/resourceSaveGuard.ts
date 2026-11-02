export async function runResourceSaveOnce(
    pendingTabs: Set<string>,
    tabId: string,
    save: () => Promise<boolean>
): Promise<boolean> {
    if (pendingTabs.has(tabId)) {
        return false;
    }
    pendingTabs.add(tabId);
    try {
        return await save();
    } finally {
        pendingTabs.delete(tabId);
    }
}
