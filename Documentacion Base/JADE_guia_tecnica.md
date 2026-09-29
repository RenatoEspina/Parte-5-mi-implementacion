# JADE 4.0 — Guía técnica de uso

> Síntesis del *JADE Programmer's Guide* y del *JADE Administrator's Guide* (JADE 4.0, abril 2010), con notas complementarias marcadas como **[Nota]**.

---

## 1. Qué es JADE

JADE (*Java Agent DEvelopment Framework*) es un framework Java para construir sistemas multiagente conformes a los estándares **FIPA**. Aporta dos cosas:

1. Una **plataforma de agentes** FIPA-compliant (runtime distribuido, AMS, DF, transporte de mensajes).
2. Una **API Java** para programar agentes (`Agent`, `Behaviour`, `ACLMessage`, protocolos de interacción, ontologías).

Requisito único de ejecución: **JRE 5+**. Licencia: LGPL 2.1.

### Paquetes principales

| Paquete | Contenido |
|---|---|
| `jade.core` | Kernel. Clase `Agent`, `AID`, `Runtime`, `Profile`, `Location`. |
| `jade.core.behaviours` | Jerarquía de `Behaviour` (tareas del agente). |
| `jade.lang.acl` | `ACLMessage`, `MessageTemplate`, `StringACLCodec`. |
| `jade.content` | Soporte para lenguajes de contenido y ontologías (`jade.content.lang.sl` → codec SL). |
| `jade.domain` | AMS, DF, `DFService`, `AMSService`, ontologías de gestión (`FIPAAgentManagement`, `JADEAgentManagement`, `mobility`, `introspection`). |
| `jade.proto` | Protocolos FIPA listos (Request, Query, Contract-Net, Subscribe, Propose…). |
| `jade.proto.states` | Estados genéricos reutilizables (`HandlerSelector`, `MsgReceiver`). |
| `jade.mtp` | Interfaz `MTP` e implementaciones (HTTP, IIOP). |
| `jade.wrapper` | Wrappers para usar JADE como librería desde una app Java externa. |
| `jade.gui` | Componentes Swing para AIDs, descripciones, mensajes. |
| `jade.tools` | RMA, Dummy, Sniffer, Introspector, DF GUI, LogManager, SocketProxy. |

---

## 2. Arquitectura de la plataforma

### 2.1 Modelo FIPA

```
┌──────────────────── Agent Platform ────────────────────┐
│   Agent      AMS (white pages)      DF (yellow pages)   │
│ ─────────────── Message Transport System ────────────── │
└─────────────────────────────────────────────────────────┘
```

- **AMS** (*Agent Management System*): único por plataforma. Control de ciclo de vida y páginas blancas (directorio de AIDs y estado). Todo agente se registra en el AMS para obtener un AID válido — JADE lo hace automáticamente antes de `setup()` y lo deshace tras `takeDown()`.
- **DF** (*Directory Facilitator*): páginas amarillas (servicios ofrecidos). Pueden existir varios DF federados.
- **MTS / ACC**: el *Messaging Service*, siempre activo; enruta mensajes intra e inter-plataforma.

### 2.2 Contenedores

- Cada JVM = un **contenedor** que hospeda N agentes (cada agente = un thread Java).
- **Main Container**: el primero en arrancar; hospeda AMS y DF, y mantiene la *Container Table*, la *Global Agent Descriptor Table* y la *MTP table*.
- **Contenedores periféricos**: se registran contra el main. El conjunto se ve desde fuera como una sola plataforma.
- Topología por defecto: estrella con el main al centro (punto único de fallo → ver §9).

### 2.3 Identificación: AID

Un `jade.core.AID` tiene:

- **name (GUID)**: `<nickname>@<platform-name>`, p. ej. `peter@kim:1099/JADE`. El nombre de plataforma por defecto es `<host-main>:<puerto-main>/JADE`, sobrescribible con `-platform-id` / `-name`. En los `ACLMessage` deben usarse siempre GUIDs completos.
- **addresses**: URIs de transporte; por defecto, las de todos los MTP activos en la plataforma.
- **resolvers**: servicios de white pages donde está registrado.

Construcción desde código:

```java
new AID("receiver", AID.ISLOCALNAME);           // completa con el nombre de plataforma
new AID("receiver@host:1099/JADE", AID.ISGUID); // GUID explícito
```

---

## 3. La clase `Agent`

Un agente JADE es una subclase de `jade.core.Agent`. Hereda la interacción con la plataforma y un **scheduler interno** de behaviours.

### 3.1 Ciclo de vida (FIPA)

| Estado | Significado |
|---|---|
| `INITIATED` | Objeto construido; sin AID ni registro en AMS; no puede comunicarse. |
| `ACTIVE` | Registrado, con nombre y dirección. **Único estado en que ejecuta behaviours.** |
| `SUSPENDED` | Thread suspendido; ningún behaviour se ejecuta. |
| `WAITING` | Thread dormido en un monitor hasta que se cumpla una condición (típicamente, llegada de mensaje). |
| `TRANSIT` | Migrando; la plataforma bufferiza sus mensajes. |
| `DELETED` | Thread terminado y desregistrado del AMS. |

Transiciones vía métodos `doXXX()`: `doWait()`, `doSuspend()`, `doActivate()`, `doDelete()`, `doMove()`, `doClone()`, etc.

> `doWait()` bloquea **todo el agente** (todos sus behaviours). Para bloquear solo un behaviour se usa `Behaviour.block()`.

### 3.2 Arranque y parada

Secuencia de arranque: constructor → asignación de AID → registro en AMS → estado `ACTIVE` → `setup()`.

`setup()` es el punto de entrada de la lógica de aplicación:

- (opcional) modificar datos en el AMS;
- (opcional) registrar servicios en uno o más DF;
- **(necesario)** añadir al menos un behaviour con `addBehaviour()`.

Parada: cualquier behaviour llama `doDelete()` → se ejecuta `takeDown()` (el agente aún está registrado y puede enviar mensajes; lugar para desregistrarse del DF, cerrar recursos) → desregistro y destrucción del thread.

```java
public class MiAgente extends Agent {
    @Override protected void setup() {
        Object[] args = getArguments();   // argumentos de arranque (transitorios: no migran ni se clonan)
        addBehaviour(new MiBehaviour(this));
    }
    @Override protected void takeDown() {
        // limpieza, p.ej. DFService.deregister(this);
    }
}
```

### 3.3 Formas de lanzar un agente

1. Línea de comandos (`jade.Boot`, §8.2) — la más común.
2. GUI del RMA (*Start New Agent*).
3. Desde una aplicación Java externa vía *in-process interface* (§7).

### 3.4 Cola de mensajes privada

Todo mensaje recibido va a una cola privada por agente (tamaño ilimitado por defecto; `setQueueSize()` para acotarla).

| Método | Semántica |
|---|---|
| `receive()` | No bloqueante; devuelve `null` si no hay mensaje. |
| `receive(MessageTemplate)` | Ídem, con *pattern matching*. |
| `blockingReceive()` | Bloquea **todo el agente** hasta que llegue un mensaje. |
| `blockingReceive(long ms)` | Ídem con timeout; `null` si expira. |
| `blockingReceive(MessageTemplate[, long])` | Bloqueante con filtro. |

Patrón idiomático: `receive()` + `block()` dentro de un behaviour (§4.3). `blockingReceive()` solo en behaviours que no conviven con otros o en threaded behaviours.

---

## 4. Behaviours (modelo de concurrencia)

### 4.1 Modelo de ejecución

- Un agente = **un único thread**. Sus tareas son objetos `Behaviour`.
- Scheduler **round-robin no preemptivo**: invoca `action()` del behaviour en cabeza de la cola; al retornar, llama `done()`. Si `done()==true`, el behaviour se elimina; si no, se re-encola.
- Los behaviours son "threads cooperativos" **sin stack propio**: cada `action()` empieza desde el principio. El estado debe vivir en campos de instancia (→ patrón máquina de estados).
- **Nunca** loops infinitos ni operaciones largas dentro de `action()`: bloquean al resto de behaviours del mismo agente.
- `addBehaviour()` / `removeBehaviour()` pueden llamarse en cualquier momento, no solo en `setup()`.

### 4.2 `block()` y reactivación

`block()` marca el behaviour como bloqueado; el efecto ocurre **al retornar** de `action()`. Un behaviour bloqueado se reactiva cuando:

1. Llega **cualquier** mensaje ACL al agente (hay que volver a llamar `block()` si el mensaje no le interesaba);
2. Expira el timeout pasado a `block(long ms)`;
3. Se invoca `restart()` explícitamente.

### 4.3 Patrón canónico de recepción

```java
addBehaviour(new CyclicBehaviour(this) {
    private final MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
    public void action() {
        ACLMessage msg = myAgent.receive(mt);
        if (msg != null) {
            // procesar
        } else {
            block();
        }
    }
});
```

### 4.4 Jerarquía de clases

```
Behaviour  (action(), done(), onStart(), onEnd(), block(), restart(), reset(), DataStore)
├── SimpleBehaviour
│   ├── OneShotBehaviour     done() == true siempre (una ejecución)
│   ├── CyclicBehaviour      done() == false siempre (infinito)
│   ├── WakerBehaviour       one-shot tras un timeout  → onWake()
│   └── TickerBehaviour      periódico                 → onTick()
└── CompositeBehaviour       (planificación de hijos; no define política)
    ├── SequentialBehaviour  hijos en secuencia; termina al terminar todos
    ├── ParallelBehaviour    hijos concurrentes; termina con WHEN_ALL / WHEN_ANY / N hijos
    └── FSMBehaviour         hijos = estados de una FSM
```

Detalles relevantes:

- `onStart()` / `onEnd()`: hooks antes y después de la ejecución. `onEnd()` devuelve un `int` (valor de terminación; lo usa `FSMBehaviour` para elegir transición). Se llama **tras** retirar el behaviour de la cola, por lo que para repetirlo hay que re-añadirlo:
  ```java
  public int onEnd() { reset(); myAgent.addBehaviour(this); return 0; }
  ```
- `DataStore`: repositorio compartido entre behaviours (lo usan los protocolos de `jade.proto`). Se vacía con `reset()`.
- `FSMBehaviour`: `registerFirstState()`, `registerState()`, `registerLastState()`, `registerTransition(from, to, exitValue)`, `registerDefaultTransition()`.
- Para políticas de scheduling propias (p. ej. prioridades), extender `CompositeBehaviour` directamente.

### 4.5 Threaded behaviours

Para operaciones bloqueantes (I/O, llamadas remotas) sin congelar al agente:

```java
private final ThreadedBehaviourFactory tbf = new ThreadedBehaviourFactory();
protected void setup() {
    Behaviour b = new OneShotBehaviour(this) {
        public void action() { /* operación bloqueante larga */ }
    };
    addBehaviour(tbf.wrap(b));   // se ejecuta en un thread dedicado
}
```

Consideraciones:

- `removeBehaviour()` no tiene efecto sobre un threaded behaviour: usar `tbf.getThread(b).interrupt()`.
- Al morir, migrar o suspenderse el agente, hay que matar explícitamente sus threaded behaviours.
- Acceso concurrente a recursos del agente → sincronización manual.
- Se pueden mezclar con behaviours normales dentro de composites; un `ParallelBehaviour` envuelto asigna un grupo de behaviours a un thread.

---

## 5. Mensajería ACL

### 5.1 `ACLMessage`

Implementa los parámetros del FIPA ACL Message Structure. Accesores `get/set<Attr>()` para atributos simples y `add/getAll<Attr>()` (Iterator) para conjuntos (p. ej. `receiver`). Los getters devuelven `null` si el atributo no se ha fijado.

| Parámetro FIPA | Uso |
|---|---|
| performative | Acto comunicativo; se fija en el constructor (`ACLMessage.INFORM`, `REQUEST`, …). |
| sender / receiver | AIDs. `send()` rellena el sender. |
| content | `setContent(String)`, `setContentObject(Serializable)`, `setByteSequenceContent(byte[])`. |
| language / ontology | Cómo interpretar el contenido. |
| protocol | Protocolo de interacción (`FIPANames.InteractionProtocols.*`). |
| conversation-id, reply-with, in-reply-to | Correlación de conversaciones. |
| reply-by | Deadline (`Date`); lo usan los protocolos como timeout. |

**[Nota]** Performatives FIPA disponibles como constantes: `ACCEPT_PROPOSAL`, `AGREE`, `CANCEL`, `CFP`, `CONFIRM`, `DISCONFIRM`, `FAILURE`, `INFORM`, `INFORM_IF`, `INFORM_REF`, `NOT_UNDERSTOOD`, `PROPAGATE`, `PROPOSE`, `PROXY`, `QUERY_IF`, `QUERY_REF`, `REFUSE`, `REJECT_PROPOSAL`, `REQUEST`, `REQUEST_WHEN`, `REQUEST_WHENEVER`, `SUBSCRIBE`.

Envío:

```java
ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
msg.addReceiver(new AID("receiver", AID.ISLOCALNAME));
msg.setLanguage("PlainText");
msg.setContent("hola");
send(msg);   // asíncrono, transparente a la ubicación (local/remoto)
```

Respuestas bien formadas (copia `conversation-id`, `in-reply-to`, receptor = emisor original):

```java
ACLMessage reply = msg.createReply();
reply.setPerformative(ACLMessage.AGREE);
send(reply);
```

Otros:

- `setContentObject()` / `getContentObject()`: serialización Java + Base64. **No es FIPA**: ambos extremos deben conocerlo a priori.
- `setByteSequenceContent()`: bytes crudos; puede corromperse si el mensaje se codifica en String.
- `toString()`: solo para debug. `reset()`: limpia todos los campos.
- Intra-plataforma los mensajes viajan como objetos Java (sin marshalling); al cruzar plataformas se convierten a sintaxis/transporte FIPA automáticamente.
- `StringACLCodec`: parseo/encode manual en formato String (casos especiales).

### 5.2 `MessageTemplate`

Filtro sobre la cola privada: permite atender varias conversaciones en paralelo sin consumir mensajes ajenos.

```java
MessageTemplate mt = MessageTemplate.and(
    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
    MessageTemplate.not(MessageTemplate.MatchOntology("ReceiveTest")));
```

Factories: `MatchPerformative`, `MatchSender`, `MatchLanguage`, `MatchOntology`, `MatchProtocol`, `MatchConversationId`, `MatchInReplyTo`, `MatchReplyWith`, `MatchContent`, `MatchTopic`, etc. Combinadores: `and`, `or`, `not`.

Matching custom: implementar `MessageTemplate.MatchExpression` (`boolean match(ACLMessage)`) y envolverla con `new MessageTemplate(expr)`.

### 5.3 Comunicación por tópicos (publish/subscribe)

Requiere `jade.core.messaging.TopicManagementService` activo **en todos los contenedores**. Los tópicos se modelan como AIDs; el emisor no conoce a los suscriptores; sin suscriptores, el envío no tiene efecto (sin `FAILURE`).

```java
TopicManagementHelper th =
    (TopicManagementHelper) getHelper(TopicManagementHelper.SERVICE_NAME);
AID topic = th.createTopic("JADE");

// Suscribirse
th.register(topic);
MessageTemplate tpl = MessageTemplate.MatchTopic(topic);

// Publicar
ACLMessage m = new ACLMessage(ACLMessage.INFORM);
m.addReceiver(topic);
m.setContent("...");
send(m);
```

---

## 6. Servicios de plataforma, protocolos y ontologías

### 6.1 DF y AMS: API simplificada

AMS/DF hablan **FIPA-SL0** + ontología `fipa-agent-management` + protocolo `fipa-request`. JADE lo encapsula:

- `getDefaultDF()`, `getAMS()`: AIDs de los agentes de sistema.
- `jade.domain.DFService`: métodos estáticos `register`, `deregister`, `modify`, `search`. **Bloquean al agente** hasta acabar la conversación o lanzar `FIPAException`.
- `jade.domain.AMSService`: API dual para el AMS (normalmente innecesaria: el registro es automático).

**[Nota]** Registro y búsqueda típicos:

```java
// Registro (en setup)
DFAgentDescription dfd = new DFAgentDescription();
dfd.setName(getAID());
ServiceDescription sd = new ServiceDescription();
sd.setType("traduccion");
sd.setName("traductor-es-en");
dfd.addServices(sd);
DFService.register(this, dfd);

// Búsqueda
DFAgentDescription tpl = new DFAgentDescription();
ServiceDescription s = new ServiceDescription();
s.setType("traduccion");
tpl.addServices(s);
DFAgentDescription[] res = DFService.search(this, tpl);
```

Versión no bloqueante (suscripción al DF):

```java
ACLMessage subs = DFService.createSubscriptionMessage(this, getDefaultDF(), template, null);
addBehaviour(new SubscriptionInitiator(this, subs) {
    protected void handleInform(ACLMessage inform) {
        try {
            DFAgentDescription[] dfds = DFService.decodeNotification(inform.getContent());
        } catch (FIPAException fe) { fe.printStackTrace(); }
    }
});
```

Convención de las clases de ontología (`jade.domain.FIPAAgentManagement`): atributo simple → `getX()/setX()`; atributo multivaluado → `addX()`, `clearAllX()`, `getAllX()` (Iterator).

### 6.2 Protocolos de interacción (`jade.proto`)

Cada protocolo tiene rol **Initiator** y **Responder**, implementados como behaviours con callbacks.

- **Initiators**: terminan al alcanzar un estado final; son 1:N (salvo `SimpleAchieveREInitiator`, 1:1); reutilizables vía métodos `reset(...)`.
- **Responders**: cíclicos; se reprograman al terminar. N instancias añadidas = N conversaciones concurrentes máximo.
- Timeout: tomado del `reply-by` del mensaje inicial (en 1:N, el mínimo). Aplica a la **primera** respuesta.
- Extensión: override de `handleXxx()` / `prepareXxx()`, o registro de behaviours como handlers de estado (`registerHandleXxx()`, `registerPrepareXxx()`), comunicándose por el `DataStore` con las claves `*_KEY` (variables, no constantes).

| Protocolo | Clases | Flujo |
|---|---|---|
| FIPA-Request, Query, Request-When, Recruiting, Brokering | `AchieveREInitiator/Responder`, `SimpleAchieveREInitiator/Responder` | acto → (`not-understood` \| `refuse` \| `agree`?) → (`failure` \| `inform`) |
| FIPA-Contract-Net | `ContractNetInitiator/Responder` | `cfp` → (`propose` \| `refuse` \| `not-understood`) → (`accept-proposal` \| `reject-proposal`) → (`inform` \| `failure`). `CANCEL` no implementado. |
| FIPA-Propose | `ProposeInitiator/Responder` | `propose` → (`accept-proposal` \| `reject-proposal` \| `not-understood`) |
| FIPA-Subscribe | `SubscriptionInitiator/Responder` | `subscribe` → (`refuse` \| `agree`?) → `inform`* hasta `cancel` o `failure` |

**AchieveRE** — callbacks clave del initiator: `prepareRequests` (devuelve `Vector<ACLMessage>`), `handleAgree`, `handleRefuse`, `handleNotUnderstood`, `handleInform`, `handleFailure`, `handleOutOfSequence`, `handleAllResponses` (1ª capa), `handleAllResultNotifications` (2ª capa). Responder: `prepareResponse` (1ª respuesta, p. ej. `agree`; puede devolver `null` para omitirla) y `prepareResultNotification` (resultado final). El `agree` es opcional.

```java
// Initiator
ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
req.setProtocol(FIPANames.InteractionProtocols.FIPA_REQUEST);
req.addReceiver(new AID("receiver", AID.ISLOCALNAME));
addBehaviour(new AchieveREInitiator(this, req) {
    protected void handleInform(ACLMessage inform) { /* efecto racional logrado */ }
});

// Responder
MessageTemplate mt = AchieveREResponder.createMessageTemplate(
        FIPANames.InteractionProtocols.FIPA_REQUEST);
addBehaviour(new AchieveREResponder(this, mt) {
    protected ACLMessage prepareResultNotification(ACLMessage request, ACLMessage response) {
        ACLMessage r = request.createReply();
        r.setPerformative(ACLMessage.INFORM);
        r.setContent("done");
        return r;
    }
});
```

**Contract-Net**: el initiator tiene `handlePropose`, `handleAllResponses(Vector responses, Vector acceptances)` para elegir ganador, y `handleAllResultNotifications`. Respuestas tardías quedan en la cola del agente.

**Subscribe**: el responder delega el registro en un `SubscriptionResponder.SubscriptionManager` (`register()`/`deregister()`); las notificaciones se envían con `Subscription.notify(msg)` (no con `send()`), que gestiona los campos de protocolo. El initiator cancela con `cancel(AID, boolean)`; contenido vía `fillCancelContent()`.

**Estados genéricos** (`jade.proto.states`): `HandlerSelector` (selecciona un behaviour handler según una clave del DataStore; override de `getSelectionKey`), `MsgReceiver` (espera un mensaje que cumpla un template con timeout).

### 6.3 Lenguajes de contenido y ontologías

`jade.content` desacopla ontología y lenguaje: el código que manipula conceptos no depende del codec.

```java
getContentManager().registerLanguage(new SLCodec(0));                        // FIPA-SL0
getContentManager().registerOntology(FIPAManagementOntology.getInstance());
getContentManager().fillContent(msg, action);        // objeto Java → content
ContentElement ce = getContentManager().extractContent(msg);
```

`jade.content.onto.basic` provee conceptos comunes (`Action` con actor + acción, `Result`, `TrueProposition`…); `BasicOntology` es combinable con ontologías propias.

---

## 7. Integración con aplicaciones externas

### 7.1 In-process interface (`jade.wrapper`)

```java
import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.*;

Runtime rt = Runtime.instance();
Profile p = new ProfileImpl();
// p.setParameter(Profile.MAIN_HOST, "localhost"); p.setParameter(Profile.GUI, "true");
ContainerController cc = rt.createAgentContainer(p);   // periférico; createMainContainer(p) para main
AgentController ac = cc.createNewAgent("inProcess",
        "jade.tools.DummyAgent.DummyAgent", new Object[]{ new Object() });
ac.start();   // crear no arranca
```

- `ContainerController`: crear agentes, instalar/desinstalar MTPs, matar el contenedor (la app sigue viva).
- `AgentController`: control de ciclo de vida, **sin** referencia directa al objeto `Agent` (preserva autonomía).
- Permite plataformas totalmente in-process (varios contenedores en una JVM) o mixtas.

**[Nota]** Para comunicar la app con el agente sin romper la autonomía existen `AgentController.putO2AObject()` + `Agent.setEnabledO2ACommunication()` / `getO2AObject()`.

### 7.2 Agentes con GUI (Swing)

Dos threads distintos: el del agente y el *Event Dispatcher Thread* (EDT). Swing no es thread-safe.

- **GUI → agente**: un `send()` simple puede hacerse desde el listener (es asíncrono). Operaciones complejas (protocolos) → encapsular en un behaviour y añadirlo con `addBehaviour()`. Exponer a la GUI solo una **interfaz** con los "métodos externos" del agente, no la referencia al `Agent`.
- **Agente → GUI**: toda modificación de modelos Swing se envuelve en un `Runnable` y se envía con `SwingUtilities.invokeLater()`.

---

## 8. Administración de la plataforma

### 8.1 Distribución e instalación

Paquetes: `JADE-bin` (jars), `JADE-src`, `JADE-examples`, `JADE-doc`, `JADE-all`. Añadir `lib/jade.jar` (y en 4.0, `lib/commons-codec/commons-codec-1.3.jar`) al classpath.

Build desde fuentes (Ant ≥1.6, `build.xml` en la raíz): `ant jade` (→ `classes/`), `ant lib` (→ `lib/jade.jar`), `ant doc`, `ant examples`, `ant clean`.

### 8.2 `jade.Boot`

```
java jade.Boot Option* AgentSpecifier (";" AgentSpecifier)*
AgentSpecifier = AgentName ":" ClassName ("(" Arg ("," Arg)* ")")?
```

```bash
java -cp lib/jade.jar jade.Boot -gui                                  # main container + RMA
java -cp lib/jade.jar jade.Boot -container -host <main-host> a1:pkg.MiAgente(x,42)
```

Los argumentos llegan como `Object[]` de `String` (`getArguments()`), sin comillas.

| Opción | Efecto |
|---|---|
| `-container` | Contenedor periférico. |
| `-host` / `-port` | Main al que conectarse (def. `localhost:1099`). |
| `-local-host` / `-local-port` | Host/puerto de este contenedor. |
| `-gui` | Lanza el RMA. |
| `-platform-id` (`-name`) | Nombre de plataforma (solo main). |
| `-container-name` | Nombre del contenedor. |
| `-services s1;s2` | Servicios de kernel a activar (FQN). |
| `-mtps` / `-nomtp` | MTPs a activar / suprimir el HTTP MTP por defecto del main. |
| `-aclcodecs` | Codecs ACL adicionales (se eligen vía `aclRepresentation` del Envelope). |
| `-nomobility` | Deshabilita migración y clonado en ese contenedor. |
| `-backupmain`, `-smhost`, `-smport`, `-smaddrs` | Replicación del main (§9). |
| `-detect-main` | Descubrimiento del main por multicast UDP. |
| `-conf <file>` | Opciones desde un properties file. |
| `-<key> <value>` | Propiedad arbitraria. |
| `-version`, `-help` | Info. |

### 8.3 Servicios de kernel

| Servicio | Clase | Default |
|---|---|---|
| Messaging | `jade.core.messaging.MessagingService` | Siempre |
| Agent-Management | `jade.core.management.AgentManagementService` | Siempre |
| Agent-Mobility | `jade.core.mobility.AgentMobilityService` | Activo |
| Notification (Sniffer/Introspector) | `jade.core.event.NotificationService` | Activo |
| Persistent-Delivery | `jade.core.messaging.PersistentDeliveryService` | Inactivo |
| Main-Replication | `jade.core.replication.MainReplicationService` | Inactivo |
| Address-Notification | `jade.core.replication.AddressNotificationService` | Inactivo |
| UDP Node Monitoring | `jade.core.nodeMonitoring.UDPNodeMonitoringService` | Inactivo |
| Fault-Recovery | `jade.core.faultRecovery.FaultRecoveryService` | Inactivo |
| Topic-Management | `jade.core.messaging.TopicManagementService` | Inactivo |
| Back-End Management (split containers) | `jade.imtp.leap.nio.BEManagementService` | Inactivo |

> Especificar `-services` **reemplaza** los defaults: si se necesita movilidad/notificación junto a otros, hay que listarlos explícitamente.

### 8.4 Jar agents

Desde 3.4, el código de un agente puede cargarse desde un jar fuera del classpath, en namespace de ClassLoader separado:

```bash
java jade.Boot -jade_core_management_AgentManagementService_agentspath /ruta/jars ...
```

Convención: un jar por agente, nombrado como la clase principal con `_` en vez de `.` (`coffeeApp_agents_WaiterAgent.jar`). Sin el parámetro, se usa el directorio de trabajo.

### 8.5 Configuración del DF

| Parámetro | Default | Descripción |
|---|---|---|
| `jade_domain_df_autocleanup` | `false` | Elimina registros de agentes muertos. |
| `jade_domain_df_maxleasetime` | ∞ | Lease máximo (ms). |
| `jade_domain_df_maxresult` | 100 | Máx. resultados por búsqueda sin constraints. |
| `jade_domain_df_kb-factory` | `jade.domain.DFKBFactory` | Factory del knowledge base. |
| `jade_domain_df_db-default` | — | Persistencia en HSQLDB embebido (jar de HSQLDB en classpath). |
| `jade_domain_df_db-url` / `-driver` / `-username` / `-password` | — | Persistencia JDBC arbitraria. |
| `jade_domain_df_db-cleantables` | `false` | Limpia tablas previas. |

Un DF adicional puede leer su configuración de un properties: `df1:jade.domain.df(df1conf.properties)`.

### 8.6 Monitorización de nodos

- Por defecto: conexiones TCP permanentes main↔periféricos (adecuado para pocas máquinas y redes estables).
- UDP (escalable, tolera indisponibilidad temporal): activar `UDPNodeMonitoringService` en main y periféricos; parámetros (en el main, se propagan) con prefijo `jade_core_nodeMonitoring_UDPNodeMonitoringService_`: `port` (28000), `pingdelay` (1000 ms), `pingdelaylimit` (3000 ms), `unreachablelimit` (10000 ms).

### 8.7 Detección automática del main

`-detect-main true` en el periférico → multicast a `239.255.10.99:1199` (configurable con `jade_core_MainDetectionManager_mcastaddr/mcastport/mcastttl/mcasttimeout/mcastretries`). `-host/-port` tienen precedencia. Con varias plataformas en la red, filtrar con `-name`.

### 8.8 Mensajería inter-plataforma (MTP y codecs)

- Routing single-hop; requiere visibilidad directa entre contenedores.
- Default: **HTTP MTP** en el main; ninguno en periféricos. Direcciones activas se imprimen y se escriben en `MTPs-<Container>.txt`.
- `-mtps jade.mtp.http.MessageTransportProtocol(http://host:7778/acc)`; varios separados por `;`. También IIOP (`jade.mtp.iiop.MessageTransportProtocol`, ORB del JDK) y ORBacus (add-on).
- MTPs instalables/desinstalables en caliente desde el RMA.
- MTP propio: implementar `jade.mtp.MTP` (extiende `InChannel`/`OutChannel`) y `TransportAddress`.
- Codec ACL por defecto: String FIPA. Add-ons: XML y Bit-Efficient (este último con licencia distinta de LGPL).
- Un agente puede publicar un subconjunto de direcciones manteniendo su propia copia de AID y usándola como sender.

---

## 9. Tolerancia a fallos y persistencia

### 9.1 Replicación del Main Container

Varios nodos main (un *master* con AMS/DF activos + *backups* con réplicas latentes) forman un **anillo**; cada uno monitoriza a su vecino. Topología resultante: "anillo de estrellas".

```bash
# host1: master
java jade.Boot -name Hydra -services jade.core.replication.MainReplicationService;jade.core.replication.AddressNotificationService
# host2: backup
java jade.Boot -backupmain -local-port 1234 -host host1 -port 1099 -services jade.core.replication.MainReplicationService;jade.core.replication.AddressNotificationService
# host3/host4: periféricos
java jade.Boot -container -host host1 -port 1099 -services jade.core.replication.AddressNotificationService
java jade.Boot -container -host host2 -port 1234
```

Si cae un backup, el anillo se reconstruye y los contenedores huérfanos se reconectan a otro nodo al necesitarlo. Si cae el master, el siguiente nodo activa sus réplicas de AMS y DF. Los periféricos conocen los nodos vía **Address-Notification** (dinámico; no obligatorio desde 3.6) o `-smaddrs` (lista fija).

### 9.2 Persistent Delivery

Sustituye el `FAILURE` inmediato ante receptor inexistente por buffering + reintentos.

```java
public class NewsFilter implements PersistentDeliveryFilter {
    public long delayBeforeExpiration(ACLMessage msg) {
        if (!"News-Ontology".equals(msg.getOntology())) return NOW;   // no reclamado
        Date d = msg.getReplyByDate();
        if (d == null) return NEVER;                                 // indefinido
        long delay = d.getTime() - System.currentTimeMillis();
        return delay > 0 ? delay : 0;
    }
}
```

Se consultan los filtros de todos los contenedores hasta que uno reclama el mensaje (≠ `NOW`); el retorno es el TTL en ms.

```bash
java jade.Boot -services jade.core.messaging.PersistentDeliveryService \
  -persistent-delivery-filter app.news.NewsFilter \
  -persistent-delivery-storagemethod file \
  -persistent-delivery-basedir /usr/local/news \
  -persistent-delivery-sendfailureperiod 60000
```

Almacenamiento `file`: subdirectorio por hash del AID receptor; un fichero por hash de ⟨mensaje, receptor⟩ con nº de copias, due date (o `FOREVER`), AID en SL y mensaje en S-expression. Se recargan al reiniciar.

---

## 10. Movilidad

Solo **intra-plataforma** (entre contenedores de la misma plataforma). Requiere `AgentMobilityService` y que el agente sea serializable.

| Método | Descripción |
|---|---|
| `doMove(Location)` | Migra el agente. |
| `doClone(Location, String nuevoNombre)` | Copia remota con otro nombre. |
| `beforeMove()` | En origen, **tras** el éxito del move: liberar recursos locales (ficheros, GUI). Cambios de estado aquí no viajan. |
| `afterMove()` | En destino, identidad lista, scheduler aún no reiniciado: reabrir recursos. |
| `beforeClone()` / `afterClone()` | Análogos para clonado. |

`jade.core.Location` es una interfaz: las ubicaciones se obtienen del AMS (`query-platform-locations`, `where-is-agent` en `JADEManagementOntology`). Las acciones `move-agent` / `clone-agent` (ontología `jade-mobility-ontology`, `MobilityOntology.getInstance()`) se piden al AMS vía `fipa-request` + `FIPA-SL0`, construyendo `MoveAction` / `CloneAction` con un `MobileAgentDescription` y serializando con `getContentManager().fillContent()`.

**[Nota]** Para moverse a sí mismo basta `doMove(new ContainerID("Container-1", null))`.

---

## 11. Herramientas de depuración

Todas son agentes (mismo ciclo de vida y comunicación que uno de aplicación).

| Herramienta | Clase / lanzamiento | Uso |
|---|---|---|
| **RMA** | `-gui` o `x:jade.tools.rma.rma` | Consola: crear/matar/suspender/reanudar/migrar/clonar agentes, enviar mensajes, gestionar MTPs y plataformas remotas. Uno por contenedor. **No suspender el AMS** (deadlock de la plataforma). |
| **DummyAgent** | `d:jade.tools.DummyAgent.DummyAgent` | Componer/enviar ACL a mano, historial con timestamps, guardar/cargar. |
| **Sniffer** | `s:jade.tools.sniffer.Sniffer` | Intercepta mensajes y los muestra como diagrama de secuencia. `sniffer.properties`: `preload=da0;da1 inform propose`, `clip=prefijo.` También por argumentos: `Sniffer(df* ; RMA inform)`. |
| **Introspector** | desde RMA / línea de comandos | Estado, colas de mensajes y de behaviours de un agente; ejecución paso a paso. |
| **DF GUI** | RMA → Tools | Ver/registrar/modificar/buscar en el DF; federación de DFs. |
| **LogManagerAgent** | RMA → Tools | Cambiar niveles de `java.util.logging` en runtime. |
| **SocketProxyAgent** | — | Gateway ACL ↔ socket TCP ASCII. |

Sniffer e Introspector dependen del `NotificationService`.

---

## 12. Implementación del ejemplo de JADE: `examples.receivers`

Es el ejemplo común a ambas guías: el *Programmer's Guide* (§3.4.11) da el código de `AgentSender` y `AgentReceiver`, y el *Administrator's Guide* (§2.3.4) muestra cómo lanzarlos distribuidos. Ilustra envío de ACL, `MessageTemplate`, `receive()` vs `blockingReceive()`, `block()` y un behaviour con estado.

### 12.1 Qué hace

- **AgentSender**: un `SimpleBehaviour` pide por consola el nombre del receptor y envía 4 `INFORM` con distintos atributos, espaciados con `doWait()`, y luego se elimina.

  | # | content | language | ontology | espera tras enviar |
  |---|---|---|---|---|
  | 1 | `FirstInform` | — | — | 5 s |
  | 2 | `SecondInform` | `PlainText` | — | 5 s |
  | 3 | `\nThirdInform` | `PlainText` | — | 1 s |
  | 4 | `FourthInform` | `PlainText` | `ReceiveTest` | → `doDelete()` |

  (Reutiliza el mismo objeto `ACLMessage`, por eso los atributos se acumulan.)

- **AgentReceiver**: un `SimpleBehaviour` de 3 estados:
  1. `op1`: `receive()` no bloqueante con template `INFORM ∧ PlainText ∧ ¬ReceiveTest`; si no hay match → `block()` y se queda en el estado 1.
  2. `op2`: `blockingReceive(5000)` sin filtro (bloquea todo el agente hasta 5 s).
  3. `op3`: `blockingReceive(template)` con `INFORM ∧ PlainText ∧ ReceiveTest`; luego `finished = true`.

### 12.2 Estructura del proyecto

```
jade-demo/
├── lib/
│   ├── jade.jar
│   └── commons-codec-1.3.jar      # incluido en la distribución 4.0 (lib/commons-codec/)
├── src/examples/receivers/
│   ├── AgentSender.java
│   └── AgentReceiver.java
└── classes/                       # salida de javac
```

### 12.3 `AgentSender.java`

```java
package examples.receivers;

import java.io.*;
import jade.core.*;
import jade.core.behaviours.*;
import jade.lang.acl.*;

public class AgentSender extends Agent {

    protected void setup() {
        addBehaviour(new SimpleBehaviour(this) {
            private boolean finished = false;

            public void action() {
                try {
                    System.out.println("\nEnter responder agent name: ");
                    BufferedReader buff = new BufferedReader(new InputStreamReader(System.in));
                    String responder = buff.readLine();

                    ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                    // [Nota] el original usa new AID(responder); con ISLOCALNAME basta escribir "receiver"
                    msg.addReceiver(new AID(responder, AID.ISLOCALNAME));
                    msg.setContent("FirstInform");
                    send(msg);
                    System.out.println("\nFirst INFORM sent");
                    doWait(5000);

                    msg.setLanguage("PlainText");
                    msg.setContent("SecondInform");
                    send(msg);
                    System.out.println("\nSecond INFORM sent");
                    doWait(5000);

                    msg.setContent("\nThirdInform");
                    send(msg);
                    System.out.println("\nThird INFORM sent");
                    doWait(1000);

                    msg.setOntology("ReceiveTest");
                    msg.setContent("FourthInform");
                    send(msg);
                    System.out.println("\nFourth INFORM sent");

                    finished = true;
                    myAgent.doDelete();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            }

            public boolean done() {
                return finished;
            }
        });
    }
}
```

### 12.4 `AgentReceiver.java`

```java
package examples.receivers;

import jade.core.*;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class AgentReceiver extends Agent {

    class my3StepBehaviour extends SimpleBehaviour {
        final int FIRST = 1, SECOND = 2, THIRD = 3;
        private int state = FIRST;
        private boolean finished = false;

        public my3StepBehaviour(Agent a) { super(a); }

        public void action() {
            switch (state) {
                case FIRST:  state = op1() ? SECOND : FIRST; break;
                case SECOND: op2(); state = THIRD; break;
                case THIRD:  op3(); state = FIRST; finished = true; break;
            }
        }

        public boolean done() { return finished; }

        private boolean op1() {
            System.out.println("\nAgent " + getLocalName() + " in state 1.1 is waiting for a message");
            MessageTemplate m1 = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
            MessageTemplate m2 = MessageTemplate.MatchLanguage("PlainText");
            MessageTemplate m3 = MessageTemplate.MatchOntology("ReceiveTest");
            MessageTemplate mt = MessageTemplate.and(MessageTemplate.and(m1, m2),
                                                     MessageTemplate.not(m3));
            ACLMessage msg = receive(mt);               // no bloqueante
            if (msg != null) {
                System.out.println("\nAgent " + getLocalName()
                        + " received the following message in state 1.1: " + msg);
                return true;
            }
            System.out.println("\nNo message received in state 1.1");
            block();                                    // efectivo al retornar de action()
            return false;
        }

        private void op2() {
            System.out.println("\nAgent " + getLocalName() + " in state 1.2 is waiting for a message");
            ACLMessage msg = blockingReceive(5000);     // bloquea TODO el agente
            System.out.println(msg != null
                    ? "\nAgent " + getLocalName() + " received the following message in state 1.2: " + msg
                    : "\nNo message received in state 1.2");
        }

        private void op3() {
            System.out.println("\nAgent: " + getLocalName() + " in state 1.3 is waiting for a message");
            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.and(MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                                        MessageTemplate.MatchLanguage("PlainText")),
                    MessageTemplate.MatchOntology("ReceiveTest"));
            ACLMessage msg = blockingReceive(mt);
            System.out.println(msg != null
                    ? "\nAgent " + getLocalName() + " received the following message in state 1.3: " + msg
                    : "\nNo message received in state 1.3");
        }
    }

    protected void setup() {
        addBehaviour(new my3StepBehaviour(this));
    }
}
```

### 12.5 Compilación

Linux/macOS (separador `:`; en Windows usar `;`):

```bash
cd jade-demo
CP="lib/jade.jar:lib/commons-codec-1.3.jar"
javac -cp "$CP" -d classes src/examples/receivers/*.java
```

Alternativa con la distribución de ejemplos oficial: descomprimir `JADE-examples` sobre el árbol de fuentes y ejecutar `ant examples`.

### 12.6 Ejecución

**Opción A — todo en un host (3 terminales):**

```bash
# T1: main container + RMA
java -cp "$CP:classes" jade.Boot -gui

# T2: contenedor con el receptor
java -cp "$CP:classes" jade.Boot -container receiver:examples.receivers.AgentReceiver

# T3: contenedor con el emisor (queda esperando input por stdin)
java -cp "$CP:classes" jade.Boot -container sender:examples.receivers.AgentSender
# → escribir:  receiver
```

**Opción B — un solo proceso** (el `;` se entrecomilla para que la shell no lo interprete):

```bash
java -cp "$CP:classes" jade.Boot -gui "receiver:examples.receivers.AgentReceiver;sender:examples.receivers.AgentSender"
```

**Opción C — distribuido** (como en el *Administrator's Guide*):

```bash
# máquina A (main), p.ej. kim.cselt.it
java jade.Boot -gui
# máquina B
java jade.Boot -host kim.cselt.it -container sender1:examples.receivers.AgentSender
# máquina C
java jade.Boot -host kim.cselt.it -container receiver2:examples.receivers.AgentReceiver sender2:examples.receivers.AgentSender
```

Si se mantiene el original `new AID(responder)` en vez de `ISLOCALNAME`, al prompt hay que ingresar el GUID completo (p. ej. `receiver@<host>:1099/JADE`, visible en el RMA).

### 12.7 Traza esperada y por qué

1. Receiver arranca → `op1`: cola vacía → `block()`.
2. Llega **msg1** (sin language) → el behaviour se reactiva, pero no hace match con el template de `op1` → `block()` de nuevo. **msg1 queda en la cola.**
3. Llega **msg2** (`PlainText`, sin ontology) → match en `op1` → estado 2.
4. `op2`: `blockingReceive(5000)` sin filtro toma el **primer mensaje de la cola, msg1** (FIFO), de inmediato.
5. `op3`: espera `PlainText ∧ ReceiveTest`. Llega **msg3** (no hace match, queda en cola) y luego **msg4** → match → `finished = true`, el behaviour se retira.
6. Sender termina con `doDelete()`. Receiver sigue vivo, sin behaviours, con msg3 sin consumir.

Conclusiones que demuestra el ejemplo: el template no consume mensajes que no casan; `block()` se despierta con **cualquier** mensaje; `blockingReceive()`/`doWait()` congelan el agente entero.

### 12.8 Verificación con herramientas

- **Sniffer**: RMA → Tools → Sniffer, seleccionar `sender` y `receiver` antes de escribir el nombre en T3; se ve el diagrama de secuencia de los 4 `INFORM`.
- **Introspector**: sobre `receiver`, muestra la cola de mensajes entrantes (msg3 pendiente al final) y el behaviour en ejecución; permite step-by-step.
- **DummyAgent**: enviar a mano `INFORM` con `language=PlainText` y `ontology=ReceiveTest` para probar el estado 3 sin el sender.

### 12.9 Variante idiomática **[Nota]**

El ejemplo usa `doWait()` y `blockingReceive()` con fines didácticos. En código real se prefiere no bloquear el thread del agente:

```java
// Sender: un TickerBehaviour en vez de doWait()
addBehaviour(new TickerBehaviour(this, 5000) {
    int i = 0;
    protected void onTick() {
        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.addReceiver(new AID("receiver", AID.ISLOCALNAME));
        m.setLanguage("PlainText");
        m.setContent("Inform #" + (++i));
        myAgent.send(m);
        if (i == 4) { stop(); myAgent.doDelete(); }
    }
});

// Receiver: CyclicBehaviour + receive(template) + block()
addBehaviour(new CyclicBehaviour(this) {
    MessageTemplate mt = MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchLanguage("PlainText"));
    public void action() {
        ACLMessage m = myAgent.receive(mt);
        if (m != null) System.out.println(getLocalName() + " <- " + m.getContent());
        else block();
    }
});
```

---

## Glosario rápido

| Sigla | Significado |
|---|---|
| ACL | Agent Communication Language |
| AID | Agent Identifier |
| AMS | Agent Management System (white pages, ciclo de vida) |
| DF | Directory Facilitator (yellow pages) |
| FIPA | Foundation for Intelligent Physical Agents |
| GUID | Globally Unique Identifier (`nombre@plataforma`) |
| HAP | Home Agent Platform |
| MTP | Message Transport Protocol |
| RMA | Remote Monitoring Agent |
| SL / SL0 | FIPA Semantic Language (perfil 0) |
