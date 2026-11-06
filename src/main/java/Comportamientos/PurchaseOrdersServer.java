package Comportamientos;

import Agentes.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.regex.Matcher;

public class PurchaseOrdersServer extends MapekCyclicBehaviour {
    private final BookSellerAgent sellerAgent;
    private ACLMessage currentMsg;

    public PurchaseOrdersServer(BookSellerAgent sellerAgent) {
        super(sellerAgent);
        this.sellerAgent = sellerAgent;
    }

    @Override
    protected String monitor() {
        MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
        currentMsg = sellerAgent.receive(mt);
        if (currentMsg != null) {
            return "Iteración " + sellerAgent.getCurrentIteration() + "/" + sellerAgent.getMaxIterations()
                    + ": El comprador '" + currentMsg.getSender().getLocalName()
                    + "' envió una orden de compra (ACCEPT_PROPOSAL) para '" + currentMsg.getContent() + "'.";
        }
        return null;
    }

    @Override
    protected String analyze(String perceptionPrompt) {
        return sellerAgent.analyzeWithLlm(
                perceptionPrompt,
                "Revisa mis BELIEFS: si dice TIENE_STOCK para '" + currentMsg.getContent() + "', confirma que sigue disponible para concretar la venta."
        );
    }

    @Override
    protected String plan(String analysisPrompt) {
        return sellerAgent.planWithLlm(
                analysisPrompt,
                "Usa únicamente tu skill RESPONDER_ORDEN_COMPRA. Si sigue en mi catálogo (TIENE_STOCK), responde EXACTAMENTE: RESPONDER_ORDEN_COMPRA|INFORM|vendido. Si ya no está (SIN_STOCK), responde: RESPONDER_ORDEN_COMPRA|FAILURE|sin-stock"
        );
    }

    @Override
    protected void execute(String planPrompt) {
        ACLMessage reply = currentMsg.createReply();
        String book = currentMsg.getContent().trim();
        String buyerName = currentMsg.getSender().getLocalName();
        String currentBeliefs = sellerAgent.getBeliefs();

        if (!sellerAgent.isSold() && currentBeliefs.contains(book + ":")) {
            int salePrice = sellerAgent.getCurrentPrice();
            sellerAgent.markAsSold(buyerName);

            // Matcher.quoteReplacement evita el IndexOutOfBoundsException por el signo '$' en el reemplazo regex
            String replacement = Matcher.quoteReplacement("SIN_STOCK (" + book + " ya vendido a " + buyerName + " por $" + salePrice + ")");
            String updatedBeliefs = currentBeliefs.replaceAll("TIENE_STOCK -> " + book + ":[0-9]+", replacement);
            sellerAgent.setBeliefs(updatedBeliefs);

            sellerAgent.mutateIntentions("Libro '" + book + "' vendido a " + buyerName + " por $" + salePrice + ". Rechazar futuras cotizaciones.");
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("vendido");
            System.out.println(">>> [" + sellerAgent.getLocalName() + "] Vendió '" + book + "' a " + buyerName + " por $" + salePrice);

            notifySaleToEntorno(buyerName, book, salePrice);
            recordSellerAction("RESPONDER_ORDEN_COMPRA (INFORM vendido a " + buyerName + " por $" + salePrice + ")");
        } else {
            sellerAgent.mutateIntentions("Rechazar orden de compra de " + buyerName + " usando RESPONDER_ORDEN_COMPRA porque ya no hay stock.");
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("not-available");
            recordSellerAction("RESPONDER_ORDEN_COMPRA (FAILURE sin-stock a " + buyerName + ")");
        }
        sellerAgent.send(reply);
    }

    private void notifySaleToEntorno(String buyerName, String book, int price) {
        try {
            Class<?> entornoClass = Class.forName("Entorno");
            entornoClass.getMethod("recordSale", String.class, String.class, String.class, int.class)
                    .invoke(null, sellerAgent.getLocalName(), buyerName, book, price);
        } catch (Exception e) {
            e.printStackTrace();
        }
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