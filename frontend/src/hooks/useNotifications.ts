import { useEffect, useRef, useState } from 'react'

export type Notificacao = {
  id: string
  transferId: string
  direction: 'DEBIT' | 'CREDIT'
  amount: string | number
  counterpartAccountId: number
}

/**
 * Escuta as notificacoes da conta por Server-Sent Events.
 *
 * SSE em vez de WebSocket: o fluxo e de mao unica e o EventSource do navegador
 * ja reconecta sozinho quando a conexao cai.
 */
export function useNotifications(ativo: boolean) {
  const [notificacoes, setNotificacoes] = useState<Notificacao[]>([])
  const [conectado, setConectado] = useState(false)
  const contador = useRef(0)

  useEffect(() => {
    if (!ativo) return

    // Sem `accountId` na URL: a conta vem da sessao. Antes, trocar o numero
    // aqui assinava o fluxo de qualquer conta.
    const fonte = new EventSource('/api/notifications/stream')

    fonte.addEventListener('ready', () => setConectado(true))
    fonte.addEventListener('transfer', (evento) => {
      const dados = JSON.parse((evento as MessageEvent).data)
      contador.current += 1
      setNotificacoes((atuais) => [
        { id: `n${contador.current}`, ...dados },
        ...atuais,
      ])
    })
    fonte.onerror = () => setConectado(false)

    return () => fonte.close()
  }, [ativo])

  const descartar = (id: string) =>
    setNotificacoes((atuais) => atuais.filter((n) => n.id !== id))

  return { notificacoes, conectado, descartar }
}
