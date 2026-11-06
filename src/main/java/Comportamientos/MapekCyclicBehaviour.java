package Comportamientos;

import Agentes.*;

import jade.core.behaviours.CyclicBehaviour;

public abstract class MapekCyclicBehaviour extends CyclicBehaviour {
    protected final BDIAgent bdiAgent;

    public MapekCyclicBehaviour(BDIAgent agent) {
        super(agent);
        this.bdiAgent = agent;
    }

    @Override
    public final void action() {
        // 1. MONITOR (M): Capturar evento/mensaje del entorno como prompt
        String perception = monitor();
        if (perception == null) {
            block();
            return;
        }

        // 2. ANALYZE (A): Razonar sobre la percepción y el Knowledge Base (K - BDI)
        String analysis = analyze(perception);
        System.out.println("[" + bdiAgent.getLocalName() + " | MAPE-K Analyze]: " + analysis);

        // 3. PLAN (P): Trazar el plan de acción con el LLM
        String plan = plan(analysis);
        System.out.println("[" + bdiAgent.getLocalName() + " | MAPE-K Plan]: " + plan);

        // 4. EXECUTE (E): Ejecutar el plan en el entorno JADE y actualizar K
        execute(plan);
    }

    protected abstract String monitor();
    protected abstract String analyze(String perceptionPrompt);
    protected abstract String plan(String analysisPrompt);
    protected abstract void execute(String planPrompt);
}