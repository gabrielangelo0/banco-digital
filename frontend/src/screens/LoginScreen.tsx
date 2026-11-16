import { useEffect, useState } from 'react'
import { buscarDemo, entrar, entrarComoVisitante, type DemoInfo } from '../api/auth'

export function LoginScreen({
  aoEntrar,
  aoIrParaCadastro,
}: {
  aoEntrar: () => void
  aoIrParaCadastro: () => void
}) {
  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<string | null>(null)
  const [enviando, setEnviando] = useState(false)
  const [demo, setDemo] = useState<DemoInfo | null>(null)

  useEffect(() => {
    void buscarDemo().then(setDemo)
  }, [])

  async function tentar(acao: () => Promise<unknown>) {
    setEnviando(true)
    setErro(null)
    try {
      await acao()
      aoEntrar()
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Não foi possível entrar')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="app">
      {/* `tela-auth` centraliza na vertical e `auth-caixa` da o respiro
          lateral que faltava: sem ela os campos encostam na borda da tela. */}
      <div className="tela-auth">
        <div className="auth-caixa">
          <div className="marca">
            <div className="marca-selo" aria-hidden="true">
              B
            </div>
            <div>
              <div className="marca-nome">Banco</div>
              <div className="marca-linha">Estudo de consistência transacional</div>
            </div>
          </div>

          <h2>Entrar</h2>

          {erro && <div className="erro">{erro}</div>}

          {/* <form> de verdade: no celular o teclado mostra "ir" em vez de
              "enter", e no desktop o Enter no campo de senha envia. Sem ele,
              entrar exigiria alcancar o botao. */}
          <form
            onSubmit={(e) => {
              e.preventDefault()
              if (!enviando && email && senha) void tentar(() => entrar(email, senha))
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
              <label htmlFor="senha">Senha</label>
              <input
                id="senha"
                type="password"
                autoComplete="current-password"
                value={senha}
                onChange={(e) => setSenha(e.target.value)}
              />
            </div>

            <button className="botao-primario" type="submit" disabled={enviando || !email || !senha}>
              {enviando ? 'Entrando...' : 'Entrar'}
            </button>
          </form>

          <button className="botao-texto" onClick={aoIrParaCadastro}>
            Criar uma conta
          </button>

          {demo && (
            <>
              <p className="bloco-titulo">Entrar como visitante</p>
              <div className="bloco">
                {demo.accounts.map((c) => (
                  <button
                    key={c.id}
                    className="lancamento lancamento-botao"
                    disabled={enviando}
                    onClick={() => void tentar(() => entrarComoVisitante(c.id))}
                  >
                    <div className="seta visitante">{c.holderName.charAt(0)}</div>
                    <div className="lancamento-info">
                      <div className="lancamento-titulo">{c.holderName}</div>
                      <div className="lancamento-data">conta {c.id}</div>
                    </div>
                  </button>
                ))}
              </div>
              <div className="dica-chave">
                A senha transacional desta demonstração é <strong>{demo.transactionPassword}</strong>.
                Ela aparece aqui de propósito: entrar não autoriza mover dinheiro, e o
                recurso continua demonstrável sem um atalho que viraria brecha.
                <br />
                Abra em duas abas, entre com contas diferentes e transfira — a
                notificação chega de uma para a outra.
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
