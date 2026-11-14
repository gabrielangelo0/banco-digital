import type { Account } from '../api/client'
import { formatarDinheiro } from './formato'

export function BalanceCard({
  conta,
  visivel,
  aoAlternar,
  conectado,
}: {
  conta: Account | null
  visivel: boolean
  aoAlternar: () => void
  conectado: boolean
}) {
  const inicial = conta?.holderName?.charAt(0).toUpperCase() ?? '?'

  return (
    <header className="topo">
      <div className="topo-linha">
        <div className="avatar">{inicial}</div>
        <button
          className="icone-olho"
          onClick={aoAlternar}
          aria-label={visivel ? 'Ocultar saldo' : 'Mostrar saldo'}
        >
          {visivel ? '◉' : '◎'}
        </button>
      </div>

      <p className="saudacao">
        Olá, {conta?.holderName ?? '...'}
        <span
          className={conectado ? 'conectado' : 'conectado off'}
          title={conectado ? 'Notificações conectadas' : 'Notificações desconectadas'}
        />
      </p>

      <p className="rotulo-saldo">Saldo disponível</p>
      <p className="saldo">
        {visivel ? (
          conta ? formatarDinheiro(conta.balance) : '—'
        ) : (
          <span className="saldo-oculto" />
        )}
      </p>
    </header>
  )
}
