const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

/**
 * O backend manda NUMERIC como numero ou string conforme o caso. Aqui a
 * conversao e so para EXIBIR - nenhuma conta de dinheiro acontece no
 * navegador, onde todo numero e ponto flutuante.
 */
export const formatarDinheiro = (valor: string | number) => moeda.format(Math.abs(Number(valor)))

const hora = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' })
const diaCompleto = new Intl.DateTimeFormat('pt-BR', {
  weekday: 'long',
  day: '2-digit',
  month: 'long',
})

export const formatarHora = (iso: string) => hora.format(new Date(iso))

/**
 * O rotulo do dia no extrato.
 *
 * A diferenca e medida entre as DATAS (meia-noite local de cada uma), nunca
 * entre os instantes: 23h59 e 00h01 distam dois minutos e sao dias
 * diferentes, e e o dia que o extrato agrupa.
 */
export function rotuloDoDia(iso: string): string {
  const data = new Date(iso)
  const meiaNoite = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const dias = Math.round((meiaNoite(new Date()) - meiaNoite(data)) / 86_400_000)

  if (dias === 0) return 'Hoje'
  if (dias === 1) return 'Ontem'
  return diaCompleto.format(data)
}

/**
 * Soma valores decimais SEM ponto flutuante.
 *
 * O backend manda quatro casas ("42.0000"). Aqui cada valor vira um inteiro
 * de decimos de milesimo - 42.0000 vira 420000 - e a soma acontece entre
 * inteiros, que o JavaScript representa exatamente ate 2^53. Somar 0.1 + 0.2
 * como ponto flutuante da 0.30000000000000004; num extrato isso vira um
 * centavo que aparece do nada.
 *
 * O resultado volta como string decimal, no mesmo formato que veio do
 * servidor, para seguir pelo mesmo caminho de formatacao.
 */
const CASAS = 4
const ESCALA = 10_000

function emUnidades(valor: string | number): number {
  const texto = String(valor).trim()
  const negativo = texto.startsWith('-')
  const [inteiro, fracao = ''] = texto.replace('-', '').split('.')
  const unidades = Number(inteiro) * ESCALA + Number((fracao + '0000').slice(0, CASAS))
  return negativo ? -unidades : unidades
}

export function somar(valores: Array<string | number>): string {
  const total = valores.reduce<number>((acumulado, valor) => acumulado + emUnidades(valor), 0)
  const sinal = total < 0 ? '-' : ''
  const absoluto = Math.abs(total)
  return `${sinal}${Math.floor(absoluto / ESCALA)}.${String(absoluto % ESCALA).padStart(CASAS, '0')}`
}

/**
 * O que entrou e o que saiu NO MES corrente, e a comparacao com o mes
 * anterior.
 *
 * Tudo vem do extrato que o servidor ja mandou - nenhum numero daqui e
 * inventado para encher o grafico. Quando nao existe mes anterior com
 * movimento, `variacao` volta nulo e a tela nao mostra comparacao: "0% em
 * relacao ao mes passado" seria uma afirmacao sobre um mes que nao houve.
 */
export type ResumoDoMes = {
  entradas: string
  saidas: string
  variacao: number | null
}

type MovimentoBruto = { amount: string | number; direction: 'DEBIT' | 'CREDIT'; createdAt: string }

/**
 * O servidor manda o debito com sinal: "-80.0". Para somar e para medir a
 * altura de uma barra o que interessa e o tamanho, nao a direcao - essa ja
 * esta em `direction`. Sem este `abs` o total do mes sai negativo e o teto do
 * grafico vira zero, que foi exatamente o que aconteceu.
 */
const grandeza = (valor: string | number) => Math.abs(emUnidades(valor))

const paraTexto = (unidades: number) =>
  `${Math.floor(unidades / ESCALA)}.${String(unidades % ESCALA).padStart(CASAS, '0')}`

function mesDe(iso: string): number {
  const d = new Date(iso)
  return d.getFullYear() * 12 + d.getMonth()
}

export function resumoDoMes(lancamentos: MovimentoBruto[]): ResumoDoMes {
  const agora = new Date()
  const atual = agora.getFullYear() * 12 + agora.getMonth()

  const noMes = (m: number, direcao: 'DEBIT' | 'CREDIT') =>
    lancamentos
      .filter((l) => l.direction === direcao && mesDe(l.createdAt) === m)
      .reduce((total, l) => total + grandeza(l.amount), 0)

  const saiuAgora = noMes(atual, 'DEBIT')
  const saiuAntes = noMes(atual - 1, 'DEBIT')

  return {
    entradas: paraTexto(noMes(atual, 'CREDIT')),
    saidas: paraTexto(saiuAgora),
    variacao: saiuAntes === 0 ? null : Math.round(((saiuAgora - saiuAntes) / saiuAntes) * 100),
  }
}

/**
 * Quanto saiu em cada um dos ultimos dias, para as barras.
 *
 * Os dias sem movimento entram com zero de proposito: um grafico que pula os
 * dias vazios desenha um gasto constante onde houve silencio.
 */
export type Barra = { rotulo: string; dia: string; valor: number }

const diaCurto = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit' })

export function gastoPorDia(lancamentos: MovimentoBruto[], dias = 7): Barra[] {
  const porChave = new Map<string, number>()
  for (const l of lancamentos) {
    if (l.direction !== 'DEBIT') continue
    const d = new Date(l.createdAt)
    const chave = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
    porChave.set(chave, (porChave.get(chave) ?? 0) + grandeza(l.amount))
  }

  const barras: Barra[] = []
  const hoje = new Date()
  for (let i = dias - 1; i >= 0; i--) {
    const d = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate() - i)
    const chave = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
    barras.push({
      rotulo: diaCurto.format(d),
      dia: chave,
      valor: (porChave.get(chave) ?? 0) / ESCALA,
    })
  }
  return barras
}

/**
 * "Hoje, 09:24". Para datas antigas o dia por extenso nao cabe na linha do
 * lancamento, entao vira "14/11, 09:24".
 */
export function quando(iso: string): string {
  const rotulo = rotuloDoDia(iso)
  const curto = rotulo === 'Hoje' || rotulo === 'Ontem' ? rotulo : diaCurto.format(new Date(iso))
  return `${curto}, ${formatarHora(iso)}`
}
