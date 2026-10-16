package Comportamientos;

import Agentes.*;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class FindSellersTickerBehaviour extends TickerBehaviour {
    private final BookBuyerAgent buyerAgent;
    private boolean negotiationInProgress = false;

    public FindSellersTickerBehaviour(BookBuyerAgent a, long period) {
        super(a, period);
        this.buyerAgent = a;
    }

    public void setNegotiationInProgress(boolean inProgress) {
        this.negotiationInProgress = inProgress;
    }

    @Override
    protected void onTick() {
        if (buyerAgent.hasBoughtBook() || buyerAgent.getDesires().isEmpty()) {
            stop();
            return;
        }

        if (negotiationInProgress) {
            return;
        }

        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("book-selling");
        template.addServices(sd);

        try {
            DFAgentDescription[] result = DFService.search(buyerAgent, template);
            if (result.length > 0) {
                StringBuilder sellersList = new StringBuilder();
                for (int i = 0; i < result.length; ++i) {
                    if (i > 0) sellersList.append(",");
                    sellersList.append(result[i].getName().getLocalName());
                }

                buyerAgent.mutateBeliefs("Iteración " + buyerAgent.getCurrentIteration() + "/" + buyerAgent.getMaxIterations()
                        + " - Vendedores detectados: [" + sellersList + "]");

                negotiationInProgress = true;
                buyerAgent.adoptIntention(
                    "Ejecutar Acción 1 (SOLICITAR_COTIZACION) hacia [" + sellersList + "]",
                    new RequestPerformer(buyerAgent, sellersList.toString().split(","))
                );
                // Detener el ticker de esta iteración; Entorno iniciará el siguiente en la próxima iteración
                stop();
            } else {
                buyerAgent.mutateBeliefs("No se encontraron vendedores en el entorno.");
            }
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }
}