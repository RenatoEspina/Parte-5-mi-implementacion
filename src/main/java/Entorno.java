import Agentes.BookBuyerAgent;
import Agentes.BookSellerAgent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Entorno {

    public static final int MAX_ITERATIONS = 6;
    public static final int EXPECTED_BUYERS = 2;
    public static final int ACTIONS_PER_AGENT_PER_ITERATION = 2;

    private static volatile int currentIteration = 1;

    // Registro de agentes activos para coordinar los turnos
    private static final Map<String, BookSellerAgent> sellerAgents = new ConcurrentHashMap<>();
    private static final Map<String, BookBuyerAgent> buyerAgents = new ConcurrentHashMap<>();

    // Conteo de acciones en la iteración actual
    private static final Map<String, Integer> buyerActionsInIter = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sellerActionsInIter = new ConcurrentHashMap<>();
    private static final Set<String> buyersFinishedInIter = ConcurrentHashMap.newKeySet();

    private static CountDownLatch iterationLatch;

    // Registro histórico para la tabla final
    public static class TradeRecord {
        public final String seller;
        public final String buyer;
        public final String book;
        public final int price;
        public final int iteration;

        public TradeRecord(String seller, String buyer, String book, int price, int iteration) {
            this.seller = seller;
            this.buyer = buyer;
            this.book = book;
            this.price = price;
            this.iteration = iteration;
        }
    }

    private static final List<TradeRecord> tradeHistory = new ArrayList<>();
    private static final Map<String, Integer> initialSellerPrices = new ConcurrentHashMap<>();

    public static int getCurrentIteration() {
        return currentIteration;
    }

    public static int getMaxIterations() {
        return MAX_ITERATIONS;
    }

    public static void registerSeller(String name, BookSellerAgent agent, int initialPrice) {
        sellerAgents.put(name, agent);
        initialSellerPrices.put(name, initialPrice);
    }

    public static void registerBuyer(String name, BookBuyerAgent agent) {
        buyerAgents.put(name, agent);
    }

    public static synchronized void recordSellerAction(String sellerName, String actionDescription) {
        int count = sellerActionsInIter.getOrDefault(sellerName, 0) + 1;
        sellerActionsInIter.put(sellerName, count);
        System.out.println("   [Acción Seller | Iteración " + currentIteration + "/" + MAX_ITERATIONS + "] "
                + sellerName + " realizó acción #" + count + ": " + actionDescription);
    }

    public static synchronized void recordBuyerAction(String buyerName, String actionDescription) {
        int count = buyerActionsInIter.getOrDefault(buyerName, 0) + 1;
        buyerActionsInIter.put(buyerName, count);
        System.out.println("   [Acción Comprador | Iteración " + currentIteration + "/" + MAX_ITERATIONS + "] "
                + buyerName + " realizó acción (" + count + "/" + ACTIONS_PER_AGENT_PER_ITERATION + "): " + actionDescription);

        if (count >= ACTIONS_PER_AGENT_PER_ITERATION) {
            buyersFinishedInIter.add(buyerName);
            if (buyersFinishedInIter.size() >= EXPECTED_BUYERS && iterationLatch != null) {
                iterationLatch.countDown();
            }
        }
    }

    public static synchronized void recordSale(String seller, String buyer, String book, int price) {
        tradeHistory.add(new TradeRecord(seller, buyer, book, price, currentIteration));
    }

    public static void main(String[] args) {
        try {
            Runtime rt = Runtime.instance();
            rt.setCloseVM(true);

            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, "localhost");
            profile.setParameter(Profile.GUI, "false");

            AgentContainer mainContainer = rt.createMainContainer(profile);
            System.out.println("=== ENTORNO MAS-LLM (BDIS + MAPE-K) CON " + MAX_ITERATIONS + " ITERACIONES INICIADO ===");

            AgentController seller1 = mainContainer.createNewAgent(
                    "seller1", "Agentes.BookSellerAgent", new Object[]{"ElQuijote:100"}
            );
            AgentController seller2 = mainContainer.createNewAgent(
                    "seller2", "Agentes.BookSellerAgent", new Object[]{"ElQuijote:120"}
            );
            AgentController seller3 = mainContainer.createNewAgent(
                    "seller3", "Agentes.BookSellerAgent", new Object[]{"ElQuijote:140"}
            );

            seller1.start();
            seller2.start();
            seller3.start();

            Thread.sleep(1500);

            AgentController buyer1 = mainContainer.createNewAgent(
                    "buyer1", "Agentes.BookBuyerAgent", new Object[]{"ElQuijote"}
            );
            AgentController buyer2 = mainContainer.createNewAgent(
                    "buyer2", "Agentes.BookBuyerAgent", new Object[]{"ElQuijote"}
            );

            buyer1.start();
            buyer2.start();

            // Esperar a que los 5 agentes se registren en el coordinador
            while (sellerAgents.size() < 3 || buyerAgents.size() < EXPECTED_BUYERS) {
                Thread.sleep(200);
            }
            Thread.sleep(500);

            // Bucle principal de las 6 iteraciones
            for (int iter = 1; iter <= MAX_ITERATIONS; iter++) {
                currentIteration = iter;
                buyerActionsInIter.clear();
                sellerActionsInIter.clear();
                buyersFinishedInIter.clear();
                iterationLatch = new CountDownLatch(1);

                System.out.println("\n======================================================================");
                System.out.println("   INICIANDO ITERACIÓN " + iter + " DE " + MAX_ITERATIONS
                        + " (Termina en la iteración " + MAX_ITERATIONS + ")");
                System.out.println("======================================================================");

                // Notificar a todos los agentes en qué iteración están
                for (BookSellerAgent seller : sellerAgents.values()) {
                    seller.notifyIterationStart(iter, MAX_ITERATIONS);
                }

                // Lanzar las 2 acciones de cada comprador activo en esta iteración
                for (BookBuyerAgent buyer : buyerAgents.values()) {
                    if (buyer.hasBoughtBook()) {
                        // Si ya compró en una iteración pasada, se marcan sus 2 acciones como completadas
                        recordBuyerAction(buyer.getLocalName(), "Sin acción (libro ya comprado previamente)");
                        recordBuyerAction(buyer.getLocalName(), "Sin acción (libro ya comprado previamente)");
                    } else {
                        buyer.startIterationTurn(iter, MAX_ITERATIONS);
                    }
                }

                // Esperar hasta que ambos agentes completen sus 2 acciones en la iteración
                iterationLatch.await(60, TimeUnit.SECONDS);

                System.out.println("\n--- FIN DE ACCIONES DE LA ITERACIÓN " + iter + " DE " + MAX_ITERATIONS + " ---");

                // Verificar si todos los compradores ya compraron su libro
                boolean allBuyersDone = buyerAgents.values().stream().allMatch(BookBuyerAgent::hasBoughtBook);
                if (allBuyersDone) {
                    System.out.println(">>> Todos los compradores han realizado su compra. Finalizando mercado en la iteración " + iter + ".");
                    break;
                }

                // Al final de la iteración (si quedan iteraciones), los vendedores toman conciencia de los precios de los demás y pueden cambiar su precio
                if (iter < MAX_ITERATIONS) {
                    System.out.println("\n--- FASE DE CONCIENCIA DE MERCADO Y AJUSTE DE PRECIOS (FIN ITERACIÓN " + iter + ") ---");
                    CountDownLatch priceAdjustLatch = new CountDownLatch(sellerAgents.size());

                    for (BookSellerAgent seller : sellerAgents.values()) {
                        StringBuilder otherPrices = new StringBuilder();
                        for (BookSellerAgent other : sellerAgents.values()) {
                            if (!other.getLocalName().equals(seller.getLocalName())) {
                                if (otherPrices.length() > 0) otherPrices.append(" ; ");
                                if (other.isSold()) {
                                    otherPrices.append(other.getLocalName())
                                               .append(": YA VENDIÓ a $").append(other.getSoldPrice());
                                } else {
                                    otherPrices.append(other.getLocalName())
                                               .append(": vende a $").append(other.getCurrentPrice());
                                }
                            }
                        }
                        seller.triggerEndOfIterationPriceAdjustment(otherPrices.toString(), iter, MAX_ITERATIONS, priceAdjustLatch::countDown);
                    }

                    priceAdjustLatch.await(60, TimeUnit.SECONDS);
                }
            }

            // Imprimir la tabla final de premiación
            printFinalAwardsTable();

            // Terminar agentes limpiamente
            for (BookBuyerAgent b : buyerAgents.values()) {
                b.doDelete();
            }
            for (BookSellerAgent s : sellerAgents.values()) {
                s.doDelete();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printFinalAwardsTable() {
        TradeRecord bestSellerTrade = tradeHistory.stream()
                .max(Comparator.comparingInt(t -> t.price))
                .orElse(null);

        TradeRecord bestBuyerTrade = tradeHistory.stream()
                .min(Comparator.comparingInt(t -> t.price))
                .orElse(null);

        System.out.println("\n==========================================================================================");
        System.out.println("                     TABLA FINAL DE RESULTADOS Y PREMIACIÓN (MÁX 6 ITERACIONES)           ");
        System.out.println("==========================================================================================");

        System.out.println("\n1. RENDIMIENTO DE VENDEDORES (PREMIO AL QUE VENDIÓ MÁS CARO):");
        System.out.println("+------------+---------------+--------------+---------------+------------+---------------+");
        System.out.printf("| %-10s | %-13s | %-12s | %-13s | %-10s | %-13s |%n",
                "Vendedor", "Precio Inic.", "Estado Final", "Precio Venta", "Iteración", "Premio");
        System.out.println("+------------+---------------+--------------+---------------+------------+---------------+");

        List<String> sortedSellers = new ArrayList<>(sellerAgents.keySet());
        sortedSellers.sort(String::compareTo);

        for (String sName : sortedSellers) {
            int initPrice = initialSellerPrices.getOrDefault(sName, 0);
            TradeRecord sale = tradeHistory.stream()
                    .filter(t -> t.seller.equals(sName))
                    .findFirst()
                    .orElse(null);

            String status = (sale != null) ? "VENDIDO" : "SIN VENDER";
            String salePriceStr = (sale != null) ? ("$" + sale.price) : ("$" + sellerAgents.get(sName).getCurrentPrice() + " (Stock)");
            String iterStr = (sale != null) ? ("Iter " + sale.iteration + "/" + MAX_ITERATIONS) : "-";
            boolean isWinner = (sale != null && bestSellerTrade != null && sale.price == bestSellerTrade.price);
            String award = isWinner ? "GANADOR" : "-";

            System.out.printf("| %-10s | %-13s | %-12s | %-13s | %-10s | %-13s |%n",
                    sName, "$" + initPrice, status, salePriceStr, iterStr, award);
        }
        System.out.println("+------------+---------------+--------------+---------------+------------+---------------+");

        System.out.println("\n2. RENDIMIENTO DE COMPRADORES (PREMIO AL QUE COMPRÓ MÁS BARATO):");
        System.out.println("+------------+--------------+---------------+---------------+------------+---------------+");
        System.out.printf("| %-10s | %-12s | %-13s | %-13s | %-10s | %-13s |%n",
                "Comprador", "Estado Final", "Vendedor", "Precio Compra", "Iteración", "Premio");
        System.out.println("+------------+--------------+---------------+---------------+------------+---------------+");

        List<String> sortedBuyers = new ArrayList<>(buyerAgents.keySet());
        sortedBuyers.sort(String::compareTo);

        for (String bName : sortedBuyers) {
            TradeRecord purchase = tradeHistory.stream()
                    .filter(t -> t.buyer.equals(bName))
                    .findFirst()
                    .orElse(null);

            String status = (purchase != null) ? "COMPRADO" : "NO COMPRÓ";
            String sellerStr = (purchase != null) ? purchase.seller : "-";
            String buyPriceStr = (purchase != null) ? ("$" + purchase.price) : "-";
            String iterStr = (purchase != null) ? ("Iter " + purchase.iteration + "/" + MAX_ITERATIONS) : "-";
            boolean isWinner = (purchase != null && bestBuyerTrade != null && purchase.price == bestBuyerTrade.price);
            String award = isWinner ? "GANADOR" : "-";

            System.out.printf("| %-10s | %-12s | %-13s | %-13s | %-10s | %-13s |%n",
                    bName, status, sellerStr, buyPriceStr, iterStr, award);
        }
        System.out.println("+------------+--------------+---------------+---------------+------------+---------------+");

        if (bestSellerTrade != null) {
            System.out.println(">>> PREMIO VENDEDOR (Vendió más caro)  : " + bestSellerTrade.seller
                    + " (vendió a $" + bestSellerTrade.price + " a " + bestSellerTrade.buyer + " en la iteración " + bestSellerTrade.iteration + ")");
        } else {
            System.out.println(">>> PREMIO VENDEDOR (Vendió más caro)  : Desierto (ningún vendedor concretó ventas)");
        }

        if (bestBuyerTrade != null) {
            System.out.println(">>> PREMIO COMPRADOR (Compró más barato): " + bestBuyerTrade.buyer
                    + " (compró a $" + bestBuyerTrade.price + " de " + bestBuyerTrade.seller + " en la iteración " + bestBuyerTrade.iteration + ")");
        } else {
            System.out.println(">>> PREMIO COMPRADOR (Compró más barato): Desierto (ningún comprador concretó compras)");
        }
        System.out.println("==========================================================================================\n");
    }
}