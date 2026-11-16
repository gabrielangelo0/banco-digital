export type Account = {
  id: number
  holderName: string
  balance: string | number
}

export type Entry = {
  id: number
  transferId: string
  amount: string | number
  direction: 'DEBIT' | 'CREDIT'
  createdAt: string
}

export type TransferResponse = {
  transferId: string
  status: 'COMPLETED' | 'FAILED'
  sourceBalance: string | number
  targetBalance: string | number
}

export type ApiError = { code: string; message: string }

/**
 * Erro que carrega o `code` da API, nao so a mensagem.
 *
 * O codigo e o que permite a tela colocar o erro NO CAMPO certo - senha
 * transacional errada embaixo do campo da senha, conta inexistente embaixo do
 * campo do numero. A critica mais votada do app usado como referencia e
 * justamente erro sem mensagem inteligivel.
 */
export class ErroDaApi extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.code = code
  }
}

/** Toda chamada manda o cookie de sessao. */
const comCredenciais: RequestInit = { credentials: 'include' }

async function parse<T>(resposta: Response): Promise<T> {
  if (!resposta.ok) {
    let erro: ApiError = { code: 'ERRO', message: `Falha ${resposta.status}` }
    try {
      erro = (await resposta.json()) as ApiError
    } catch {
      // resposta sem corpo JSON; mantem a mensagem generica
    }
    throw new ErroDaApi(erro.code, erro.message)
  }
  if (resposta.status === 204) {
    return undefined as T
  }
  return (await resposta.json()) as T
}

/** Devolve apenas a conta do usuario autenticado. */
export const fetchAccounts = () =>
  fetch('/api/accounts', comCredenciais).then(parse<Account[]>)

export const fetchAccount = (id: number) =>
  fetch(`/api/accounts/${id}`, comCredenciais).then(parse<Account>)

export const fetchStatement = (id: number) =>
  fetch(`/api/accounts/${id}/statement`, comCredenciais).then(parse<Entry[]>)

export const postTransfer = (
  // Sem `sourceAccountId`: a origem e a conta da sessao.
  body: { targetAccountId: number; amount: string; transactionPassword: string },
  idempotencyKey: string
) =>
  fetch('/api/transfers', {
    ...comCredenciais,
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // A chave viaja no header, nao no corpo: ela identifica a INTENCAO, nao
      // o conteudo da requisicao.
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(body),
  }).then(parse<TransferResponse>)
