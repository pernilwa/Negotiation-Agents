package agents;

import java.util.*;
import java.util.stream.Collectors;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import tools.Inventory;

/**
 * An agent responsible for dividing the initial loot so that each item has
 * a seller and a buyer
 */
public class LootDistributorAgent extends Agent {
  public static String SERVICE_TYPE = "loot-distributor";

  private static final Random rng = new Random(System.currentTimeMillis());

  private List<AID> traders = new ArrayList<>();
  private boolean listeningToLootRequests = true;
  private DivideLoot divideLoot = null;

  protected void setup() {
    System.out.println(this.getLocalName()+" agent online.");
    register();
    addBehaviour(new ListenForLootRequests());
  }

  private class ListenForLootRequests extends CyclicBehaviour {

    @Override
    public void action() {
      MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
      ACLMessage msg = myAgent.receive(mt);
      if (msg != null) {
        ACLMessage reply = msg.createReply();
        if (listeningToLootRequests) {
          traders.add(msg.getSender());
          if (divideLoot == null) {
            divideLoot = new DivideLoot(myAgent, 1000L);
            addBehaviour(divideLoot);
          }
        } else {
          reply.setPerformative(ACLMessage.REFUSE);
        }

        myAgent.send(reply);
      } else {
        block();
      }
    }
  }

  private class DivideLoot extends WakerBehaviour {
    public DivideLoot(Agent a, long timeout) {
      super(a, timeout);
    }

    protected void onWake() {
      listeningToLootRequests = false;

      List<String> allItems = Inventory.getAllItems();
      Collections.shuffle(allItems, rng);

      System.out.printf("%s says: distributing loot to %s\n",
        myAgent.getLocalName(),
        traders.stream().map(AID::getLocalName).collect(Collectors.joining(", "))
      );
      System.out.printf("%s says: distributing items: %s\n",
        myAgent.getLocalName(),
        allItems.stream().collect(Collectors.joining(", "))
      );

      Map<AID, List<String>> itemsTraderHas = new HashMap<>();
      // Distribute items evenly among all the traders
      for (int i = 0; !allItems.isEmpty(); i = (i + 1) % traders.size()) {
        AID trader = traders.get(i);
        itemsTraderHas.putIfAbsent(trader, new ArrayList<>());
        itemsTraderHas.get(trader).add(allItems.remove(0));
      }

      allItems = Inventory.getAllItems();
      Collections.shuffle(allItems, rng);
      Map<AID, List<String>> itemsTraderWants = new HashMap<>();
      for (int i = 0; !allItems.isEmpty(); i = (i + 1) % traders.size()) {
        AID trader = traders.get(i);
        List<String> hasList = itemsTraderHas.get(trader);

        // We only want to need items we don't already have.
        for (int j = 0; j < allItems.size(); j++) {
          String item = allItems.get(j);
          if (hasList.contains(item)) continue;
          itemsTraderWants.putIfAbsent(trader, new ArrayList<>());
          itemsTraderWants.get(trader).add(item);
          allItems.remove(j);
        }
      }

      for (AID trader : traders) {
        ACLMessage have = new ACLMessage(ACLMessage.INFORM);
        have.addReceiver(trader);
        have.setContent("have " +
          itemsTraderHas.get(trader).stream().collect(Collectors.joining(" "))
        );

        ACLMessage want = new ACLMessage(ACLMessage.INFORM);
        want.addReceiver(trader);
        want.setContent("want " +
            itemsTraderWants.get(trader).stream().collect(Collectors.joining(" "))
        );

        System.out.printf("%s says: sending message '%s' to %s\n",
          myAgent.getLocalName(),
          have.getContent(),
          trader.getLocalName()
        );
        System.out.printf("%s says: sending message '%s' to %s\n",
          myAgent.getLocalName(),
          want.getContent(),
          trader.getLocalName()
        );
        myAgent.send(have);
        myAgent.send(want);
      }
    }
  }

  /*
   * DF register method
   */
  protected void register() {
    // Register the trader-agent service in the yellow pages
    DFAgentDescription dfd = new DFAgentDescription();
    dfd.setName(getAID());
    ServiceDescription sd = new ServiceDescription();
    sd.setType(SERVICE_TYPE);
    sd.setName("LootDistributorAgent" + System.currentTimeMillis()); //Gives each agent unique name
    dfd.addServices(sd);
    try {
      DFService.register(this, dfd);
    }
    catch (FIPAException fe) {
      fe.printStackTrace();
    }
  }//End
}
