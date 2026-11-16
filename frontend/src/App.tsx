import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Account, Entry } from './api/client'
import { ErroDaApi, fetchAccounts, fetchStatement } from './api/client'
import { sair } from './api/auth'
import { PainelSaldo } from './components/BalanceCard'
import { Razao } from './components/StatementList'
import { TransferSheet } from './components/TransferSheet'
import { Toasts } from './components/Toast'
import { somar } from './components/formato'
import { useNotifications } from './hooks/useNotifications'
import { LoginScreen } from './screens/LoginScreen'
import { RegisterScreen } from './screens/RegisterScreen'

type Estado = 'carregando' | 'deslogado' | 'cadastrando' | 'logado' | 'erro'

/**
 * Marca os lancamentos que chegaram DEPOIS da primeira carga.
 *
 * Sem isto, a lista inteira acenderia ao abrir o app: o destaque perderia o
 * sentido justamente no caso em que ele importa, que e dinheiro entrando
 * enquanto a tela ja esta aberta.
 */
function useLancamentosNovos(lancamentos: Entry[]): Set<number> {
  const vistos = useRef<Set<number> | null>(null)
  const [novos, setNovos] = useState<Set<number>>(new Set())

  useEffect(() => {
    const ids = new Set(lancamentos.map((l) => l.id))
    if (vistos.current === null) {
      vistos.current = ids
      return
    }
    const recentes = [...ids].filter((id) => !vistos.current!.has(id))
    vistos.current = ids
    if (recentes.length > 0) setNovos(new Set(recentes))
  }, [lancamentos])

  return novos
}

export default function App() {
  const [estado, setEstado] = useState<Estado>('carregando')
  const [conta, setConta] = useState<Account | null>(null)
  const [lancamentos, setLancamentos] = useState<Entry[]>([])
  const [saldoVisivel, setSaldoVisivel] = useState(true)
  const [caixaAberta, setCaixaAberta] = useState(false)
  const [erro, setErro] = useState<string | null>(null)

  const { notificacoes, conectado, descartar } = useNotifications(estado === 'logado')
  const novos = useLancamentosNovos(lancamentos)

  // Somado em unidades inteiras, nao em ponto flutuante. Ver `somar`.
  const entradas = useMemo(
    () => somar(lancamentos.filter((l) => l.direction === 'CREDIT').map((l) => l.amount)),
    [lancamentos]
  )
  const saidas = useMemo(
    () => somar(lancamentos.filter((l) => l.direction === 'DEBIT').map((l) => l.amount)),
    [lancamentos]
  )

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
      // Sem isto a tela fica em "Carregando..." para sempre: `erro` so e
      // renderizado dentro da tela logada, onde ninguem chega.
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
        <p className="carregando">Carregando…</p>
      </div>
    )
  }

  if (estado === 'erro') {
    return (
      <div className="app">
        <div className="entrada-corpo">
          <div className="formulario">
            {/* A frase vem primeiro; "Falha 500" nao diz nada a quem esta
                olhando, entao o detalhe tecnico fica embaixo. */}
            <h2>O servidor não respondeu.</h2>
            <p className="campo-nota">
              {erro}. Isso costuma ser o backend reiniciando — tente de novo em alguns
              segundos.
            </p>
            <button
              className="botao-primario"
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

      <header className="barra">
        <div className="marca">
          <div className="selo" aria-hidden="true">
            B
          </div>
          <span className="marca-nome">Banco</span>
        </div>

        <div className="barra-conta">
          <div className="barra-nome">{conta?.holderName ?? '—'}</div>
          <div className="barra-numero">conta {conta?.id ?? '—'}</div>
        </div>

        <span
          className={conectado ? 'pulso' : 'pulso off'}
          role="img"
          aria-label={
            conectado ? 'Notificações conectadas' : 'Notificações desconectadas'
          }
        />

        <button className="botao-fantasma" onClick={() => void encerrarSessao()}>
          Sair
        </button>
      </header>

      {/* Pix, pagar e depositar nao estao aqui de proposito. Tres botoes
          desligados imitando um banco de verdade custam atencao e nao
          entregam nada; o app faz uma coisa, e essa coisa e o botao do
          painel.

          A ordem e a do celular: saldo, depois extrato. Numa tela larga o
          grid reposiciona por area nomeada, sem mexer nesta ordem - que e
          tambem a ordem do Tab e a de um leitor de tela. */}
      <main className="conteudo">
        <PainelSaldo
          conta={conta}
          visivel={saldoVisivel}
          aoAlternar={() => setSaldoVisivel((v) => !v)}
          entradas={entradas}
          saidas={saidas}
          aoTransferir={() => setCaixaAberta(true)}
        />

        <section className="extrato">
          <div className="extrato-topo">
            <h2>Extrato</h2>
            <span className="extrato-contagem">
              {lancamentos.length === 1 ? '1 lançamento' : `${lancamentos.length} lançamentos`}
            </span>
          </div>

          {erro ? (
            <div className="razao">
              <p className="vazio">{erro}</p>
            </div>
          ) : (
            <Razao lancamentos={lancamentos} novos={novos} />
          )}
        </section>
      </main>

      {caixaAberta && conta && (
        <TransferSheet
          origem={conta}
          aoFechar={() => setCaixaAberta(false)}
          aoConcluir={carregar}
        />
      )}
    </div>
  )
}
