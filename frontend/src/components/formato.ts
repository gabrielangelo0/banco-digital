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
