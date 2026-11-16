import { useEffect, useMemo, useState } from 'react'
import type { Account } from '../api/client'
import { ErroDaApi, postTransfer } from '../api/client'
import { formatarDinheiro } from './formato'

export function TransferSheet({
  origem,
  aoFechar,
  aoConcluir,
}: {
  origem: Account
  aoFechar: () => void
  aoConcluir: () => void
}) {
  // Numero da conta digitado, e nao lista: nao existe diretorio de contas, e
  // um endpoint de busca revelaria quem tem cadastro aqui.
  const [destino, setDestino] = useState('')
  const [valor, setValor] = useState('')
  const [senhaTransacional, setSenhaTransacional] = useState('')
  const [enviando, setEnviando] = useState(false)
  const [erroGeral, setErroGeral] = useState<string | null>(null)
  const [erroDestino, setErroDestino] = useState<string | null>(null)
  const [erroSenha, setErroSenha] = useState<string | null>(null)

  /**
   * A chave identifica a INTENCAO, nao o clique.
   *
   * Ela e estavel enquanto destino e valor nao mudam, entao apertar "tentar de
   * novo" depois de um erro de rede reaproveita a MESMA chave - e o servidor
   * devolve a resposta original em vez de transferir duas vezes.
   */
  const idempotencyKey = useMemo(() => crypto.randomUUID(), [destino, valor])

  // Esc fecha. No celular existe o gesto e o toque fora; no teclado, sem isto
  // a unica saida e achar o botao Cancelar com o Tab.
  useEffect(() => {
    function aoTeclar(e: KeyboardEvent) {
      if (e.key === 'Escape') aoFechar()
    }
    window.addEventListener('keydown', aoTeclar)
    return () => window.removeEventListener('keydown', aoTeclar)
  }, [aoFechar])

  const destinoValido = /^\d+$/.test(destino.trim())
  const valorValido =
    /^\d+([.,]\d{1,2})?$/.test(valor.trim()) && Number(valor.replace(',', '.')) > 0
  const podeEnviar = destinoValido && valorValido && senhaTransacional.length > 0 && !enviando

  async function enviar() {
    if (!podeEnviar) return
    setEnviando(true)
    setErroGeral(null)
    setErroDestino(null)
    setErroSenha(null)
    try {
      await postTransfer(
        {
          targetAccountId: Number(destino),
          amount: valor.replace(',', '.'),
          transactionPassword: senhaTransacional,
        },
        idempotencyKey
      )
      aoConcluir()
      aoFechar()
    } catch (e) {
      // O erro vai PARA O CAMPO que o causou. "Nao foi possivel transferir"
      // sem dizer onde esta o problema e a critica mais votada do app que
      // serviu de referencia.
      if (e instanceof ErroDaApi && e.code === 'SENHA_TRANSACIONAL_INVALIDA') {
        setErroSenha(e.message)
      } else if (e instanceof ErroDaApi && e.code === 'CONTA_NAO_ENCONTRADA') {
        setErroDestino(e.message)
      } else {
        setErroGeral(e instanceof Error ? e.message : 'Não foi possível transferir')
      }
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="overlay" onClick={aoFechar}>
      <div
        className="caixa"
        role="dialog"
        aria-modal="true"
        aria-labelledby="titulo-transferir"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="puxador" />

        <h2 id="titulo-transferir">Transferir</h2>
        <p className="caixa-sub">Saindo da conta {origem.id}</p>

        {erroGeral && <div className="erro">{erroGeral}</div>}

        <form
          onSubmit={(e) => {
            e.preventDefault()
            void enviar()
          }}
        >
          <div className="campo">
            <label htmlFor="destino">Conta de destino</label>
            <input
              id="destino"
              inputMode="numeric"
              placeholder="ex.: 2"
              value={destino}
              onChange={(e) => setDestino(e.target.value)}
              autoFocus
            />
            {erroDestino && <div className="erro">{erroDestino}</div>}
          </div>

          <div className="campo">
            <label htmlFor="valor">Quanto</label>
            <input
              id="valor"
              inputMode="decimal"
              placeholder="0,00"
              value={valor}
              onChange={(e) => setValor(e.target.value)}
            />
          </div>

          <div className="campo">
            <label htmlFor="senha-transacional">Senha transacional</label>
            <input
              id="senha-transacional"
              type="password"
              placeholder="••••••"
              value={senhaTransacional}
              onChange={(e) => setSenhaTransacional(e.target.value)}
            />
            {erroSenha && <div className="erro">{erroSenha}</div>}
            <p className="campo-nota">
              Entrar prova quem você é. Mover dinheiro pede a segunda senha.
            </p>
          </div>

          <div className="chave">
            Chave de idempotência desta tentativa
            <code>{idempotencyKey}</code>
            Tentar de novo com o mesmo destino e valor reusa esta chave, e o servidor
            devolve a resposta original em vez de transferir duas vezes.
          </div>

          <button className="botao-primario" type="submit" disabled={!podeEnviar}>
            {enviando
              ? 'Transferindo...'
              : valorValido
                ? `Transferir ${formatarDinheiro(valor.replace(',', '.'))}`
                : 'Transferir'}
          </button>
        </form>

        <button className="botao-texto" onClick={aoFechar}>
          Cancelar
        </button>
      </div>
    </div>
  )
}
