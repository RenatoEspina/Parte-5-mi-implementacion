package Agentes;

import Comportamientos.*;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class BookSellerAgent extends BDIAgent {

    private String bookTitle = "ElQuijote";
    private int currentPrice = 100;
    private boolean sold = false;
    private int soldPrice = 0;

    @Override
    protected void setup() {
        setSkills(
            "RESPONDER_COTIZACION (enviar PROPOSE con precio o REFUSE si ya se vendió) | " +
            "RESPONDER_ORDEN_COMPRA (enviar INFORM al concretar venta o FAILURE si no hay stock) | " +
            "CAMBIAR_PRECIO (bajar mi precio si otro vende más barato o subirlo si quedan pocas iteraciones) | " +
            "MANTENER_PRECIO (conservar mi precio actual)"
        );
        setBeliefs("Iteración 1/6 (solo conozco mi propio precio) | Catálogo disponible -> vacío | Historial de interacciones -> ninguna");
        setDesires("Vender el libro de mi catálogo lo más caro posible antes de que termine la iteración 6 para ganar el premio al mejor vendedor.");
        setIntentions("Esperar solicitudes de compradores y ajustar estratégicamente mi precio al final de cada iteración.");

        Object[] args = getArguments();
        if (args != null) {
            for (Object arg : args) {
                String[] parts = arg.toString().split(":");
                if (parts.length == 2) {
                    this.bookTitle = parts[0].trim();
                    this.currentPrice = Integer.parseInt(parts[1].trim());
                    updateCatalogue(this.bookTitle, String.valueOf(this.currentPrice));
                }
            }
        }

        // Registrar servicio en el DF (Páginas Amarillas)
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("book-selling");
        sd.setName("JADE-book-trading");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        addBehaviour(new OfferRequestsServer(this));
        addBehaviour(new PurchaseOrdersServer(this));

        // Registrar en el coordinador de Entorno
        try {
            Class<?> entornoClass = Class.forName("Entorno");
            entornoClass.getMethod("registerSeller", String.class, BookSellerAgent.class, int.class)
                    .invoke(null, getLocalName(), this, this.currentPrice);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateCatalogue(final String title, final String price) {
        addBehaviour(new UpdateCatalogueBehaviour(this, title, price));
    }

    public void notifyIterationStart(int iteration, int maxIter) {
        setIterationContext(iteration, maxIter);
        if (iteration == 1) {
            mutateBeliefs("Inicio de Iteración 1/" + maxIter + ": solo conozco mi propio precio ($" + currentPrice + ").");
        } else {
            mutateBeliefs("Inicio de Iteración " + iteration + "/" + maxIter + " (termina en la " + maxIter + "). Mi precio actual es $" + currentPrice + ".");
        }
    }

    public void triggerEndOfIterationPriceAdjustment(String marketPricesInfo, int iter, int maxIter, Runnable onComplete) {
        addBehaviour(new AdjustPriceBehaviour(this, bookTitle, marketPricesInfo, iter, maxIter, onComplete));
    }

    public synchronized void updatePriceInBeliefs(String title, int newPrice) {
        this.currentPrice = newPrice;
        String updated = getBeliefs().replaceAll(title + ":[0-9]+", title + ":" + newPrice);
        setBeliefs(updated);
    }

    public synchronized void markAsSold(String buyerName) {
        this.sold = true;
        this.soldPrice = this.currentPrice;
    }

    public synchronized int getCurrentPrice() {
        return currentPrice;
    }

    public synchronized boolean isSold() {
        return sold;
    }

    public synchronized int getSoldPrice() {
        return soldPrice;
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            // Ignorar si ya fue desregistrado
        }
        System.out.println("Seller-agent " + getLocalName() + " terminating.");
    }
}