const moeda = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

/**
 * O backend manda NUMERIC como numero ou string conforme o caso. Aqui a
 * conversao e so para EXIBIR - nenhuma conta de dinheiro acontece no
 * navegador, onde todo numero e ponto flutuante.
 */
export const formatarDinheiro = (valor: string | number) => moeda.format(Math.abs(Number(valor)))

export const formatarData = (iso: string) =>
  new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' })
    .format(new Date(iso))
