package Comportamientos;

import Agentes.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class OfferRequestsServer extends MapekCyclicBehaviour {
    private final BookSellerAgent sellerAgent;
    private ACLMessage currentMsg;

    public OfferRequestsServer(BookSellerAgent sellerAgent) {
        super(sellerAgent);
        this.sellerAgent = sellerAgent;
    }

    @Override
    protected String monitor() {
        MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
        currentMsg = sellerAgent.receive(mt);
        if (currentMsg != null) {
            String buyerName = currentMsg.getSender().getLocalName();
            String book = currentMsg.getContent().trim();
            sellerAgent.mutateBeliefs("Iteración " + sellerAgent.getCurrentIteration() + "/" + sellerAgent.getMaxIterations()
                    + ": '" + buyerName + "' solicita cotización por '" + book + "'");
            return "Estamos en la iteración " + sellerAgent.getCurrentIteration() + " de " + sellerAgent.getMaxIterations()
                    + ". El agente '" + buyerName + "' solicita cotización (CFP) para el libro '" + book + "'.";
        }
        return null;
    }

    @Override
    protected String analyze(String perceptionPrompt) {
        return sellerAgent.analyzeWithLlm(
                perceptionPrompt,
                "Verifica en mis BELIEFS: si aparece 'TIENE_STOCK -> " + currentMsg.getContent() + ":<precio>', indica que SÍ tengo el libro en stock y menciona su precio. Si dice 'SIN_STOCK', indica que ya fue vendido."
        );
    }

    @Override
    protected String plan(String analysisPrompt) {
        return sellerAgent.planWithLlm(
                analysisPrompt,
                "Usa únicamente tu skill RESPONDER_COTIZACION. Si el libro está disponible en tus BELIEFS, responde EXACTAMENTE: RESPONDER_COTIZACION|PROPOSE|<precio>. Si ya lo vendiste o no está, responde: RESPONDER_COTIZACION|REFUSE|ya-vendido"
        );
    }

    @Override
    protected void execute(String planPrompt) {
        ACLMessage reply = currentMsg.createReply();
        String book = currentMsg.getContent().trim();
        String buyerName = currentMsg.getSender().getLocalName();

        if (!sellerAgent.isSold() && sellerAgent.getBeliefs().contains(book + ":")) {
            String price = String.valueOf(sellerAgent.getCurrentPrice());
            sellerAgent.mutateIntentions("Ejecutar skill RESPONDER_COTIZACION ofreciendo '" + book + "' a " + buyerName + " por $" + price);
            reply.setPerformative(ACLMessage.PROPOSE);
            reply.setContent(price);
            recordSellerAction("RESPONDER_COTIZACION (PROPOSE $" + price + " a " + buyerName + ")");
        } else {
            sellerAgent.mutateIntentions("Ejecutar skill RESPONDER_COTIZACION informando a " + buyerName + " que '" + book + "' ya fue vendido");
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent("ya-vendido");
            recordSellerAction("RESPONDER_COTIZACION (REFUSE sin-stock a " + buyerName + ")");
        }
        sellerAgent.send(reply);
    }

    private void recordSellerAction(String actionDesc) {
        try {
            Class<?> entornoClass = Class.forName("Entorno");
            entornoClass.getMethod("recordSellerAction", String.class, String.class)
                    .invoke(null, sellerAgent.getLocalName(), actionDesc);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}