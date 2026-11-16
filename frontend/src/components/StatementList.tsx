import type { Entry } from '../api/client'
import { formatarDinheiro, formatarHora, rotuloDoDia } from './formato'

type Dia = { rotulo: string; lancamentos: Entry[] }

/**
 * Agrupa por dia preservando a ordem que o servidor mandou (mais recente
 * primeiro). Um extrato corrido, sem o dia, obriga quem le a decifrar a data
 * de cada linha para saber se aquilo foi hoje.
 */
function porDia(lancamentos: Entry[]): Dia[] {
  const dias: Dia[] = []
  for (const lancamento of lancamentos) {
    const rotulo = rotuloDoDia(lancamento.createdAt)
    const ultimo = dias[dias.length - 1]
    if (ultimo && ultimo.rotulo === rotulo) {
      ultimo.lancamentos.push(lancamento)
    } else {
      dias.push({ rotulo, lancamentos: [lancamento] })
    }
  }
  return dias
}

export function Razao({ lancamentos, novos }: { lancamentos: Entry[]; novos: Set<number> }) {
  if (lancamentos.length === 0) {
    return (
      <div className="razao">
        <p className="vazio">
          <strong>Nada se moveu ainda.</strong>
          Transfira para a outra conta de demonstração e o lançamento aparece aqui no
          mesmo instante.
        </p>
      </div>
    )
  }

  return (
    <div className="razao">
      {porDia(lancamentos).map((dia) => (
        <div className="dia" key={dia.rotulo}>
          <h3 className="dia-titulo">{dia.rotulo}</h3>

          {dia.lancamentos.map((lancamento) => {
            const entrada = lancamento.direction === 'CREDIT'
            return (
              <div
                className={novos.has(lancamento.id) ? 'lancamento novo' : 'lancamento'}
                key={lancamento.id}
              >
                <div className={entrada ? 'lancamento-icone credito' : 'lancamento-icone'}>
                  {entrada ? '↓' : '↑'}
                </div>

                <div className="lancamento-corpo">
                  <div className="lancamento-titulo">
                    {entrada ? 'Transferência recebida' : 'Transferência enviada'}
                  </div>
                  <div className="lancamento-hora">{formatarHora(lancamento.createdAt)}</div>
                </div>

                {/* O identificador da transferencia e o que liga esta linha ao
                    log do servidor e ao par dela na outra conta. Aparece so
                    onde ha largura sobrando; no celular ele roubaria espaco
                    do que importa. */}
                <code className="lancamento-id" title={lancamento.transferId}>
                  {lancamento.transferId.slice(0, 8)}
                </code>

                <div className={entrada ? 'lancamento-valor credito dinheiro' : 'lancamento-valor dinheiro'}>
                  {entrada ? '+' : '−'} {formatarDinheiro(lancamento.amount)}
                </div>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}
