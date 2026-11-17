import { Icone, type NomeDeIcone } from './Icone'

/**
 * A fileira de acoes rapidas.
 *
 * Transferir e a unica que faz alguma coisa; as outras quatro estao aqui
 * porque a referencia as tem, e vao marcadas como demonstracao. Um botao
 * desligado sem aviso e o tipo de coisa que quem testa o app descobre
 * apertando - melhor dizer antes.
 */
type Atalho = { nome: NomeDeIcone; texto: string; demo?: boolean }

const ATALHOS: Atalho[] = [
  { nome: 'transferir', texto: 'Transferir' },
  { nome: 'pagar', texto: 'Pagar', demo: true },
  { nome: 'pix', texto: 'Pix', demo: true },
  { nome: 'recarga', texto: 'Recarga', demo: true },
  { nome: 'mais', texto: 'Mais', demo: true },
]

export function Atalhos({
  aoTransferir,
  aoAvisar,
  habilitado,
}: {
  aoTransferir: () => void
  aoAvisar: () => void
  habilitado: boolean
}) {
  return (
    <section className="cartao atalhos-cartao" aria-label="Ações rápidas">
      <div className="cartao-cabeca atalhos-cabeca">
        <h2>Ações rápidas</h2>
      </div>

      <div className="atalhos">
        {ATALHOS.map((a) => (
          <button
            key={a.texto}
            className={a.demo ? 'atalho demo' : 'atalho'}
            onClick={a.demo ? aoAvisar : aoTransferir}
            disabled={!a.demo && !habilitado}
          >
            <span className="atalho-icone">
              <Icone nome={a.nome} tamanho={23} />
            </span>
            <span className="atalho-texto">{a.texto}</span>
            {a.demo && <span className="selo-demo">demo</span>}
          </button>
        ))}
      </div>
    </section>
  )
}
