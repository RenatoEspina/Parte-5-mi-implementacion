package Llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Cliente de la API de chat completions compatible con OpenAI que expone vLLM.
 *
 * <p>Es seguro entre hilos: una sola instancia puede compartirse entre todos los agentes de la JVM.
 * Dentro de un agente JADE no se debe llamar a {@link #completar(List)} desde {@code action()},
 * porque bloquea el único hilo del agente; para eso existe {@link ConsultaLlmBehaviour}.
 */
public class ClienteLlm {

    /** Mensaje de una conversación. {@code role} es {@code system}, {@code user} o {@code assistant}. */
    public record Mensaje(String role, String content) {

        public static Mensaje sistema(String texto) {
            return new Mensaje("system", texto);
        }

        public static Mensaje usuario(String texto) {
            return new Mensaje("user", texto);
        }

        public static Mensaje asistente(String texto) {
            return new Mensaje("assistant", texto);
        }
    }

    private static final int MAX_CARACTERES_ERROR = 300;

    private final String baseUrl;
    private final String modelo;
    private final String apiKey;
    private final double temperatura;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param baseUrl     URL base de la API, por ejemplo {@code http://127.0.0.1:8000/v1}
     * @param modelo      nombre (o alias) del modelo servido
     * @param apiKey      clave opcional; si no es nula ni vacía se envía como {@code Bearer}
     * @param temperatura temperatura de muestreo
     * @param maxTokens   máximo de tokens generados por respuesta
     * @param timeout     tiempo máximo de espera de cada consulta
     */
    public ClienteLlm(String baseUrl, String modelo, String apiKey, double temperatura, int maxTokens,
                      Duration timeout) {
        this.baseUrl = quitarBarrasFinales(baseUrl);
        this.modelo = modelo;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.temperatura = temperatura;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        // vLLM solo habla HTTP/1.1: se evita que Java intente la actualización a h2c.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Construye un cliente desde variables de entorno; las vacías se tratan como ausentes.
     * Valores por defecto: {@code LLM_BASE_URL=http://127.0.0.1:8000/v1}, {@code LLM_MODEL=market-llm},
     * {@code LLM_API_KEY} sin valor, {@code LLM_TEMPERATURE=0.7}, {@code LLM_MAX_TOKENS=256} y
     * {@code LLM_TIMEOUT_SECONDS=60}.
     *
     * @throws LlmException si alguna variable numérica no es un número válido
     */
    public static ClienteLlm desdeEntorno() {
        try {
            return new ClienteLlm(
                    variable("LLM_BASE_URL", "http://127.0.0.1:8000/v1"),
                    variable("LLM_MODEL", "market-llm"),
                    variable("LLM_API_KEY", null),
                    Double.parseDouble(variable("LLM_TEMPERATURE", "0.7")),
                    Integer.parseInt(variable("LLM_MAX_TOKENS", "256")),
                    Duration.ofSeconds(Long.parseLong(variable("LLM_TIMEOUT_SECONDS", "60"))));
        } catch (NumberFormatException e) {
            throw new LlmException("Variable de entorno LLM_* numérica inválida: " + e.getMessage(), e);
        }
    }

    /** Equivale a {@link #completar(List, String)} sin restricción de formato. */
    public String completar(List<Mensaje> mensajes) {
        return completar(mensajes, null);
    }

    /**
     * Consulta al LLM y espera la respuesta. Bloquea el hilo que llama.
     *
     * @param regex expresión regular a la que debe ajustarse la respuesta completa; puede ser {@code null}
     * @return el texto de la respuesta, sin espacios en los extremos
     * @throws LlmException ante cualquier fallo de la consulta
     */
    public String completar(List<Mensaje> mensajes, String regex) {
        HttpRequest peticion = construirPeticion(mensajes, regex);
        try {
            return extraerContenido(http.send(peticion, HttpResponse.BodyHandlers.ofString()));
        } catch (IOException e) {
            throw convertir(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Consulta al LLM interrumpida", e);
        }
    }

    /**
     * Consulta al LLM sin bloquear. El futuro termina con el texto de la respuesta o, si falla, de forma
     * excepcional con una {@link LlmException}. Sus etapas dependientes se ejecutan en un hilo del
     * {@code HttpClient}, no en el del agente; en un agente conviene usar {@link ConsultaLlmBehaviour}.
     *
     * @param regex expresión regular a la que debe ajustarse la respuesta completa; puede ser {@code null}
     */
    public CompletableFuture<String> completarAsync(List<Mensaje> mensajes, String regex) {
        CompletableFuture<String> resultado = new CompletableFuture<>();
        try {
            http.sendAsync(construirPeticion(mensajes, regex), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((respuesta, error) -> {
                        try {
                            if (error != null) {
                                resultado.completeExceptionally(convertir(error));
                            } else {
                                resultado.complete(extraerContenido(respuesta));
                            }
                        } catch (RuntimeException e) {
                            resultado.completeExceptionally(convertir(e));
                        }
                    });
        } catch (RuntimeException e) {
            resultado.completeExceptionally(convertir(e));
        }
        return resultado;
    }

    /** Indica si el servidor responde 200 en {@code GET {baseUrl}/models}; ante cualquier error devuelve false. */
    public boolean disponible() {
        try {
            HttpRequest peticion = base("/models").GET().build();
            return http.send(peticion, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private HttpRequest.Builder base(String ruta) {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(baseUrl + ruta)).timeout(timeout);
        if (apiKey != null) {
            peticion.header("Authorization", "Bearer " + apiKey);
        }
        return peticion;
    }

    private HttpRequest construirPeticion(List<Mensaje> mensajes, String regex) {
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.put("model", modelo);
        cuerpo.put("temperature", temperatura);
        cuerpo.put("max_tokens", maxTokens);
        ArrayNode lista = cuerpo.putArray("messages");
        for (Mensaje mensaje : mensajes) {
            lista.addObject().put("role", mensaje.role()).put("content", mensaje.content());
        }
        if (regex != null) {
            cuerpo.putObject("structured_outputs").put("regex", regex);
        }
        try {
            return base("/chat/completions")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new LlmException("No se pudo serializar la consulta al LLM", e);
        }
    }

    private String extraerContenido(HttpResponse<String> respuesta) {
        String cuerpo = respuesta.body();
        if (respuesta.statusCode() != 200) {
            String recorte = cuerpo.length() > MAX_CARACTERES_ERROR
                    ? cuerpo.substring(0, MAX_CARACTERES_ERROR) : cuerpo;
            throw new LlmException("HTTP " + respuesta.statusCode() + ": " + recorte);
        }
        JsonNode contenido;
        try {
            contenido = json.readTree(cuerpo).path("choices").path(0).path("message").path("content");
        } catch (JsonProcessingException e) {
            throw new LlmException("La respuesta del servidor LLM no es JSON válido", e);
        }
        if (!contenido.isTextual() || contenido.asText().isBlank()) {
            throw new LlmException("La respuesta del servidor LLM no trae texto en choices[0].message.content");
        }
        return contenido.asText().strip();
    }

    /** Devuelve el error como {@link LlmException}, descartando el {@link CompletionException} envolvente. */
    private static LlmException convertir(Throwable error) {
        Throwable causa = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (causa instanceof LlmException llm) {
            return llm;
        }
        String detalle = causa.getClass().getSimpleName()
                + (causa.getMessage() != null ? ": " + causa.getMessage() : "");
        return new LlmException("Falló la consulta al servidor LLM (" + detalle + ")", causa);
    }

    private static String variable(String nombre, String valorPorDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? valorPorDefecto : valor.strip();
    }

    private static String quitarBarrasFinales(String url) {
        String limpia = url.strip();
        while (limpia.endsWith("/")) {
            limpia = limpia.substring(0, limpia.length() - 1);
        }
        return limpia;
    }
}
