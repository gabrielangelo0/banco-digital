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
        <span className="selo" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M20.5 3.5C12 3 6.5 6.4 5 12.2c-.6 2.3-.3 4.6.6 6.4l-1.9 2.6 1.7 1.2 1.9-2.5c1.7 1 3.9 1.3 6 .7 5.8-1.6 8.1-7.6 7.2-17.1zM9 16.6c1.2-3.6 3.6-6.2 7-7.8-2.5 2.1-4.3 4.8-5.3 8.1-.6.1-1.2.1-1.7-.3z" />
          </svg>
        </span>
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
        <li>
          Quem se cadastra recebe o comprovante por e-mail. Para ver a ida e a volta,
          são dois cadastros: conta de demonstração não transfere para fora do par.
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
