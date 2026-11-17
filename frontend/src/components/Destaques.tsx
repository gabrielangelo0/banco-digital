import { Icone } from './Icone'

/**
 * Os tres cartoes de baixo.
 *
 * Na referencia sao ofertas: invista, seu cartao, servicos. Dois deles aqui
 * falam de coisas que o app realmente faz - a senha transacional e o codigo
 * aberto - e o terceiro, o cartao, vai marcado como demonstracao. Anunciar um
 * produto que nao existe num app que serve para mostrar como ele foi feito
 * seria o unico lugar da tela onde o conteudo mente.
 */
export function Destaques({ aoAvisar }: { aoAvisar: () => void }) {
  return (
    <>
      <section className="cartao destaque-escuro">
        <span className="destaque-icone">
          <Icone nome="codigo" tamanho={22} />
        </span>
        <h2>O código está aberto</h2>
        <p>
          Transferência com trava de linha, chave de idempotência e caixa de saída.
          Dá para ler tudo.
        </p>
        <a
          className="destaque-botao"
          href="https://github.com/gabrielangelo0/banco-digital"
          target="_blank"
          rel="noreferrer"
        >
          Ver no GitHub <Icone nome="seta" tamanho={16} />
        </a>
      </section>

      <section className="cartao destaque">
        <div className="cartao-cabeca">
          <h2>Meus cartões</h2>
          <span className="selo-demo">demo</span>
        </div>
        <div className="cartao-mini" aria-hidden="true">
          <span className="cartao-chip" />
          <span className="cartao-mini-nome">Banco</span>
        </div>
        <p className="destaque-nota">
          Esta demonstração não emite cartão — a conta movimenta dinheiro por
          transferência.
        </p>
        <button className="destaque-link" onClick={aoAvisar}>
          Ver detalhes <Icone nome="seta" tamanho={16} />
        </button>
      </section>

      <section className="cartao destaque">
        <span className="destaque-icone verde">
          <Icone nome="escudo" tamanho={22} />
        </span>
        <h2>Sua conta tem duas senhas</h2>
        <p>
          Entrar prova quem você é. Mover dinheiro pede a segunda senha — mesmo com
          a sessão aberta.
        </p>
        <span className="destaque-estado">
          <span className="ponto-verde" aria-hidden="true" /> Já está ativo
        </span>
      </section>
    </>
  )
}
