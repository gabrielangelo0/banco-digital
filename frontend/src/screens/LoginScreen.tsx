import { useEffect, useState } from 'react'
import { buscarDemo, entrar, entrarComoVisitante, type DemoInfo } from '../api/auth'
import { Vitrine } from './Vitrine'

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
      <div className="entrada">
        <Vitrine />

        <main className="entrada-corpo">
          <div className="formulario">
            <h2>Entrar</h2>

            {erro && <div className="erro">{erro}</div>}

            {/* <form> de verdade: no celular o teclado mostra "ir" em vez de
                "enter", e no desktop o Enter no campo de senha envia. */}
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

              <button
                className="botao-primario"
                type="submit"
                disabled={enviando || !email || !senha}
              >
                {enviando ? 'Entrando...' : 'Entrar'}
              </button>
            </form>

            <button className="botao-texto" onClick={aoIrParaCadastro}>
              Criar uma conta
            </button>

            {demo && (
              <div className="visitantes">
                <h3>Ou entre como visitante</h3>

                {demo.accounts.map((c) => (
                  <button
                    key={c.id}
                    className="visitante"
                    disabled={enviando}
                    onClick={() => void tentar(() => entrarComoVisitante(c.id))}
                  >
                    <span className="visitante-inicial" aria-hidden="true">
                      {c.holderName.charAt(0)}
                    </span>
                    <span>
                      <span className="visitante-nome">{c.holderName}</span>
                      <span className="visitante-conta"> conta {c.id}</span>
                    </span>
                  </button>
                ))}

                <div className="nota">
                  <p>
                    A senha transacional desta demonstração é{' '}
                    <strong>{demo.transactionPassword}</strong>. Ela está à vista de
                    propósito: entrar não autoriza mover dinheiro, então o recurso
                    continua demonstrável sem virar brecha.
                  </p>
                  <p>
                    Abra em duas abas, entre com contas diferentes e transfira de uma
                    para a outra.
                  </p>
                </div>
              </div>
            )}
          </div>
        </main>
      </div>
    </div>
  )
}
