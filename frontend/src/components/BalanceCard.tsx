import { useEffect, useRef, useState } from 'react'
import type { Account } from '../api/client'
import { formatarDinheiro } from './formato'
import { Icone } from './Icone'

/**
 * O saldo anda ate o valor novo em vez de trocar de uma vez.
 *
 * E a unica animacao nao pedida da tela, e existe porque RESPONDE a uma
 * mudanca: quando a notificacao chega, o numero mudar sozinho sem transicao
 * nao se distingue de um numero que sempre esteve ali.
 *
 * O quadro final recebe o valor do servidor, nao o resultado da interpolacao:
 * o caminho e ponto flutuante, o destino nao.
 */
function useSaldoAnimado(alvo: number): number {
  const [valor, setValor] = useState(alvo)
  const anterior = useRef(alvo)

  useEffect(() => {
    const partida = anterior.current
    anterior.current = alvo
    if (partida === alvo) return

    const menosMovimento =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (menosMovimento) {
      setValor(alvo)
      return
    }

    const inicio = performance.now()
    const duracao = 650
    let quadro = 0

    const passo = (agora: number) => {
      const t = Math.min(1, (agora - inicio) / duracao)
      if (t >= 1) {
        setValor(alvo)
        return
      }
      const suave = 1 - Math.pow(1 - t, 3)
      setValor(partida + (alvo - partida) * suave)
      quadro = requestAnimationFrame(passo)
    }

    quadro = requestAnimationFrame(passo)
    return () => cancelAnimationFrame(quadro)
  }, [alvo])

  return valor
}

/**
 * O cartao escuro do topo.
 *
 * A forma e a de um cartao fisico porque e o que a referencia pede, mas o que
 * esta impresso nele e a conta de verdade: numero, titular e saldo do
 * servidor. Nao ha numero de cartao nem bandeira - seria o unico dado falso da
 * tela inteira.
 */
export function CartaoDaConta({
  conta,
  visivel,
  aoAlternar,
  entradasDoMes,
  aoVerExtrato,
}: {
  conta: Account | null
  visivel: boolean
  aoAlternar: () => void
  entradasDoMes: string
  aoVerExtrato: () => void
}) {
  const saldo = useSaldoAnimado(Number(conta?.balance ?? 0))
  const entrou = Number(entradasDoMes) > 0

  return (
    <section className="cartao-conta" aria-label="Saldo da conta">
      <div className="cartao-topo">
        <span className="cartao-selo" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor">
            <path d="M20.5 3.5C12 3 6.5 6.4 5 12.2c-.6 2.3-.3 4.6.6 6.4l-1.9 2.6 1.7 1.2 1.9-2.5c1.7 1 3.9 1.3 6 .7 5.8-1.6 8.1-7.6 7.2-17.1zM9 16.6c1.2-3.6 3.6-6.2 7-7.8-2.5 2.1-4.3 4.8-5.3 8.1-.6.1-1.2.1-1.7-.3z" />
          </svg>
        </span>
        <span className="cartao-tipo">Conta corrente</span>

        <span className="cartao-fisico" aria-hidden="true">
          <span className="cartao-chip" />
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <path d="M7 8.5a7 7 0 0 1 0 7M11 6a11 11 0 0 1 0 12M15 3.5a15 15 0 0 1 0 17" />
          </svg>
        </span>
      </div>

      <p className="cartao-rotulo">Saldo disponível</p>

      <p className="saldo-linha">
        <span className="saldo dinheiro">
          {visivel ? formatarDinheiro(saldo) : <span className="saldo-oculto" />}
        </span>
        <button
          className="olho"
          onClick={aoAlternar}
          aria-label={visivel ? 'Ocultar saldo' : 'Mostrar saldo'}
        >
          <Icone nome={visivel ? 'olho' : 'olho-fechado'} tamanho={22} />
        </button>
      </p>

      {/* So aparece se houve entrada no mes. Um "+ R$ 0,00 este mes" fixo
          embaixo do saldo e enfeite com cara de dado. */}
      {entrou && (
        <p className="cartao-variacao dinheiro">
          <span aria-hidden="true">↗</span> {visivel ? `+ ${formatarDinheiro(entradasDoMes)}` : '•••'}{' '}
          <span className="cartao-variacao-nota">entrou este mês</span>
        </p>
      )}

      {/* O atalho para o extrato so aparece em tela larga; no celular as abas
          de baixo levam ao mesmo lugar, e um botao a mais dentro do cartao
          roubaria a altura do saldo. */}
      <button className="cartao-acao" onClick={aoVerExtrato}>
        Ver extrato completo <Icone nome="seta" tamanho={17} />
      </button>

      <div className="cartao-base">
        <span className="cartao-titular">{conta?.holderName ?? '—'}</span>
        <span className="cartao-numero">conta {conta?.id ?? '—'}</span>
      </div>
    </section>
  )
}
