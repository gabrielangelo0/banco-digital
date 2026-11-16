import { useEffect, useRef, useState } from 'react'
import type { Account } from '../api/client'
import { formatarDinheiro } from './formato'

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

export function PainelSaldo({
  conta,
  visivel,
  aoAlternar,
  entradas,
  saidas,
  aoTransferir,
}: {
  conta: Account | null
  visivel: boolean
  aoAlternar: () => void
  entradas: string
  saidas: string
  aoTransferir: () => void
}) {
  const saldo = useSaldoAnimado(Number(conta?.balance ?? 0))

  return (
    <section className="heroi" aria-label="Saldo da conta">
      <div>
        <p className="heroi-rotulo">
          Saldo disponível
          <button
            className="olho"
            onClick={aoAlternar}
            aria-label={visivel ? 'Ocultar saldo' : 'Mostrar saldo'}
          >
            {visivel ? '◉' : '◎'}
          </button>
        </p>

        <p className="saldo dinheiro">
          {visivel ? formatarDinheiro(saldo) : <span className="saldo-oculto" />}
        </p>

        <div className="resumo">
          <div>
            <p className="resumo-rotulo">Entrou</p>
            <p className="resumo-valor credito dinheiro">
              {visivel ? formatarDinheiro(entradas) : '•••'}
            </p>
          </div>
          <div>
            <p className="resumo-rotulo">Saiu</p>
            <p className="resumo-valor dinheiro">
              {visivel ? formatarDinheiro(saidas) : '•••'}
            </p>
          </div>
        </div>
      </div>

      <button className="cta" onClick={aoTransferir} disabled={!conta}>
        Transferir
      </button>
    </section>
  )
}
