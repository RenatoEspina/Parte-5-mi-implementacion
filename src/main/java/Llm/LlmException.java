package Llm;

/**
 * Error al consultar el servidor LLM: servidor inalcanzable, tiempo de espera agotado,
 * respuesta HTTP distinta de 200 o respuesta sin texto utilizable.
 */
public class LlmException extends RuntimeException {

    public LlmException(String mensaje) {
        super(mensaje);
    }

    public LlmException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
