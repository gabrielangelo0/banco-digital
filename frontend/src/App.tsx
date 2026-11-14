import { useCallback, useEffect, useState } from 'react'
import type { Account, Entry } from './api/client'
import { fetchAccounts, fetchStatement } from './api/client'
import { BalanceCard } from './components/BalanceCard'
import { StatementList } from './components/StatementList'
import { TransferSheet } from './components/TransferSheet'
import { Toasts } from './components/Toast'
import { useNotifications } from './hooks/useNotifications'

const ACOES = [
  { icone: '⚡', nome: 'Pix' },
  { icone: '⇄', nome: 'Transferir', ativa: true },
  { icone: '◫', nome: 'Pagar' },
  { icone: '＋', nome: 'Depositar' },
]

export default function App() {
  const [contas, setContas] = useState<Account[]>([])
  const [contaAtualId, setContaAtualId] = useState<number | null>(null)
  const [lancamentos, setLancamentos] = useState<Entry[]>([])
  const [saldoVisivel, setSaldoVisivel] = useState(true)
  const [sheetAberto, setSheetAberto] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  const { notificacoes, conectado, descartar } = useNotifications(contaAtualId)

  const carregar = useCallback(async () => {
    try {
      const todas = await fetchAccounts()
      setContas(todas)
      const atual = contaAtualId ?? todas[0]?.id ?? null
      setContaAtualId(atual)
      if (atual != null) setLancamentos(await fetchStatement(atual))
      setErro(null)
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Falha ao carregar')
    }
  }, [contaAtualId])

  useEffect(() => {
    void carregar()
  }, [carregar])

  // Chegou notificacao: o saldo mudou, entao recarrega.
  useEffect(() => {
    if (notificacoes.length > 0) void carregar()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notificacoes.length])

  const contaAtual = contas.find((c) => c.id === contaAtualId) ?? null
  const destinos = contas.filter((c) => c.id !== contaAtualId)

  return (
    <div className="app">
      <Toasts notificacoes={notificacoes} aoDescartar={descartar} />

      <BalanceCard
        conta={contaAtual}
        visivel={saldoVisivel}
        aoAlternar={() => setSaldoVisivel((v) => !v)}
        conectado={conectado}
      />

      <div className="acoes">
        {ACOES.map((a) => (
          <button
            key={a.nome}
            className="acao"
            disabled={!a.ativa}
            onClick={() => a.ativa && setSheetAberto(true)}
          >
            <span className="acao-icone">{a.icone}</span>
            {a.nome}
          </button>
        ))}
      </div>

      {contas.length > 1 && (
        <>
          <p className="bloco-titulo">Trocar de conta (demo)</p>
          <div className="bloco">
            {contas.map((c) => (
              <button
                key={c.id}
                className="lancamento"
                style={{ width: '100%', textAlign: 'left' }}
                onClick={() => setContaAtualId(c.id)}
              >
                <div className="seta entrada" style={{ background: 'rgba(130,10,209,.1)', color: 'var(--roxo)' }}>
                  {c.holderName.charAt(0)}
                </div>
                <div className="lancamento-info">
                  <div className="lancamento-titulo">{c.holderName}</div>
                  <div className="lancamento-data">conta {c.id}</div>
                </div>
                {c.id === contaAtualId && <span style={{ color: 'var(--roxo)' }}>●</span>}
              </button>
            ))}
          </div>
        </>
      )}

      <p className="bloco-titulo">Transações</p>
      {erro ? <div className="bloco"><p className="vazio">{erro}</p></div> : <StatementList lancamentos={lancamentos} />}

      {sheetAberto && contaAtual && (
        <TransferSheet
          origem={contaAtual}
          destinos={destinos}
          aoFechar={() => setSheetAberto(false)}
          aoConcluir={carregar}
        />
      )}
    </div>
  )
}
