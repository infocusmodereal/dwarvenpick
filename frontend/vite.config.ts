import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        host: '0.0.0.0',
        proxy: {
            '/api': {
                target: process.env.DWARVENPICK_BACKEND_URL || 'http://localhost:8080',
                changeOrigin: true
            },
            '/actuator': {
                target: process.env.DWARVENPICK_BACKEND_URL || 'http://localhost:8080',
                changeOrigin: true
            },
            '/ws': {
                target: (process.env.DWARVENPICK_BACKEND_URL || 'http://localhost:8080').replace(
                    /^http/,
                    'ws'
                ),
                ws: true
            }
        }
    }
});
