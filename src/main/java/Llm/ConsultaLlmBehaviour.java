package Llm;

import jade.core.Agent;
import jade.core.behaviours.Behaviour;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Consulta al LLM sin bloquear al agente: la llamada HTTP corre en los hilos del {@code HttpClient},
 * el agente sigue ejecutando sus otros comportamientos y {@link #alResponder} / {@link #alFallar}
 * se invocan en el hilo del propio agente, por lo que el código del usuario no necesita sincronización.
 *
 * <p>Es de un solo uso: cada consulta requiere una instancia nueva. Como en cualquier comportamiento,
 * si {@link #alResponder} o {@link #alFallar} lanzan una excepción, JADE termina al agente.
 */
public abstract class ConsultaLlmBehaviour extends Behaviour {

    private final ClienteLlm cliente;
    private final List<ClienteLlm.Mensaje> mensajes;
    private final String regex;
    private CompletableFuture<String> pendiente;
    private boolean terminado;

    protected ConsultaLlmBehaviour(Agent agente, ClienteLlm cliente, List<ClienteLlm.Mensaje> mensajes) {
        this(agente, cliente, mensajes, null);
    }

    /**
     * @param regex expresión regular a la que debe ajustarse la respuesta completa; {@code null} para texto libre
     */
    protected ConsultaLlmBehaviour(Agent agente, ClienteLlm cliente, List<ClienteLlm.Mensaje> mensajes,
                                   String regex) {
        super(agente);
        this.cliente = cliente;
        this.mensajes = List.copyOf(mensajes);
        this.regex = regex;
    }

    /** Se invoca en el hilo del agente cuando llega la respuesta. */
    protected abstract void alResponder(String respuesta);

    /** Se invoca en el hilo del agente si la consulta falla. Por defecto solo informa por stderr. */
    protected void alFallar(LlmException error) {
        System.err.println("[ConsultaLlm] error: " + error.getMessage());
    }

    @Override
    public void action() {
        if (pendiente == null) {
            pendiente = cliente.completarAsync(mensajes, regex);
            // Se ejecuta en un hilo ajeno al agente; restart() es seguro entre hilos y lo despierta.
            pendiente.whenComplete((respuesta, error) -> restart());
        }
        // Se bloquea primero y se comprueba después: cualquier mensaje ACL también despierta a un
        // comportamiento bloqueado, de modo que action() puede ejecutarse antes de que llegue la respuesta.
        block();
        if (!pendiente.isDone()) {
            return;
        }
        terminado = true;
        String respuesta;
        try {
            respuesta = pendiente.join();
        } catch (CompletionException e) {
            alFallar(e.getCause() instanceof LlmException llm
                    ? llm : new LlmException("Falló la consulta al LLM", e.getCause()));
            return;
        }
        // Fuera del try: una excepción de alResponder no es un fallo de la consulta y no debe ir a alFallar.
        alResponder(respuesta);
    }

    @Override
    public boolean done() {
        return terminado;
    }
}
