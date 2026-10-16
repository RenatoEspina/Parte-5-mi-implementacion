package Agentes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class LlmClient {
    private static final String VLLM_URL = "http://127.0.0.1:8000/v1/chat/completions";
    private static final String MODEL_NAME = "market-llm";
    
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public LlmClient() {
        // Forzar HTTP_1_1 evita que Uvicorn/FastAPI en vLLM pierda el body al intentar upgrade a HTTP/2
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public String askLlm(String systemPrompt, String userPrompt) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", MODEL_NAME);
            payload.put("temperature", 0.1);
            payload.put("max_tokens", 150);

            // Desactivar el bloque <think> en Qwen3.5 para obtener respuestas directas y rápidas
            ObjectNode chatTemplateKwargs = payload.putObject("chat_template_kwargs");
            chatTemplateKwargs.put("enable_thinking", false);

            ArrayNode messages = payload.putArray("messages");
            
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);

            ObjectNode usrMsg = messages.addObject();
            usrMsg.put("role", "user");
            usrMsg.put("content", userPrompt);

            String requestBody = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VLLM_URL))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                String content = root.path("choices").get(0).path("message").path("content").asText();
                // Limpiar etiquetas <think> en caso de que el modelo las incluya de todos modos
                return content.replaceAll("(?s)<think>.*?</think>", "").trim();
            } else {
                System.err.println("[LLM Error HTTP " + response.statusCode() + "]: " + response.body());
            }
        } catch (Exception e) {
            System.err.println("[LLM Exception]: No se pudo conectar a vLLM (" + e.getMessage() + ")");
        }
        return "ERROR";
    }
}