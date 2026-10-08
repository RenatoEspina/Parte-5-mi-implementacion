package Llm;

import jade.content.lang.Codec;
import jade.content.lang.sl.SLCodec;
import jade.content.onto.OntologyException;
import jade.content.onto.basic.Action;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.JADEAgentManagement.JADEManagementOntology;
import jade.domain.JADEAgentManagement.ShutdownPlatform;
import jade.lang.acl.ACLMessage;

import java.util.List;

/**
 * Agente de prueba del enlace JADE - vLLM. Hace una consulta de texto libre y otra restringida por
 * regex, y apaga la plataforma al terminar (o al primer error). Mientras espera, un comportamiento
 * periódico sigue imprimiendo: así se comprueba que la consulta no bloquea al agente.
 *
 * <p>Uso: {@code mvn -q compile exec:java -Dexec.args="-agents prueba:Llm.AgentePruebaLlm"}
 */
public class AgentePruebaLlm extends Agent {

    /** El servidor responde en pocas decenas de milisegundos; un periodo corto hace visible el latido. */
    private static final long PERIODO_LATIDO_MS = 20;
    private static final String REGEX_DECISION = "(ACEPTAR|RECHAZAR|CONTRAOFERTA\\{[0-9]{1,3}\\})";

    private final Codec codec = new SLCodec();
    private ClienteLlm cliente;
    private TickerBehaviour latido;

    @Override
    protected void setup() {
        getContentManager().registerLanguage(codec);
        getContentManager().registerOntology(JADEManagementOntology.getInstance());
        cliente = ClienteLlm.desdeEntorno();

        latido = new TickerBehaviour(this, PERIODO_LATIDO_MS) {
            @Override
            protected void onTick() {
                System.out.println("[prueba] el agente sigue activo mientras el LLM responde");
            }
        };
        addBehaviour(latido);

        addBehaviour(new ConsultaLlmBehaviour(this, cliente, List.of(
                ClienteLlm.Mensaje.sistema("Eres un vendedor de libros. Respondes siempre en una sola frase."),
                ClienteLlm.Mensaje.usuario("Un comprador ofrece 20 por el libro \"Dune\". "
                        + "Tu precio mínimo es 25. ¿Qué le respondes?"))) {
            @Override
            protected void alResponder(String respuesta) {
                System.out.println("[prueba] respuesta libre: " + respuesta);
                consultarDecision();
            }

            @Override
            protected void alFallar(LlmException error) {
                informarErrorYApagar(error);
            }
        });
    }

    private void consultarDecision() {
        addBehaviour(new ConsultaLlmBehaviour(this, cliente, List.of(
                ClienteLlm.Mensaje.sistema("Eres un vendedor de libros. Decides sobre ofertas y respondes "
                        + "solo con la decisión: ACEPTAR, RECHAZAR o CONTRAOFERTA{precio}."),
                ClienteLlm.Mensaje.usuario("Libro \"Dune\", precio mínimo 25. El comprador ofrece 20.")),
                REGEX_DECISION) {
            @Override
            protected void alResponder(String respuesta) {
                System.out.println("[prueba] respuesta con regex: " + respuesta);
                apagarPlataforma();
            }

            @Override
            protected void alFallar(LlmException error) {
                informarErrorYApagar(error);
            }
        });
    }

    private void informarErrorYApagar(LlmException error) {
        System.err.println("[prueba] error: " + error.getMessage());
        apagarPlataforma();
    }

    /** Pide al AMS que apague la plataforma; con {@code jade.Boot} esto termina la JVM. */
    private void apagarPlataforma() {
        latido.stop();
        ACLMessage mensaje = new ACLMessage(ACLMessage.REQUEST);
        mensaje.addReceiver(getAMS());
        mensaje.setLanguage(codec.getName());
        mensaje.setOntology(JADEManagementOntology.getInstance().getName());
        try {
            getContentManager().fillContent(mensaje, new Action(getAMS(), new ShutdownPlatform()));
            send(mensaje);
        } catch (Codec.CodecException | OntologyException e) {
            System.err.println("[prueba] no se pudo pedir el apagado de la plataforma: " + e.getMessage());
            doDelete();
        }
    }
}
