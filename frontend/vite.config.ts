import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // O backend fica noutra porta em desenvolvimento. O proxy evita CORS e
      // mantem o SSE na mesma origem do app.
      '/api': {
        // Alvo configuravel: na maquina de quem desenvolve, 8080 pode estar
        // ocupada por outro projeto e o backend sobe noutra porta.
        target: process.env.API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
