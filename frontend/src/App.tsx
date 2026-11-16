import { useCallback, useEffect, useState } from 'react'
import type { Account, Entry } from './api/client'
import { ErroDaApi, fetchAccounts, fetchStatement } from './api/client'
import { sair } from './api/auth'
import { BalanceCard } from './components/BalanceCard'
import { StatementList } from './components/StatementList'
import { TransferSheet } from './components/TransferSheet'
import { Toasts } from './components/Toast'
import { useNotifications } from './hooks/useNotifications'
import { LoginScreen } from './screens/LoginScreen'
import { RegisterScreen } from './screens/RegisterScreen'

const ACOES = [
  { icone: '⚡', nome: 'Pix' },
  { icone: '⇄', nome: 'Transferir', ativa: true },
  { icone: '◫', nome: 'Pagar' },
  { icone: '＋', nome: 'Depositar' },
]

type Estado = 'carregando' | 'deslogado' | 'cadastrando' | 'logado'

export default function App() {
  const [estado, setEstado] = useState<Estado>('carregando')
  const [conta, setConta] = useState<Account | null>(null)
  const [lancamentos, setLancamentos] = useState<Entry[]>([])
  const [saldoVisivel, setSaldoVisivel] = useState(true)
  const [sheetAberto, setSheetAberto] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  const { notificacoes, conectado, descartar } = useNotifications(estado === 'logado')

  const carregar = useCallback(async () => {
    try {
      // Esta chamada e tambem o teste de sessao: 401 significa deslogado.
      const minhas = await fetchAccounts()
      const minha = minhas[0] ?? null
      setConta(minha)
      if (minha) setLancamentos(await fetchStatement(minha.id))
      setErro(null)
      setEstado('logado')
    } catch (e) {
      if (e instanceof ErroDaApi && e.code === 'SEM_SESSAO') {
        setEstado((atual) => (atual === 'cadastrando' ? atual : 'deslogado'))
        return
      }
      setErro(e instanceof Error ? e.message : 'Falha ao carregar')
    }
  }, [])

  useEffect(() => {
    void carregar()
  }, [carregar])

  // Chegou notificacao: o saldo mudou, entao recarrega.
  useEffect(() => {
    if (notificacoes.length > 0) void carregar()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [notificacoes.length])

  async function encerrarSessao() {
    try {
      await sair()
    } finally {
      setConta(null)
      setLancamentos([])
      setEstado('deslogado')
    }
  }

  if (estado === 'carregando') {
    return <div className="app"><p className="vazio">Carregando…</p></div>
  }

  if (estado === 'deslogado') {
    return (
      <LoginScreen
        aoEntrar={() => void carregar()}
        aoIrParaCadastro={() => setEstado('cadastrando')}
      />
    )
  }

  if (estado === 'cadastrando') {
    return (
      <RegisterScreen
        aoEntrar={() => void carregar()}
        aoVoltar={() => setEstado('deslogado')}
      />
    )
  }

  return (
    <div className="app">
      <Toasts notificacoes={notificacoes} aoDescartar={descartar} />

      <BalanceCard
        conta={conta}
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

      <p className="bloco-titulo">Transações</p>
      {erro ? <div className="bloco"><p className="vazio">{erro}</p></div> : <StatementList lancamentos={lancamentos} />}

      <button className="botao-texto" onClick={() => void encerrarSessao()}>
        Sair
      </button>

      {sheetAberto && conta && (
        <TransferSheet
          origem={conta}
          aoFechar={() => setSheetAberto(false)}
          aoConcluir={carregar}
        />
      )}
    </div>
  )
}
