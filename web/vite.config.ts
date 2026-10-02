import { defineConfig } from 'vite';
// The server takes live connections from its own origin only (Origin must match Host), so the proxied
// WebSocket presents itself as the server: Host and Origin both become the server's.
const server = 'http://127.0.0.1:8080';
export default defineConfig({server:{proxy:{
  '^/api/v1/sites/[0-9]+/live$': { target: server, ws: true, changeOrigin: true, rewriteWsOrigin: true },
  '/api':server,'/actuator':server}}});
