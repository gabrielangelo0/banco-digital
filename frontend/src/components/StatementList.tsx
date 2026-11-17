import { useState } from 'react'
import type { Entry } from '../api/client'
import { formatarDinheiro, quando } from './formato'
import { Icone } from './Icone'

const PREVIA = 5

export function Razao({
  lancamentos,
  novos,
  visivel,
  filtro,
}: {
  lancamentos: Entry[]
  novos: Set<number>
  visivel: boolean
  filtro: string
}) {
  const [tudo, setTudo] = useState(false)

  const busca = filtro.trim().toLowerCase()
  const filtrados = busca
    ? lancamentos.filter((l) => {
        const rotulo = l.direction === 'CREDIT' ? 'transferência recebida' : 'transferência enviada'
        return (
          rotulo.includes(busca) ||
          l.transferId.toLowerCase().includes(busca) ||
          String(l.amount).replace('.', ',').includes(busca.replace('.', ','))
        )
      })
    : lancamentos

  const mostrados = tudo || busca ? filtrados : filtrados.slice(0, PREVIA)

  return (
    <section className="cartao" aria-label="Movimentações">
      <div className="cartao-cabeca">
        <span className="cartao-cabeca-titulo">
          <span className="cabeca-icone" aria-hidden="true">
            <Icone nome="historico" tamanho={19} />
          </span>
          <h2>{busca ? 'Resultados' : 'Últimas movimentações'}</h2>
        </span>

        {filtrados.length > PREVIA && !busca && (
          <button className="cartao-link" onClick={() => setTudo((t) => !t)}>
            {tudo ? 'Ver menos' : 'Ver todas'} <Icone nome="seta" tamanho={16} />
          </button>
        )}
      </div>

      {mostrados.length === 0 ? (
        <p className="vazio">
          {busca ? (
            <>
              <strong>Nada encontrado.</strong>A busca olha o tipo, o valor e o
              identificador do lançamento.
            </>
          ) : (
            <>
              <strong>Nada se moveu ainda.</strong>Transfira para a outra conta de
              demonstração e o lançamento aparece aqui no mesmo instante.
            </>
          )}
        </p>
      ) : (
        <ul className="lancamentos">
          {mostrados.map((l) => {
            const entrada = l.direction === 'CREDIT'
            return (
              <li
                className={novos.has(l.id) ? 'lancamento novo' : 'lancamento'}
                key={l.id}
              >
                <span className={entrada ? 'lancamento-icone credito' : 'lancamento-icone'}>
                  <Icone nome="transferir" tamanho={19} />
                </span>

                <span className="lancamento-corpo">
                  <span className="lancamento-titulo">
                    {entrada ? 'Transferência recebida' : 'Transferência enviada'}
                  </span>
                  {/* O identificador e o que liga esta linha ao log do servidor
                      e ao par dela na outra conta. */}
                  <code className="lancamento-id" title={l.transferId}>
                    #{l.transferId.slice(0, 8)}
                  </code>
                </span>

                <span className="lancamento-lado">
                  <span
                    className={
                      entrada ? 'lancamento-valor credito dinheiro' : 'lancamento-valor dinheiro'
                    }
                  >
                    {visivel ? `${entrada ? '+' : '−'} ${formatarDinheiro(l.amount)}` : '•••'}
                  </span>
                  <span className="lancamento-hora">{quando(l.createdAt)}</span>
                </span>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
