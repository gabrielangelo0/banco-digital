import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Account, Entry } from './api/client'
import { ErroDaApi, fetchAccounts, fetchStatement } from './api/client'
import { sair } from './api/auth'
import { CartaoDaConta } from './components/BalanceCard'
import { Atalhos } from './components/Atalhos'
import { ResumoFinanceiro } from './components/Grafico'
import { Destaques } from './components/Destaques'
import { BarraDeAbas, Lateral } from './components/Navegacao'
import { Razao } from './components/StatementList'
import { TransferSheet } from './components/TransferSheet'
import { Toasts } from './components/Toast'
import { Icone } from './components/Icone'
import { resumoDoMes } from './components/formato'
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
  const [filtro, setFiltro] = useState('')
  const [secao, setSecao] = useState<'inicio' | 'extrato'>('inicio')
  const [aviso, setAviso] = useState(false)

  const extratoRef = useRef<HTMLDivElement>(null)

  const { notificacoes, conectado, descartar } = useNotifications(estado === 'logado')
  const novos = useLancamentosNovos(lancamentos)

  // Somado em unidades inteiras, nao em ponto flutuante. Ver `somar`.
  const resumo = useMemo(() => resumoDoMes(lancamentos), [lancamentos])

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

  // O aviso de "isto e so demonstracao" some sozinho; ele nao pede decisao
  // nenhuma de quem clicou.
  useEffect(() => {
    if (!aviso) return
    const t = setTimeout(() => setAviso(false), 4500)
    return () => clearTimeout(t)
  }, [aviso])

  const navegar = useCallback((acao: 'inicio' | 'extrato' | 'transferir') => {
    if (acao === 'transferir') {
      setCaixaAberta(true)
      return
    }
    setSecao(acao)
    const alvo = acao === 'extrato' ? extratoRef.current : null
    const menosMovimento =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const comportamento: ScrollBehavior = menosMovimento ? 'auto' : 'smooth'
    if (alvo) alvo.scrollIntoView({ behavior: comportamento, block: 'start' })
    else window.scrollTo({ top: 0, behavior: comportamento })
  }, [])

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

  const inicial = (conta?.holderName ?? '?').charAt(0).toUpperCase()
  const avisar = () => setAviso(true)

  return (
    <div className="app logado">
      <Toasts notificacoes={notificacoes} aoDescartar={descartar} />

      {aviso && (
        <div className="toasts toasts-aviso">
          <div className="toast aviso" role="status">
            <div className="toast-icone">
              <Icone nome="escudo" tamanho={18} />
            </div>
            <div>
              <div className="toast-titulo">Esta parte é só demonstração</div>
              <div className="toast-texto">
                O que funciona de verdade aqui é a transferência entre contas.
              </div>
            </div>
          </div>
        </div>
      )}

      <Lateral
        ativo={secao}
        aoNavegar={navegar}
        aoAvisar={avisar}
        aoSair={() => void encerrarSessao()}
      />

      <div className="painel">
        <header className="topo">
          <div className="topo-quem">
            <span className="avatar" aria-hidden="true">
              {inicial}
            </span>
            <div>
              <p className="topo-saudacao">
                Olá, <strong>{conta?.holderName ?? '—'}</strong>!
              </p>
              <p className="topo-conta">conta {conta?.id ?? '—'}</p>
            </div>
          </div>

          {/* A busca da referencia, ligada no que existe: ela filtra o extrato
              por tipo, valor ou identificador. Uma caixa de busca que nao
              busca seria o enfeite mais caro da tela. */}
          <label className="busca">
            <Icone nome="busca" tamanho={18} />
            <input
              type="search"
              value={filtro}
              placeholder="Buscar no extrato: valor, tipo ou identificador…"
              onChange={(e) => setFiltro(e.target.value)}
              aria-label="Buscar no extrato"
            />
          </label>

          <span
            className={conectado ? 'sino' : 'sino off'}
            role="img"
            aria-label={conectado ? 'Notificações conectadas' : 'Notificações desconectadas'}
          >
            <Icone nome="sino" tamanho={20} />
            <span className="sino-ponto" />
          </span>

          <button
            className="topo-sair"
            onClick={() => void encerrarSessao()}
            aria-label="Sair da conta"
          >
            <Icone nome="sair" tamanho={20} />
          </button>
        </header>

        {/* A ordem e a do celular: cartao, atalhos, movimentacoes, resumo,
            destaques. Numa tela larga o grid reposiciona em duas colunas sem
            mexer nesta ordem - que e tambem a ordem do Tab e a de um leitor
            de tela. */}
        <main className="grade">
          <div className="area-cartao">
            <CartaoDaConta
              conta={conta}
              visivel={saldoVisivel}
              aoAlternar={() => setSaldoVisivel((v) => !v)}
              entradasDoMes={resumo.entradas}
              aoVerExtrato={() => navegar('extrato')}
            />
          </div>

          <div className="area-atalhos">
            <Atalhos
              aoTransferir={() => setCaixaAberta(true)}
              aoAvisar={avisar}
              habilitado={Boolean(conta)}
            />
          </div>

          <div className="area-extrato" ref={extratoRef}>
            {erro ? (
              <section className="cartao">
                <p className="vazio">{erro}</p>
              </section>
            ) : (
              <Razao
                lancamentos={lancamentos}
                novos={novos}
                visivel={saldoVisivel}
                filtro={filtro}
              />
            )}
          </div>

          <div className="area-resumo">
            <ResumoFinanceiro
              lancamentos={lancamentos}
              resumo={resumo}
              visivel={saldoVisivel}
            />
          </div>

          <Destaques aoAvisar={avisar} />
        </main>
      </div>

      <BarraDeAbas ativo={secao} aoNavegar={navegar} aoAvisar={avisar} />

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
