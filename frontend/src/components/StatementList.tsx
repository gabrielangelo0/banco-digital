import type { Entry } from '../api/client'
import { formatarData, formatarDinheiro } from './formato'

export function StatementList({ lancamentos }: { lancamentos: Entry[] }) {
  if (lancamentos.length === 0) {
    return (
      <div className="bloco">
        <p className="vazio">Nenhuma movimentação ainda.</p>
      </div>
    )
  }

  return (
    <div className="bloco">
      {lancamentos.map((lancamento) => {
        const entrada = lancamento.direction === 'CREDIT'
        return (
          <div className="lancamento" key={lancamento.id}>
            <div className={entrada ? 'seta entrada' : 'seta saida'}>{entrada ? '↓' : '↑'}</div>
            <div className="lancamento-info">
              <div className="lancamento-titulo">
                {entrada ? 'Transferência recebida' : 'Transferência enviada'}
              </div>
              <div className="lancamento-data">{formatarData(lancamento.createdAt)}</div>
            </div>
            <div className={entrada ? 'lancamento-valor entrada' : 'lancamento-valor'}>
              {entrada ? '+' : '−'} {formatarDinheiro(lancamento.amount)}
            </div>
          </div>
        )
      })}
    </div>
  )
}
