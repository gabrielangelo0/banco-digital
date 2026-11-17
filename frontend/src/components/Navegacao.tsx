import { Icone, type NomeDeIcone } from './Icone'

type Item = {
  nome: NomeDeIcone
  texto: string
  acao?: 'inicio' | 'extrato' | 'transferir'
  demo?: boolean
}

const MENU: Item[] = [
  { nome: 'inicio', texto: 'Início', acao: 'inicio' },
  { nome: 'historico', texto: 'Extrato', acao: 'extrato' },
  { nome: 'transferir', texto: 'Transferências', acao: 'transferir' },
  { nome: 'pix', texto: 'Pix', demo: true },
  { nome: 'pagar', texto: 'Pagamentos', demo: true },
  { nome: 'cartao', texto: 'Cartões', demo: true },
  { nome: 'investir', texto: 'Investimentos', demo: true },
  { nome: 'emprestimo', texto: 'Empréstimos', demo: true },
  { nome: 'servicos', texto: 'Serviços', demo: true },
  { nome: 'ajustes', texto: 'Configurações', demo: true },
]

const ABAS: Item[] = [
  { nome: 'inicio', texto: 'Início', acao: 'inicio' },
  { nome: 'resumo', texto: 'Extrato', acao: 'extrato' },
  { nome: 'transferir', texto: 'Transferir', acao: 'transferir' },
  { nome: 'cartao', texto: 'Cartões', demo: true },
  { nome: 'menu', texto: 'Mais', demo: true },
]

type Acoes = {
  ativo: string
  aoNavegar: (acao: NonNullable<Item['acao']>) => void
  aoAvisar: () => void
}

/**
 * A coluna escura da esquerda, so em tela larga.
 *
 * Tres itens levam a algum lugar; os outros sete estao marcados como
 * demonstracao. A marca na lateral e discreta de proposito: ela precisa
 * aparecer a quem for clicar, nao competir com o saldo.
 */
export function Lateral({ ativo, aoNavegar, aoAvisar, aoSair }: Acoes & { aoSair: () => void }) {
  return (
    <aside className="lateral">
      <div className="lateral-marca">
        <span className="selo" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M20.5 3.5C12 3 6.5 6.4 5 12.2c-.6 2.3-.3 4.6.6 6.4l-1.9 2.6 1.7 1.2 1.9-2.5c1.7 1 3.9 1.3 6 .7 5.8-1.6 8.1-7.6 7.2-17.1zM9 16.6c1.2-3.6 3.6-6.2 7-7.8-2.5 2.1-4.3 4.8-5.3 8.1-.6.1-1.2.1-1.7-.3z" />
          </svg>
        </span>
        <span className="marca-nome">Banco</span>
      </div>

      <nav className="lateral-menu" aria-label="Seções">
        {MENU.map((i) => (
          <button
            key={i.texto}
            className={[
              'lateral-item',
              i.demo ? 'demo' : '',
              i.acao === ativo ? 'ativo' : '',
            ]
              .filter(Boolean)
              .join(' ')}
            aria-current={i.acao === ativo ? 'page' : undefined}
            onClick={() => (i.demo ? aoAvisar() : aoNavegar(i.acao!))}
          >
            <Icone nome={i.nome} tamanho={19} />
            <span>{i.texto}</span>
            {i.demo && <span className="selo-demo">demo</span>}
          </button>
        ))}
      </nav>

      <div className="lateral-pe">
        {/* O lugar do "Precisa de ajuda? Fale com a gente" da referencia. Nao
            existe atendimento aqui; existe o codigo, que e o que alguem
            olhando este projeto quer de verdade. */}
        <div className="ajuda">
          <span className="ajuda-icone">
            <Icone nome="codigo" tamanho={18} />
          </span>
          <p className="ajuda-titulo">Quer ver por dentro?</p>
          <p className="ajuda-texto">Spring Boot 4, Postgres e React.</p>
          <a
            className="ajuda-botao"
            href="https://github.com/gabrielangelo0/banco-digital"
            target="_blank"
            rel="noreferrer"
          >
            Ver o código
          </a>
        </div>

        <button className="lateral-sair" onClick={aoSair}>
          <Icone nome="sair" tamanho={18} />
          <span>Sair</span>
        </button>
      </div>
    </aside>
  )
}

/** A barra de abas do celular. Mesmos destinos da lateral, em cinco. */
export function BarraDeAbas({ ativo, aoNavegar, aoAvisar }: Acoes) {
  return (
    <nav className="abas" aria-label="Seções">
      {ABAS.map((i) => (
        <button
          key={i.texto}
          className={[
            'aba',
            i.demo ? 'demo' : '',
            i.acao === ativo ? 'ativo' : '',
          ]
            .filter(Boolean)
            .join(' ')}
          aria-current={i.acao === ativo ? 'page' : undefined}
          onClick={() => (i.demo ? aoAvisar() : aoNavegar(i.acao!))}
        >
          <Icone nome={i.nome} tamanho={21} />
          <span>{i.texto}</span>
        </button>
      ))}
    </nav>
  )
}
