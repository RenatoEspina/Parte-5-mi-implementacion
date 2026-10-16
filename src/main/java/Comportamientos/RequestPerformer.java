package Comportamientos;

import Agentes.*;
import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.LinkedHashMap;
import java.util.Map;

public class RequestPerformer extends Behaviour {
    private final BookBuyerAgent buyerAgent;
    private final String[] sellerNames;

    private final Map<String, Integer> availablePrices = new LinkedHashMap<>();
    private final Map<String, String> worldFeedback = new LinkedHashMap<>();
    private AID chosenSeller;
    private String chosenPrice = "";
    private int repliesCnt = 0;
    private MessageTemplate mt;
    private int step = 0;

    public RequestPerformer(BookBuyerAgent buyerAgent, String[] sellerNames) {
        super(buyerAgent);
        this.buyerAgent = buyerAgent;
        this.sellerNames = sellerNames;
    }

    private void notifyBuyerAction(String description) {
        try {
            Class<?> entornoClass = Class.forName("Entorno");
            entornoClass.getMethod("recordBuyerAction", String.class, String.class)
                    .invoke(null, buyerAgent.getLocalName(), description);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void action() {
        String targetBookTitle = buyerAgent.getTargetBookTitle();
        int currentIter = buyerAgent.getCurrentIteration();
        int maxIter = buyerAgent.getMaxIterations();

        switch (step) {
            case 0:
                // ACCIÓN 1 DE LA ITERACIÓN: Solicitar cotización (CFP)
                ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
                for (String sellerName : sellerNames) {
                    cfp.addReceiver(new AID(sellerName.trim(), AID.ISLOCALNAME));
                }
                cfp.setContent(targetBookTitle);
                cfp.setConversationId("book-trade");
                cfp.setReplyWith("cfp" + System.currentTimeMillis());
                buyerAgent.send(cfp);

                notifyBuyerAction("SOLICITAR_COTIZACION (CFP enviado a vendedores)");

                mt = MessageTemplate.and(
                        MessageTemplate.MatchConversationId("book-trade"),
                        MessageTemplate.MatchInReplyTo(cfp.getReplyWith())
                );
                step = 1;
                break;

            case 1:
                ACLMessage reply = buyerAgent.receive(mt);
                if (reply != null) {
                    String sender = reply.getSender().getLocalName();
                    if (reply.getPerformative() == ACLMessage.PROPOSE) {
                        String priceStr = reply.getContent().trim().replaceAll("[^0-9]", "");
                        int price = Integer.parseInt(priceStr);
                        availablePrices.put(sender, price);
                        worldFeedback.put(sender, sender + " TIENE STOCK de '" + targetBookTitle + "' a precio $" + price);
                    } else {
                        worldFeedback.put(sender, sender + " SIN STOCK (ya lo vendió)");
                    }
                    repliesCnt++;
                    if (repliesCnt >= sellerNames.length) {
                        step = 2;
                    }
                } else {
                    block();
                }
                break;

            case 2:
                StringBuilder worldBeliefBuilder = new StringBuilder();
                for (String state : worldFeedback.values()) {
                    if (worldBeliefBuilder.length() > 0) worldBeliefBuilder.append(" ; ");
                    worldBeliefBuilder.append("[").append(state).append("]");
                }
                String updatedWorldBelief = "Iteración " + currentIter + "/" + maxIter
                        + " (Termina en iteración " + maxIter + "). Estado actual del mundo para '"
                        + targetBookTitle + "': " + worldBeliefBuilder;
                buyerAgent.setBeliefs(updatedWorldBelief);

                // ANALYZE (A): Razonar sobre el mejor precio actual y la iteración en curso
                String analysis = buyerAgent.analyzeWithLlm(
                        updatedWorldBelief,
                        "Estamos en la iteración " + currentIter + " de " + maxIter + ". "
                        + "Compara los vendedores con TIENE STOCK, identifica cuál ofrece el menor precio y evalúa si conviene comprar ahora o esperar a que bajen de precio en la siguiente iteración."
                );
                System.out.println("[" + buyerAgent.getLocalName() + " | MAPE-K Analyze]: " + analysis);

                // PLAN (P)
                String plan = buyerAgent.planWithLlm(
                        analysis,
                        "Si decides comprar en esta iteración " + currentIter + "/" + maxIter + ", responde EXACTAMENTE: SOLICITAR_COMPRA|<NombreVendedor>|<Precio>. "
                        + "Si prefieres esperar a la siguiente iteración (y aún no estás en la última iteración " + maxIter + ") o nadie tiene stock, responde: ESPERAR_ITERACION"
                );
                System.out.println("[" + buyerAgent.getLocalName() + " | MAPE-K Plan]: " + plan);

                // EXECUTE (E): Acción 2 de la iteración
                executeBuyerPlan(plan, targetBookTitle, currentIter, maxIter);
                break;

            case 3:
                reply = buyerAgent.receive(mt);
                if (reply != null) {
                    String sellerName = reply.getSender().getLocalName();
                    if (reply.getPerformative() == ACLMessage.INFORM) {
                        buyerAgent.setBeliefs(
                            buyerAgent.getBeliefs() + " | Retroalimentación final: Compra exitosa de '"
                            + targetBookTitle + "' al agente " + sellerName + " por $" + chosenPrice
                            + " en la iteración " + currentIter + "/" + maxIter + "."
                        );
                        buyerAgent.mutateIntentions("Objetivo cumplido en iteración " + currentIter + ".");
                        buyerAgent.setDesires("");
                        buyerAgent.markBookAsBought();
                        System.out.println(">>> [" + buyerAgent.getLocalName() + "] Compró exitosamente '"
                                + targetBookTitle + "' a " + sellerName + " por $" + chosenPrice
                                + " (Iteración " + currentIter + "/" + maxIter + ")");
                    } else {
                        buyerAgent.mutateBeliefs(
                            "Retroalimentación de " + sellerName + ": falló la compra en iteración " + currentIter + " porque ya lo vendió."
                        );
                        buyerAgent.mutateIntentions("Replantear compra en la siguiente iteración.");
                    }
                    // Notificar Acción 2 completada al entorno
                    notifyBuyerAction("SOLICITAR_COMPRA hacia " + sellerName + " por $" + chosenPrice);
                    step = 4;
                } else {
                    block();
                }
                break;
        }
    }

    private void executeBuyerPlan(String plan, String targetBookTitle, int currentIter, int maxIter) {
        String bestBeliefSeller = null;
        int bestBeliefPrice = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : availablePrices.entrySet()) {
            if (entry.getValue() < bestBeliefPrice) {
                bestBeliefPrice = entry.getValue();
                bestBeliefSeller = entry.getKey();
            }
        }

        if (availablePrices.isEmpty()) {
            buyerAgent.mutateIntentions("Ningún vendedor tiene stock disponible.");
            notifyBuyerAction("ESPERAR_ITERACION (sin stock disponible)");
            step = 4;
            return;
        }

        // Si el LLM eligió explícitamente ESPERAR_ITERACION y aún quedan iteraciones
        if (plan != null && plan.toUpperCase().contains("ESPERAR") && currentIter < maxIter) {
            buyerAgent.mutateIntentions("Ejecutar skill ESPERAR_ITERACION en iteración " + currentIter + "/" + maxIter
                    + " esperando que los vendedores bajen sus precios.");
            notifyBuyerAction("ESPERAR_ITERACION (decidió esperar mejores precios)");
            step = 4;
            return;
        }

        if (plan != null && plan.startsWith("SOLICITAR_COMPRA|")) {
            String[] parts = plan.split("\\|");
            if (parts.length >= 3) {
                String candidate = parts[1].trim();
                if (availablePrices.containsKey(candidate) && availablePrices.get(candidate) == bestBeliefPrice) {
                    chosenSeller = new AID(candidate, AID.ISLOCALNAME);
                    chosenPrice = String.valueOf(bestBeliefPrice);
                }
            }
        }

        if (chosenSeller == null && bestBeliefSeller != null) {
            chosenSeller = new AID(bestBeliefSeller, AID.ISLOCALNAME);
            chosenPrice = String.valueOf(bestBeliefPrice);
            System.out.println("[" + buyerAgent.getLocalName() + " | Alineación BDIS]: Ajustando plan a la creencia óptima -> SOLICITAR_COMPRA|" + bestBeliefSeller + "|" + chosenPrice);
        }

        if (chosenSeller != null) {
            buyerAgent.mutateIntentions(
                "Ejecutar Acción 2 (SOLICITAR_COMPRA) para comprar '" + targetBookTitle
                + "' a " + chosenSeller.getLocalName() + " por $" + chosenPrice
            );

            ACLMessage order = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
            order.addReceiver(chosenSeller);
            order.setContent(targetBookTitle);
            order.setConversationId("book-trade");
            order.setReplyWith("order" + System.currentTimeMillis());
            buyerAgent.send(order);

            mt = MessageTemplate.and(
                    MessageTemplate.MatchConversationId("book-trade"),
                    MessageTemplate.MatchInReplyTo(order.getReplyWith())
            );
            step = 3;
        } else {
            notifyBuyerAction("ESPERAR_ITERACION");
            step = 4;
        }
    }

    @Override
    public boolean done() {
        return step == 4;
    }
}