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

async function parse<T>(resposta: Response): Promise<T> {
  if (!resposta.ok) {
    let erro: ApiError = { code: 'ERRO', message: `Falha ${resposta.status}` }
    try {
      erro = (await resposta.json()) as ApiError
    } catch {
      // resposta sem corpo JSON; mantem a mensagem generica
    }
    throw new Error(erro.message)
  }
  return (await resposta.json()) as T
}

export const fetchAccounts = () => fetch('/api/accounts').then(parse<Account[]>)

export const fetchAccount = (id: number) => fetch(`/api/accounts/${id}`).then(parse<Account>)

export const fetchStatement = (id: number) =>
  fetch(`/api/accounts/${id}/statement`).then(parse<Entry[]>)

export const postTransfer = (
  body: { sourceAccountId: number; targetAccountId: number; amount: string },
  idempotencyKey: string
) =>
  fetch('/api/transfers', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // A chave viaja no header, nao no corpo: ela identifica a INTENCAO, nao
      // o conteudo da requisicao.
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(body),
  }).then(parse<TransferResponse>)
