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

type Estado = 'carregando' | 'deslogado' | 'cadastrando' | 'logado' | 'erro'

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
      // Sem isto a tela fica em "Carregando…" para sempre: `erro` so e
      // renderizado dentro da tela logada, onde ninguem chega. Backend
      // reiniciando e a primeira coisa que o visitante veria.
      //
      // Quem JA esta logado continua logado: um refresh que falhou nao
      // derruba quem esta dentro, so mostra o erro no lugar do extrato.
      setEstado((atual) => (atual === 'logado' ? atual : 'erro'))
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
    return (
      <div className="app">
        <div className="tela-auth">
          <div className="auth-caixa">
            <p className="vazio">Carregando…</p>
          </div>
        </div>
      </div>
    )
  }

  if (estado === 'erro') {
    return (
      <div className="app">
        <div className="tela-auth">
          <div className="auth-caixa">
            {/* A mensagem da API ("Falha 500") nao diz nada a quem esta
                olhando. A frase vem primeiro; o detalhe tecnico fica embaixo,
                para quem souber o que fazer com ele. */}
            <p className="vazio">Não foi possível falar com o servidor.</p>
            {erro && <p className="vazio">{erro}</p>}
            <button
              className="botao-texto"
              onClick={() => {
                setEstado('carregando')
                void carregar()
              }}
            >
              Tentar de novo
            </button>
          </div>
        </div>
      </div>
    )
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

      {/* A ordem aqui e a do celular: saldo, acoes, extrato, sair. Numa tela
          larga o `.painel` vira grid de duas colunas e reposiciona por area
          nomeada, sem mexer nesta ordem - que e tambem a ordem do Tab e a que
          um leitor de tela anuncia. */}
      <div className="painel">
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

        <section className="extrato">
          <p className="bloco-titulo">Transações</p>
          {erro ? (
            <div className="bloco"><p className="vazio">{erro}</p></div>
          ) : (
            <StatementList lancamentos={lancamentos} />
          )}
        </section>

        <button className="botao-texto sair" onClick={() => void encerrarSessao()}>
          Sair
        </button>
      </div>

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
