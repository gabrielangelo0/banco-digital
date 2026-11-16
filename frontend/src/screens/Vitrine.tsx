/**
 * A metade esquerda da entrada.
 *
 * A tela de login e a unica que todo visitante ve, e este app existe para ser
 * mostrado: dizer aqui o que ele demonstra vale mais do que um formulario
 * sozinho no meio do cinza. No celular ela encolhe para marca e frase - quem
 * abriu no telefone quer entrar, nao ler.
 */
export function Vitrine() {
  return (
    <aside className="vitrine">
      <div className="vitrine-marca">
        <div className="selo" aria-hidden="true">
          B
        </div>
        <span className="marca-nome">Banco</span>
      </div>

      <h1>Dinheiro que não se perde, não duplica e não trava.</h1>

      <p className="vitrine-linha">
        Entre como visitante e transfira entre as duas contas de demonstração. A
        notificação chega na outra no mesmo instante.
      </p>

      <ul className="vitrine-lista">
        <li>Entrar prova quem você é. Mover dinheiro pede a segunda senha.</li>
        <li>Tentar de novo com o mesmo valor não transfere duas vezes.</li>
        <li>
          O servidor empurra a notificação quando a transferência fecha, em vez de o
          app ficar perguntando.
        </li>
      </ul>

      <p className="vitrine-rodape">
        Spring Boot 4, Postgres e React. O código está{' '}
        <a href="https://github.com/gabrielangelo0/banco-digital" target="_blank" rel="noreferrer">
          no GitHub
        </a>
        .
      </p>
    </aside>
  )
}
