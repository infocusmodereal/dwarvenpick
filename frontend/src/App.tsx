import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router';
import LoginPage from './pages/LoginPage';
import WorkspaceLoadingScreen from './components/WorkspaceLoadingScreen';

const WorkspacePage = lazy(() => import('./pages/WorkspacePage'));

export default function App() {
    return (
        <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
                path="/workspace"
                element={
                    <Suspense fallback={<WorkspaceLoadingScreen />}>
                        <WorkspacePage />
                    </Suspense>
                }
            />
            <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
    );
}
