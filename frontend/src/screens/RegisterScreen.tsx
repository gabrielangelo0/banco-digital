import { useState } from 'react'
import { cadastrar, entrar } from '../api/auth'

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
      <h2>Criar conta</h2>

      {erro && <div className="erro">{erro}</div>}

      <div className="campo">
        <label htmlFor="email">E-mail</label>
        <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>

      <div className="campo">
        <label htmlFor="senha">Senha de acesso</label>
        <input
          id="senha"
          type="password"
          value={senha}
          onChange={(e) => setSenha(e.target.value)}
        />
        <p className="lancamento-data">Pelo menos 8 caracteres. Prova quem você é.</p>
      </div>

      <div className="campo">
        <label htmlFor="senha-transacional">Senha transacional</label>
        <input
          id="senha-transacional"
          type="password"
          value={senhaTransacional}
          onChange={(e) => setSenhaTransacional(e.target.value)}
        />
        <p className="lancamento-data">
          Pelo menos 6 caracteres, e diferente da de acesso. É ela que autoriza
          mover dinheiro — entrar no app não basta.
        </p>
      </div>

      <button className="botao-primario" disabled={!podeEnviar} onClick={() => void criar()}>
        {enviando ? 'Criando...' : 'Criar conta'}
      </button>
      <button className="botao-texto" onClick={aoVoltar}>
        Já tenho conta
      </button>
    </div>
  )
}
