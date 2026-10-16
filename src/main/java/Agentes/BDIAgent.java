package Agentes;

import jade.core.Agent;
import jade.core.behaviours.Behaviour;

public abstract class BDIAgent extends Agent {

    // K (Knowledge Base de MAPE-K) extendido a BDIS (Beliefs, Desires, Intentions, Skills)
    protected String beliefs = "";
    protected String desires = "";
    protected String intentions = "";
    protected String skills = "";

    // Conciencia de iteraciones
    protected int currentIteration = 1;
    protected int maxIterations = 6;

    protected LlmClient llmClient = new LlmClient();

    public void setIterationContext(int currentIteration, int maxIterations) {
        this.currentIteration = currentIteration;
        this.maxIterations = maxIterations;
    }

    public int getCurrentIteration() {
        return currentIteration;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    // --- Gestión y Mutación de BDIS ---
    public String getBeliefs() {
        return beliefs;
    }

    public void setBeliefs(String beliefsPrompt) {
        this.beliefs = beliefsPrompt;
        logStateMutation("BELIEFS", this.beliefs);
    }

    public void mutateBeliefs(String feedbackInfo) {
        if (this.beliefs.isEmpty()) {
            this.beliefs = feedbackInfo;
        } else if (this.beliefs.contains("Historial de interacciones -> ninguna")) {
            this.beliefs = this.beliefs.replace(
                "Historial de interacciones -> ninguna",
                "Historial de interacciones -> " + feedbackInfo
            );
        } else {
            this.beliefs += " | " + feedbackInfo;
        }
        logStateMutation("BELIEFS", this.beliefs);
    }

    public String getDesires() {
        return desires;
    }

    public void setDesires(String desiresPrompt) {
        this.desires = desiresPrompt;
    }

    public String getIntentions() {
        return intentions;
    }

    public void setIntentions(String intentionsPrompt) {
        this.intentions = intentionsPrompt;
        logStateMutation("INTENTIONS", this.intentions);
    }

    public void mutateIntentions(String newIntentionPlan) {
        this.intentions = newIntentionPlan;
        logStateMutation("INTENTIONS", this.intentions);
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skillsPrompt) {
        this.skills = skillsPrompt;
    }

    public void adoptIntention(String intentionPrompt, Behaviour behaviour) {
        mutateIntentions(intentionPrompt);
        addBehaviour(behaviour);
    }

    private void logStateMutation(String component, String newValue) {
        System.out.println("[" + getLocalName() + " | Iter " + currentIteration + "/" + maxIterations + " | Mutación " + component + "]: " + newValue);
    }

    // --- K (Knowledge Base): Rescata todo el contexto BDIS + Conciencia de Iteración ---
    public String getKnowledgePrompt() {
        int remaining = Math.max(0, maxIterations - currentIteration);
        return "=== KNOWLEDGE BASE (BDIS) ===\n"
                + "CONTEXTO TEMPORAL: Estás en la Iteración " + currentIteration + " de un máximo de " + maxIterations
                + " (El mercado termina en la iteración " + maxIterations + ", quedan " + remaining + " iteraciones después de esta).\n"
                + "BELIEFS (Lo que creo del mundo): " + beliefs + "\n"
                + "DESIRES (Mi objetivo): " + desires + "\n"
                + "INTENTIONS (Intención actual): " + intentions + "\n"
                + "SKILLS (Habilidades permitidas): " + skills + "\n";
    }

    // --- A (Analyze) de MAPE-K ---
    public String analyzeWithLlm(String monitorPerceptionPrompt, String analysisTaskPrompt) {
        String systemPrompt = "Eres el módulo ANALYZE (MAPE-K) de un agente BDIS consciente de la iteración actual y de cuándo termina la simulación. "
                + "Analiza en una sola oración la percepción frente a tus BELIEFS, DESIRES, INTENTIONS, SKILLS y el tiempo restante.";
        String userPrompt = getKnowledgePrompt()
                + "\n[MONITOR / RETROALIMENTACIÓN]: " + monitorPerceptionPrompt
                + "\n[TAREA DE ANÁLISIS]: " + analysisTaskPrompt;
        return llmClient.askLlm(systemPrompt, userPrompt);
    }

    // --- P (Plan) de MAPE-K ---
    public String planWithLlm(String analysisResultPrompt, String planFormatInstructions) {
        String systemPrompt = "Eres el módulo PLAN (MAPE-K) de un agente BDIS. "
                + "Debes plantear un plan usando ÚNICAMENTE tus SKILLS registradas para alcanzar tus DESIRES según tus BELIEFS y la iteración actual. "
                + "Responde ÚNICAMENTE con el comando exacto solicitado, sin texto adicional.";
        String userPrompt = getKnowledgePrompt()
                + "\n[ANÁLISIS]: " + analysisResultPrompt
                + "\n[FORMATO DE PLAN]: " + planFormatInstructions;
        return llmClient.askLlm(systemPrompt, userPrompt);
    }
}