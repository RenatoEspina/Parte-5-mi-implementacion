# Agentes JADE con LLM local

Este proyecto conecta agentes JADE con un servidor vLLM local que expone la API compatible con OpenAI.
El paquete `Llm` entrega el transporte (`ClienteLlm`, `ConsultaLlmBehaviour`) para que los agentes consulten al modelo sin bloquearse.

## Servidor vLLM

```bash
docker compose up -d --wait      # inicia y espera a que el servidor esté listo
docker compose logs -f vllm      # sigue el registro
docker compose down              # detiene y elimina el contenedor
```

La API queda en `http://127.0.0.1:8000/v1` y el modelo se sirve con el alias `market-llm`.
El primer arranque compila kernels JIT (de 1,5 a 2,5 minutos aproximadamente). Los siguientes tardan cerca de 40 s porque las cachés viven en volúmenes de Docker.

| Variable | Valor por defecto | Efecto |
| --- | --- | --- |
| `VLLM_MODEL` | `Qwen/Qwen3.5-0.8B` | Modelo que se sirve |
| `VLLM_GPU_MEMORY_UTILIZATION` | `0.5` | Fracción de la VRAM que usa vLLM |
| `VLLM_MAX_MODEL_LEN` | `8192` | Longitud máxima de contexto |
| `VLLM_MAX_NUM_SEQS` | `8` | Solicitudes concurrentes máximas |

El servidor trabaja sin conexión. Para descargar un modelo que no esté en la caché hay que iniciarlo una vez con `HF_HUB_OFFLINE=0`, por ejemplo `HF_HUB_OFFLINE=0 VLLM_MODEL=<repo> docker compose up -d --wait`.

## Uso desde un agente

```java
ClienteLlm cliente = ClienteLlm.desdeEntorno();   // una instancia puede compartirse entre agentes

addBehaviour(new ConsultaLlmBehaviour(this, cliente, List.of(
        ClienteLlm.Mensaje.sistema("Eres un vendedor de libros."),
        ClienteLlm.Mensaje.usuario("Ofrecen 20 por Dune. ¿Qué respondes?"))) {
    @Override
    protected void alResponder(String respuesta) {
        // se ejecuta en el hilo del agente: no requiere sincronización
    }

    @Override
    protected void alFallar(LlmException error) {
        // opcional; por defecto solo escribe el error en stderr
    }
});
```

No se debe llamar a `cliente.completar(...)` dentro de `action()`: bloquea el único hilo del agente y detiene todos sus comportamientos.
Para forzar el formato de la respuesta se pasa una expresión regular como cuarto argumento del constructor (o como segundo de `completar` y `completarAsync`); el servidor genera únicamente texto que la cumpla.

## Variables de entorno del cliente

| Variable | Valor por defecto |
| --- | --- |
| `LLM_BASE_URL` | `http://127.0.0.1:8000/v1` |
| `LLM_MODEL` | `market-llm` |
| `LLM_API_KEY` | sin valor (si existe se envía como `Authorization: Bearer`) |
| `LLM_TEMPERATURE` | `0.7` |
| `LLM_MAX_TOKENS` | `256` |
| `LLM_TIMEOUT_SECONDS` | `60` |

Los valores vacíos se tratan como no definidos.

## Prueba rápida

Con el servidor iniciado:

```bash
mvn -q compile exec:java -Dexec.args="-agents prueba:Llm.AgentePruebaLlm"
```

Se espera una serie de líneas `[prueba] el agente sigue activo mientras el LLM responde` (prueba de que el agente no se bloquea), luego `[prueba] respuesta libre: ...` y `[prueba] respuesta con regex: ...` (una de `ACEPTAR`, `RECHAZAR` o `CONTRAOFERTA{n}`). Después la plataforma se apaga y el comando termina.
Si el servidor no responde, el agente imprime `[prueba] error: ...` en stderr y también termina. Los mensajes INFO de JADE en stderr son normales.
