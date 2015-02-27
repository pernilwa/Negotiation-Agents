package agents;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.deploy.util.StringUtils;
import jade.core.behaviours.SimpleBehaviour;
import tools.Inventory;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

@SuppressWarnings("serial")
public class TradeAgent extends Agent {

  private final static String SERVICE_TYPE = "trader-agent";
  private final static int AGENTS_BEFORE_START = 3;

  private final static Random rng = new Random(System.currentTimeMillis());

	private ArrayList<String> sellingItems = new ArrayList<String>(11);
	private ArrayList<String> wantedItems = new ArrayList<String>(3);
	private int gold = 100;

  // Gambling for chance to auction
  private int currentRound = 1;
  private double myRoll;
  private int remainingRolls;

  private int currentBiddingRound = 1;
  private String activeBiddingRoundID;
  private int activeBid;
	
protected void setup(){
  //You have to say this in your head like the StarCraft unit
  System.out.println(this.getLocalName()+" agent online.");
  register();

  //Get sellingItems and wantedItems
  addBehaviour(new RequestInventoryAndWantedItems());

  //Wait for item checks
  addBehaviour(new BidOnItems());

  //Wait for Trade acceptances
  addBehaviour(new ProcessBidResponses());

  // Answer requests to gamble for a chance at the auction
  addBehaviour(new GambleForAuctionChance());

  // Start the gambling round if all traders are present
  tryInitializeGamblingRound();
}

/**
 * Checks if AGENTS_BEFORE_START agents are present, and initializes the
 * trading round if they are.
 */
protected void tryInitializeGamblingRound() {
  AID[] agents = getAgentList();
  if (agents.length < AGENTS_BEFORE_START) {
    return;
  }

  ACLMessage requestToGamble = new ACLMessage(ACLMessage.REQUEST);
  requestToGamble.setConversationId("gambling-round-" + currentRound);

  // Important note, we actually send this message to ourselves too
  // because it makes the code simpler.
  for (AID agent : agents) {
    requestToGamble.addReceiver(agent);
  }
}

/**
 * Rolls a dice (double 0-1) and sends the results to all other traders
 */
private class GambleForAuctionChance extends CyclicBehaviour {
  public void action() {
    MessageTemplate mt = MessageTemplate.and(
      MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
      MessageTemplate.MatchConversationId("gambling-round-" + currentRound)
    );
    ACLMessage msg = myAgent.receive(mt);

    if(msg != null) {
      // If we don't have any items to sell, we don't want to win the gamble.
      myRoll = sellingItems.isEmpty() ? 0.0 : rng.nextDouble();
      System.out.println(myAgent.getLocalName() + " rolled a " + myRoll);

      ACLMessage reply = new ACLMessage(ACLMessage.INFORM);
      reply.setConversationId("gambling-round-" + currentRound);
      reply.setContent(Double.toString(myRoll));
      AID[] agentList = getAgentList();
      for (AID agent : agentList) {
        if (agent.equals(myAgent.getAID())) continue;
        reply.addReceiver(agent);
      }

      myAgent.send(reply);
      remainingRolls = agentList.length - 1;
      myAgent.addBehaviour(new ReceiveGambleRolls());

    } else {
      block();
    }
  }
}

/**
 * Receive dice rolls from other traders, and once all rolls are in,
 * checks if this agent is the winner, and starts trading if it is.
 */
private class ReceiveGambleRolls extends CyclicBehaviour {
  private final List<Double> rolls = new ArrayList<>();
  public void action() {
    MessageTemplate mt = MessageTemplate.and(
      MessageTemplate.MatchPerformative(ACLMessage.INFORM),
      MessageTemplate.MatchConversationId("gambling-round-" + currentRound)
    );
    ACLMessage msg = myAgent.receive(mt);
    if (msg != null) {
      rolls.add(Double.parseDouble(msg.getContent()));
      remainingRolls--;

      if (remainingRolls == 0) {
        myAgent.removeBehaviour(this);
        currentRound++;

        if (myRoll > Collections.max(rolls)) {
          System.out.println(myAgent.getLocalName() + " is the winner if the gambling round");
          myAgent.addBehaviour(new SellItem());
        }
      }
    } else {
      block();
    }
  }
}

  /**
   * Standard FPSB auction. Announce what item you're selling, receive bids,
   * accept the best offer.
   */
private class SellItem extends SimpleBehaviour {
  private final int
    ANNOUNCE = 0,
    RECEIVE_BIDS = 1,
    SEND_ITEM = 2,
    RECEIVE_PAYMENT = 3,
    INIT_NEW_GAMBLE = 4,
    DONE = 5;
  private int state = ANNOUNCE;

  private final String conversationID = "bidding-round-" + currentBiddingRound;

  private List<AID> traders;
  private String sellItem;

  private int bidCount = 0;
  private AID bestBidder = null;
  private int bestOffer = 0;

  @Override
  public void action() {
    MessageTemplate mt;

    switch (state) {
      case ANNOUNCE:
        // Pick a random item to sell
        ACLMessage announceMsg;
        sellItem = sellingItems.get(rng.nextInt(sellingItems.size()));
        announceMsg = new ACLMessage(ACLMessage.CFP);
        announceMsg.setConversationId(conversationID);
        announceMsg.setContent(sellItem);

        traders = new ArrayList(Arrays.asList(getAgentList()));
        traders.remove(myAgent.getAID());
        traders.forEach(announceMsg::addReceiver);

        myAgent.send(announceMsg);
        state = RECEIVE_BIDS;
        break;
      case RECEIVE_BIDS:
        mt = MessageTemplate.and(
           MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
          MessageTemplate.MatchConversationId(conversationID)
        );
        ACLMessage offerMsg = myAgent.receive(mt);
        if (offerMsg != null) {
          bidCount++;
          int bid = Integer.parseInt(offerMsg.getContent());
          if (bid > bestOffer) {
            bestBidder = offerMsg.getSender();
            bestOffer = bid;
          }

          if (bidCount == traders.size()) {
            state = SEND_ITEM;
          }
        } else {
          block();
        }
        break;
      case SEND_ITEM:
        // Reject losing bids
        ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
        reject.setConversationId(conversationID);
        traders.forEach(t -> {
          if (!t.equals(bestBidder)) reject.addReceiver(t);
        });
        myAgent.send(reject);

        if (bestBidder != null) {
          ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
          accept.setConversationId(conversationID);
          accept.setContent(sellItem);
          sellingItems.remove(sellItem);
          myAgent.send(accept);
          System.out.printf(
            "%s says: accepting bid of %d from %s, and sending item\n",
            myAgent.getLocalName(),
            bestOffer,
            bestBidder.getLocalName()
          );

          state = RECEIVE_PAYMENT;
        } else {
          System.out.println(myAgent.getLocalName() + "says: no bid was accepted");
          state = INIT_NEW_GAMBLE;
        }
        break;
      case RECEIVE_PAYMENT:
        mt = MessageTemplate.and(
          MessageTemplate.MatchPerformative(ACLMessage.INFORM),
          MessageTemplate.MatchConversationId(conversationID)
        );
        ACLMessage paymentMsg = myAgent.receive(mt);
        int payment = Integer.parseInt(paymentMsg.getContent());
        System.out.println(myAgent.getLocalName() + " says: received payment of " + payment);
        gold += payment;
        state = INIT_NEW_GAMBLE;
        break;
      case INIT_NEW_GAMBLE:
        currentBiddingRound++;
        tryInitializeGamblingRound();
        state = DONE;
        break;
    }
  }

  @Override
  public boolean done() {
    return state == DONE;
  }
}

/**
 * Bids on items it wants by replying to auction CFP messages. Bids either
 * what it thinks the item is worth, or all it's gold (whichever is lowest).
 */
private class BidOnItems extends CyclicBehaviour {
  public void action() {
    MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
    ACLMessage msg = myAgent.receive(mt);
    if (msg != null) {
      String item = msg.getContent();
      ACLMessage propose = msg.createReply();
      propose.setPerformative(ACLMessage.PROPOSE);
      activeBiddingRoundID = msg.getConversationId();

      if (wantedItems.contains(item)) {
        activeBid = Math.min(gold, Inventory.getValue(item));
        propose.setContent(Integer.toString(activeBid));

        System.out.printf("%s says: bidding %d on %s\n",
          myAgent.getLocalName(), activeBid, item
        );
      } else {
        propose.setContent("-1");
      }

      // TODO: this is a bit of a hack to increment bidding round id globally.
      Matcher matcher = Pattern.compile("\\d+").matcher(activeBiddingRoundID);
      currentBiddingRound = Integer.parseInt(matcher.group());

      myAgent.send(propose);
    } else {
      block();
    }
  }
}

/**
 * Looks at the answer it gets on it's bids and sends the payment if
 * it's accepted.
 */
private class ProcessBidResponses extends CyclicBehaviour {
  public void action() {
    MessageTemplate mt = MessageTemplate.and(
      MessageTemplate.MatchConversationId(activeBiddingRoundID),
      MessageTemplate.or(
        MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL),
        MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL)
      )
    );
    ACLMessage msg = myAgent.receive(mt);
    if (msg != null) {
      if (msg.getPerformative() == ACLMessage.ACCEPT_PROPOSAL) {
        ACLMessage reply = msg.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setContent(Integer.toString(activeBid));
        gold -= activeBid;
        wantedItems.remove(msg.getContent());
        System.out.printf("%s says: received %s from, sending back %d\n",
          myAgent.getLocalName(), msg.getContent(), activeBid
        );
      }

      activeBid = 0;
      activeBiddingRoundID = "";
    } else {
      block();
    }
  }
}

protected AID[] getAgentList() {
  AID[] agents;

  DFAgentDescription template = new DFAgentDescription();
  ServiceDescription sd = new ServiceDescription();
  sd.setType(SERVICE_TYPE);
  template.addServices(sd);

  try {
    DFAgentDescription[] result = DFService.search(this, template);
    agents = new AID[result.length];
    for (int i = 0; i < result.length; i++) {
      agents[i] = result[i].getName();
    }
    return agents;
  }
  catch (FIPAException fe) {
    fe.printStackTrace();
    return null;
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
	sd.setName("TraderAgent" + System.currentTimeMillis()); //Gives each agent unique name
	dfd.addServices(sd); 
	try { 
		DFService.register(this, dfd); 
	} 
	catch (FIPAException fe) { 
		fe.printStackTrace(); 
	}
}//End

/**
 * Inventory management class
 */
private class RequestInventoryAndWantedItems extends OneShotBehaviour {
	public void action() {
		List<String> tItems = tools.Inventory.getRandomItemSet();
		for (int i = 0; i < 5; i++) {
			sellingItems.add(tItems.remove(0));
		}

		while (tItems.size() > 0) {
			wantedItems.add(tItems.remove(0));
		}

    System.out.printf("%s says: I'm selling these items [%s]\n",
      myAgent.getLocalName(), StringUtils.join(sellingItems, ", ")
    );
    System.out.printf("%s says: I'm buying these items [%s]\n",
      myAgent.getLocalName(), StringUtils.join(wantedItems, ", ")
    );
	}
}//End
}
