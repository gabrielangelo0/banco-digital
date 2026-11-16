import { ErroDaApi, type ApiError } from './client'

export type ContaDeDemonstracao = { id: number; holderName: string }
export type DemoInfo = { accounts: ContaDeDemonstracao[]; transactionPassword: string }

const json: RequestInit = {
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
}

async function enviar(url: string, metodo: string, corpo?: unknown): Promise<Response> {
  const resposta = await fetch(url, {
    ...json,
    method: metodo,
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  })
  if (!resposta.ok) {
    let erro: ApiError = { code: 'ERRO', message: `Falha ${resposta.status}` }
    try {
      erro = (await resposta.json()) as ApiError
    } catch {
      // sem corpo JSON
    }
    throw new ErroDaApi(erro.code, erro.message)
  }
  return resposta
}

export const cadastrar = (email: string, password: string, transactionPassword: string) =>
  enviar('/api/auth/register', 'POST', { email, password, transactionPassword })

export const entrar = (email: string, password: string) =>
  enviar('/api/auth/login', 'POST', { email, password })

export const sair = () => enviar('/api/auth/logout', 'POST')

export const entrarComoVisitante = (accountId: number) =>
  enviar('/api/auth/guest', 'POST', { accountId })

/** Devolve null quando a demonstracao esta desligada neste ambiente. */
export const buscarDemo = async (): Promise<DemoInfo | null> => {
  const resposta = await fetch('/api/demo', { credentials: 'include' })
  return resposta.ok ? ((await resposta.json()) as DemoInfo) : null
}
