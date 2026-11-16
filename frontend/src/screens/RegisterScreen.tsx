import { useState } from 'react'
import { cadastrar, entrar } from '../api/auth'
import { Vitrine } from './Vitrine'

export function RegisterScreen({
  aoEntrar,
  aoVoltar,
}: {
  aoEntrar: () => void
  aoVoltar: () => void
}) {
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [senhaTransacional, setSenhaTransacional] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)

  const podeEnviar =
    email.includes('@') && senha.length >= 8 && senhaTransacional.length >= 6 && !enviando

  async function criar() {
    if (!podeEnviar) return
    setEnviando(true)
    setErro(null)
    try {
      await cadastrar(email, senha, senhaTransacional)
      await entrar(email, senha)
      aoEntrar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível cadastrar')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="app">
      <div className="entrada">
        <Vitrine />

        <main className="entrada-corpo">
          <div className="formulario">
            <h2>Criar conta</h2>

            {erro && <div className="erro">{erro}</div>}

            <form
              onSubmit={(e) => {
                e.preventDefault()
                void criar()
              }}
            >
              <div className="campo">
                <label htmlFor="email">E-mail</label>
                <input
                  id="email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                />
              </div>

              <div className="campo">
                <label htmlFor="senha">Senha de acesso</label>
                <input
                  id="senha"
                  type="password"
                  autoComplete="new-password"
                  value={senha}
                  onChange={(e) => setSenha(e.target.value)}
                />
                <p className="campo-nota">Pelo menos 8 caracteres. Prova quem você é.</p>
              </div>

              <div className="campo">
                <label htmlFor="senha-transacional">Senha transacional</label>
                <input
                  id="senha-transacional"
                  type="password"
                  autoComplete="new-password"
                  value={senhaTransacional}
                  onChange={(e) => setSenhaTransacional(e.target.value)}
                />
                <p className="campo-nota">
                  Pelo menos 6 caracteres, e diferente da de acesso. É ela que autoriza
                  mover dinheiro: entrar no app não basta.
                </p>
              </div>

              <button className="botao-primario" type="submit" disabled={!podeEnviar}>
                {enviando ? 'Criando...' : 'Criar conta'}
              </button>
            </form>

            <button className="botao-texto" onClick={aoVoltar}>
              Já tenho conta
            </button>
          </div>
        </main>
      </div>
    </div>
  )
}
