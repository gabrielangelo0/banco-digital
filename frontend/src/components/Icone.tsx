/**
 * Os icones da interface, desenhados aqui em vez de virem de um pacote.
 *
 * Sao quinze tracados curtos; uma biblioteca de icones custaria mais
 * quilobytes do que isto e traria outras mil formas que o app nao usa. Todos
 * herdam `currentColor` e a mesma espessura de traco, entao mudar a cor do
 * texto muda o icone junto - que e como eles se comportam na referencia.
 */

export type NomeDeIcone =
  | 'transferir'
  | 'pagar'
  | 'pix'
  | 'recarga'
  | 'mais'
  | 'inicio'
  | 'resumo'
  | 'cartao'
  | 'servicos'
  | 'menu'
  | 'sino'
  | 'olho'
  | 'olho-fechado'
  | 'seta'
  | 'historico'
  | 'escudo'
  | 'codigo'
  | 'sair'
  | 'investir'
  | 'emprestimo'
  | 'ajustes'
  | 'busca'

const TRACADOS: Record<NomeDeIcone, React.ReactNode> = {
  transferir: (
    <>
      <path d="M4 8h13l-3.2-3.2" />
      <path d="M20 16H7l3.2 3.2" />
    </>
  ),
  pagar: (
    <>
      <path d="M4 5v14M7.5 5v14M11 5v14M14 5v14M17 5v14M20 5v14" />
    </>
  ),
  pix: <path d="M12 3.2 20.8 12 12 20.8 3.2 12z" />,
  recarga: (
    <>
      <rect x="7" y="3" width="10" height="18" rx="2.5" />
      <path d="M11 18h2" />
    </>
  ),
  mais: <path d="M12 5v14M5 12h14" />,
  inicio: (
    <>
      <path d="M4 10.5 12 4l8 6.5" />
      <path d="M6 10v9.5h12V10" />
    </>
  ),
  resumo: (
    <>
      <path d="M5 19V11M10 19V5M15 19v-6M20 19v-10" />
    </>
  ),
  cartao: (
    <>
      <rect x="3" y="5.5" width="18" height="13" rx="2.5" />
      <path d="M3 10h18" />
    </>
  ),
  servicos: (
    <>
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.6" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.6" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.6" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.6" />
    </>
  ),
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  sino: (
    <>
      <path d="M6.5 10a5.5 5.5 0 0 1 11 0c0 4 1.5 5.5 1.5 5.5H5S6.5 14 6.5 10z" />
      <path d="M10 19a2 2 0 0 0 4 0" />
    </>
  ),
  olho: (
    <>
      <path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z" />
      <circle cx="12" cy="12" r="2.8" />
    </>
  ),
  'olho-fechado': (
    <>
      <path d="M3 4.5 21 19.5" />
      <path d="M10 6.1A9.6 9.6 0 0 1 12 5.9c6 0 9.5 6.1 9.5 6.1a17 17 0 0 1-3.3 3.9" />
      <path d="M6.2 8.2A16.6 16.6 0 0 0 2.5 12S6 18.1 12 18.1a9.8 9.8 0 0 0 3.4-.6" />
    </>
  ),
  seta: <path d="M5 12h13l-4.2-4.2M18 12l-4.2 4.2" />,
  historico: (
    <>
      <circle cx="12" cy="12" r="8.2" />
      <path d="M12 7.4V12l3 1.8" />
    </>
  ),
  escudo: (
    <>
      <path d="M12 3.2 19 6v5.4c0 4.3-2.9 7.6-7 9.4-4.1-1.8-7-5.1-7-9.4V6z" />
      <path d="M9.2 12.1 11.3 14l3.5-3.7" />
    </>
  ),
  codigo: (
    <>
      <path d="M9 7.5 4.5 12 9 16.5" />
      <path d="M15 7.5 19.5 12 15 16.5" />
    </>
  ),
  sair: (
    <>
      <path d="M14.5 7.5V5.5a1.8 1.8 0 0 0-1.8-1.8H6.3A1.8 1.8 0 0 0 4.5 5.5v13a1.8 1.8 0 0 0 1.8 1.8h6.4a1.8 1.8 0 0 0 1.8-1.8v-2" />
      <path d="M10 12h9.5l-3-3M19.5 12l-3 3" />
    </>
  ),
  investir: (
    <>
      <path d="M4 16.5 9.5 11l3.5 3.2 6.5-6.7" />
      <path d="M15 7.5h4.5V12" />
    </>
  ),
  emprestimo: (
    <>
      <path d="M3.5 9.5 12 4.5l8.5 5" />
      <path d="M6 10v8M10.6 10v8M15.2 10v8M19.8 10v8" />
      <path d="M3.5 20.5h17" />
    </>
  ),
  ajustes: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 3v2.2M12 18.8V21M21 12h-2.2M5.2 12H3M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6M18.4 18.4l-1.6-1.6M7.2 7.2 5.6 5.6" />
    </>
  ),
  busca: (
    <>
      <circle cx="11" cy="11" r="6.4" />
      <path d="M15.8 15.8 20 20" />
    </>
  ),
}

export function Icone({
  nome,
  tamanho = 20,
}: {
  nome: NomeDeIcone
  tamanho?: number
}) {
  return (
    <svg
      className="icone"
      width={tamanho}
      height={tamanho}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {TRACADOS[nome]}
    </svg>
  )
}
