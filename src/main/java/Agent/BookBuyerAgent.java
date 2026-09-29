package Agent;

import jade.core.Agent;

public class BookBuyerAgent extends Agent{
    protected void setup(){
        System.out.println("¡Hola! El agente comprador" +getAID().getName()+ "está listo.");
    }
}
