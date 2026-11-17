import { formatarDinheiro, gastoPorDia, type ResumoDoMes } from './formato'
import type { Entry } from '../api/client'

/**
 * O resumo do mes, com as barras dos ultimos sete dias.
 *
 * Toda altura aqui sai do extrato que o servidor mandou. Quando nao houve
 * gasto nenhum, as barras ficam rentes ao chao em vez de o grafico inventar
 * uma escala: o vazio tambem e informacao.
 */
export function ResumoFinanceiro({
  lancamentos,
  resumo,
  visivel,
}: {
  lancamentos: Entry[]
  resumo: ResumoDoMes
  visivel: boolean
}) {
  const barras = gastoPorDia(lancamentos)
  const teto = Math.max(...barras.map((b) => b.valor), 0)

  return (
    <section className="cartao resumo-cartao" aria-label="Resumo financeiro">
      <div className="cartao-cabeca">
        <h2>Seu resumo financeiro</h2>
        <span className="cartao-etiqueta">Este mês</span>
      </div>

      <p className="resumo-numero dinheiro">
        {visivel ? formatarDinheiro(resumo.saidas) : '••••••'}
      </p>
      <p className="resumo-legenda">
        {resumo.variacao === null ? (
          'saiu da conta este mês'
        ) : (
          <>
            <span className={resumo.variacao > 0 ? 'variacao sobe' : 'variacao desce'}>
              <span aria-hidden="true">{resumo.variacao > 0 ? '↗' : '↘'}</span>{' '}
              {Math.abs(resumo.variacao)}%
            </span>{' '}
            em relação ao mês passado
          </>
        )}
      </p>

      <div className="barras" role="img" aria-label={`Saídas por dia nos últimos ${barras.length} dias`}>
        {barras.map((b) => (
          <div className="barra-coluna" key={b.dia}>
            <div className="barra-trilho">
              <div
                className={b.valor > 0 ? 'barra cheia' : 'barra'}
                style={{ height: teto > 0 ? `${Math.max(3, (b.valor / teto) * 100)}%` : '3%' }}
              >
                {b.valor > 0 && (
                  <span className="barra-dica dinheiro">{formatarDinheiro(b.valor)}</span>
                )}
              </div>
            </div>
            <span className="barra-rotulo">{b.rotulo}</span>
          </div>
        ))}
      </div>
    </section>
  )
}
