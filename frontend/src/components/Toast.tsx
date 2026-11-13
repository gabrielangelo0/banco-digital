import { useEffect } from 'react'
import type { Notificacao } from '../hooks/useNotifications'
import { formatarDinheiro } from './formato'

function Toast({ notificacao, aoFechar }: { notificacao: Notificacao; aoFechar: () => void }) {
  useEffect(() => {
    const t = setTimeout(aoFechar, 6000)
    return () => clearTimeout(t)
  }, [aoFechar])

  const entrou = notificacao.direction === 'CREDIT'

  return (
    <div className="toast" role="status">
      <div className="toast-icone">{entrou ? '↓' : '↑'}</div>
      <div>
        <div className="toast-titulo">
          {entrou ? 'Você recebeu' : 'Transferência enviada'} {formatarDinheiro(notificacao.amount)}
        </div>
        <div className="toast-texto">
          {entrou ? 'de' : 'para'} conta {notificacao.counterpartAccountId}
        </div>
      </div>
    </div>
  )
}

export function Toasts({
  notificacoes,
  aoDescartar,
}: {
  notificacoes: Notificacao[]
  aoDescartar: (id: string) => void
}) {
  return (
    <div className="toasts">
      {notificacoes.slice(0, 3).map((n) => (
        <Toast key={n.id} notificacao={n} aoFechar={() => aoDescartar(n.id)} />
      ))}
    </div>
  )
}
