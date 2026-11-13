import { useMemo, useState } from 'react'
import type { Account } from '../api/client'
import { postTransfer } from '../api/client'
import { formatarDinheiro } from './formato'

export function TransferSheet({
  origem,
  destinos,
  aoFechar,
  aoConcluir,
}: {
  origem: Account
  destinos: Account[]
  aoFechar: () => void
  aoConcluir: () => void
}) {
  const [destinoId, setDestinoId] = useState<number | ''>(destinos[0]?.id ?? '')
  const [valor, setValor] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  /**
   * A chave identifica a INTENCAO, nao o clique.
   *
   * Ela e estavel enquanto destino e valor nao mudam, entao apertar "tentar de
   * novo" depois de um erro de rede reaproveita a MESMA chave - e o servidor
   * devolve a resposta original em vez de transferir duas vezes. Mudar o valor
   * e outra intencao, e aí a chave muda junto.
   */
  const idempotencyKey = useMemo(
    () => crypto.randomUUID(),
    [destinoId, valor]
  )

  const valorValido = /^\d+([.,]\d{1,2})?$/.test(valor.trim()) && Number(valor.replace(',', '.')) > 0
  const podeEnviar = destinoId !== '' && valorValido && !enviando

  async function enviar() {
    if (!podeEnviar) return
    setEnviando(true)
    setErro(null)
    try {
      await postTransfer(
        {
          sourceAccountId: origem.id,
          targetAccountId: Number(destinoId),
          amount: valor.replace(',', '.'),
        },
        idempotencyKey
      )
      aoConcluir()
      aoFechar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível transferir')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="overlay" onClick={aoFechar}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <div className="puxador" />
        <h2>Transferir</h2>

        {erro && <div className="erro">{erro}</div>}

        <div className="campo">
          <label htmlFor="destino">Para quem</label>
          <select
            id="destino"
            value={destinoId}
            onChange={(e) => setDestinoId(Number(e.target.value))}
          >
            {destinos.map((c) => (
              <option key={c.id} value={c.id}>
                {c.holderName} · conta {c.id}
              </option>
            ))}
          </select>
        </div>

        <div className="campo">
          <label htmlFor="valor">Quanto</label>
          <input
            id="valor"
            inputMode="decimal"
            placeholder="0,00"
            value={valor}
            onChange={(e) => setValor(e.target.value)}
            autoFocus
          />
        </div>

        <div className="dica-chave">
          <strong>Idempotency-Key</strong> desta tentativa:<br />
          {idempotencyKey}
          <br />
          Tentar de novo com o mesmo valor reusa esta chave — o servidor
          devolve a resposta original em vez de transferir duas vezes.
        </div>

        <button className="botao-primario" onClick={enviar} disabled={!podeEnviar}>
          {enviando
            ? 'Transferindo...'
            : valorValido
              ? `Transferir ${formatarDinheiro(valor.replace(',', '.'))}`
              : 'Transferir'}
        </button>
        <button className="botao-texto" onClick={aoFechar}>
          Cancelar
        </button>
      </div>
    </div>
  )
}
