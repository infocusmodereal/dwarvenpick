import { Navigate, Route, Routes } from 'react-router';
import LoginPage from './pages/LoginPage';
import WorkspacePage from './pages/WorkspacePage';
import WorkspaceLoadingScreen from './components/WorkspaceLoadingScreen';

export default function App() {
    return (
        <Routes>
            <Route path="/loading-preview" element={<WorkspaceLoadingScreen />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/workspace" element={<WorkspacePage />} />
            <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
    );
}
