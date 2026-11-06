package Comportamientos;

import Agentes.*;
import jade.core.behaviours.OneShotBehaviour;

public class UpdateCatalogueBehaviour extends OneShotBehaviour {
    private final BookSellerAgent sellerAgent;
    private final String title;
    private final String price;

    public UpdateCatalogueBehaviour(BookSellerAgent sellerAgent, String title, String price) {
        super(sellerAgent);
        this.sellerAgent = sellerAgent;
        this.title = title;
        this.price = price;
    }

    @Override
    public void action() {
        String currentBeliefs = sellerAgent.getBeliefs();
        String entry = "TIENE_STOCK -> " + title + ":" + price;

        if (currentBeliefs.contains("Catálogo disponible -> vacío")) {
            sellerAgent.setBeliefs(currentBeliefs.replace("Catálogo disponible -> vacío", "Catálogo disponible -> " + entry));
        } else {
            sellerAgent.setBeliefs(entry + " | " + currentBeliefs);
        }
    }
}