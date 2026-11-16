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
      <h2>Entrar</h2>

      {erro && <div className="erro">{erro}</div>}

      <div className="campo">
        <label htmlFor="email">E-mail</label>
        <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      </div>

      <div className="campo">
        <label htmlFor="senha">Senha</label>
        <input id="senha" type="password" value={senha} onChange={(e) => setSenha(e.target.value)} />
      </div>

      <button
        className="botao-primario"
        disabled={enviando || !email || !senha}
        onClick={() => void tentar(() => entrar(email, senha))}
      >
        {enviando ? 'Entrando...' : 'Entrar'}
      </button>

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
                className="lancamento"
                style={{ width: '100%', textAlign: 'left' }}
                disabled={enviando}
                onClick={() => void tentar(() => entrarComoVisitante(c.id))}
              >
                <div
                  className="seta entrada"
                  style={{ background: 'rgba(130,10,209,.1)', color: 'var(--roxo)' }}
                >
                  {c.holderName.charAt(0)}
                </div>
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
  )
}
