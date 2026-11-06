package Agentes;

import Comportamientos.*;

public class BookBuyerAgent extends BDIAgent {
    private String targetBookTitle;
    private boolean boughtBook = false;

    @Override
    protected void setup() {
        System.out.println("Hallo! AI Buyer-agent " + getLocalName() + " is ready.");

        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            targetBookTitle = args[0].toString().trim();

            // Inicializar arquitectura BDIS consciente de las iteraciones y del premio
            setSkills(
                "SOLICITAR_COTIZACION (Acción 1: enviar CFP a vendedores) | " +
                "SOLICITAR_COMPRA (Acción 2: enviar ACCEPT_PROPOSAL al vendedor más barato) | " +
                "ESPERAR_ITERACION (Acción 2 alternativa: no comprar aún si quedan iteraciones para que los vendedores bajen precios)"
            );
            setBeliefs("Iteración 1/6. Libro buscado: '" + targetBookTitle + "'. Estado del mundo: aún no conozco los precios ni el stock de los vendedores.");
            setDesires("Comprar el libro '" + targetBookTitle + "' al menor precio posible antes de que termine la iteración 6 para ganar el premio al comprador que compró más barato.");
            setIntentions("Esperar inicio de iteración para ejecutar mis 2 acciones.");

            try {
                Class<?> entornoClass = Class.forName("Entorno");
                entornoClass.getMethod("registerBuyer", String.class, BookBuyerAgent.class)
                        .invoke(null, getLocalName(), this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("No target book title specified");
            doDelete();
        }
    }

    public void startIterationTurn(int iteration, int maxIter) {
        setIterationContext(iteration, maxIter);
        if (!boughtBook) {
            adoptIntention(
                "Iteración " + iteration + "/" + maxIter + ": Descubrir vendedores y ejecutar mis 2 acciones de esta iteración",
                new FindSellersTickerBehaviour(this, 100)
            );
        }
    }

    public String getTargetBookTitle() {
        return targetBookTitle;
    }

    public synchronized boolean hasBoughtBook() {
        return boughtBook;
    }

    public synchronized void markBookAsBought() {
        this.boughtBook = true;
    }

    @Override
    protected void takeDown() {
        System.out.println("Buyer-agent " + getLocalName() + " terminating. Final Beliefs: [" + getBeliefs() + "]");
    }
}