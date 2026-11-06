package Comportamientos;

import Agentes.BookSellerAgent;
import jade.core.behaviours.OneShotBehaviour;

public class AdjustPriceBehaviour extends OneShotBehaviour {
    private final BookSellerAgent sellerAgent;
    private final String bookTitle;
    private final String marketPricesInfo;
    private final int currentIteration;
    private final int maxIterations;
    private final Runnable onCompleteCallback;

    public AdjustPriceBehaviour(BookSellerAgent sellerAgent, String bookTitle, String marketPricesInfo,
                                int currentIteration, int maxIterations, Runnable onCompleteCallback) {
        super(sellerAgent);
        this.sellerAgent = sellerAgent;
        this.bookTitle = bookTitle;
        this.marketPricesInfo = marketPricesInfo;
        this.currentIteration = currentIteration;
        this.maxIterations = maxIterations;
        this.onCompleteCallback = onCompleteCallback;
    }

    @Override
    public void action() {
        try {
            // Si el vendedor ya vendió su libro, no participa en el cambio de precios
            if (sellerAgent.isSold()) {
                return;
            }

            int remaining = maxIterations - currentIteration;
            int myPrice = sellerAgent.getCurrentPrice();

            // 1. MONITOR (M): Actualizar creencias con los precios revelados al final de la iteración
            String perception = "Fin de la iteración " + currentIteration + "/" + maxIterations
                    + " (quedan " + remaining + " iteraciones). "
                    + "Mi precio actual para '" + bookTitle + "' es $" + myPrice + ". "
                    + "Precios revelados de los otros vendedores en esta iteración: [" + marketPricesInfo + "].";

            sellerAgent.mutateBeliefs("Precios competencia fin iteración " + currentIteration + ": [" + marketPricesInfo + "]");

            // 2. ANALYZE (A): Razonar con el LLM sobre competencia y tiempo restante
            String analysis = sellerAgent.analyzeWithLlm(
                    perception,
                    "Analiza los precios de los otros vendedores frente a mi precio ($" + myPrice + ") y que quedan "
                    + remaining + " iteraciones para terminar en la iteración " + maxIterations + ". "
                    + "Si otro vende más barato, evalúa bajar el precio para lograr vender; si quedan pocas iteraciones o pocos competidores con stock, evalúa subirlo o mantenerlo para ganar el premio al vendedor que vende más caro."
            );
            System.out.println("[" + sellerAgent.getLocalName() + " | MAPE-K Analyze Precio]: " + analysis);

            // 3. PLAN (P): Trazar el plan de cambio de precio
            String plan = sellerAgent.planWithLlm(
                    analysis,
                    "Usa tu skill CAMBIAR_PRECIO o MANTENER_PRECIO según tu propio pensamiento estratégico. "
                    + "Responde EXACTAMENTE en este formato: CAMBIAR_PRECIO|<NuevoPrecioEntero> o MANTENER_PRECIO|" + myPrice
            );
            System.out.println("[" + sellerAgent.getLocalName() + " | MAPE-K Plan Precio]: " + plan);

            // 4. EXECUTE (E): Aplicar el nuevo precio en las creencias del vendedor
            executePriceChange(plan, myPrice);
        } finally {
            if (onCompleteCallback != null) {
                onCompleteCallback.run();
            }
        }
    }

    private void executePriceChange(String plan, int oldPrice) {
        int newPrice = oldPrice;
        if (plan != null && plan.contains("CAMBIAR_PRECIO|")) {
            String[] parts = plan.split("\\|");
            if (parts.length >= 2) {
                String numStr = parts[1].replaceAll("[^0-9]", "").trim();
                if (!numStr.isEmpty()) {
                    int parsed = Integer.parseInt(numStr);
                    if (parsed > 0) {
                        newPrice = parsed;
                    }
                }
            }
        }

        if (newPrice != oldPrice) {
            sellerAgent.updatePriceInBeliefs(bookTitle, newPrice);
            sellerAgent.mutateIntentions("Ejecutar skill CAMBIAR_PRECIO: ajusté mi precio de $" + oldPrice
                    + " a $" + newPrice + " tras finalizar la iteración " + currentIteration);
            System.out.println(">>> [" + sellerAgent.getLocalName() + "] Cambió su precio de '"
                    + bookTitle + "': $" + oldPrice + " -> $" + newPrice);
        } else {
            sellerAgent.mutateIntentions("Mantener mi precio en $" + oldPrice + " para la siguiente iteración.");
            System.out.println(">>> [" + sellerAgent.getLocalName() + "] Mantiene su precio de '"
                    + bookTitle + "' en $" + oldPrice);
        }
    }
}