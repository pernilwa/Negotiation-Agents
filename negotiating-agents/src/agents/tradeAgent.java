package agents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import jade.content.schema.facets.DocumentationFacet;
import jade.core.behaviours.SimpleBehaviour;
import tools.Inventory;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
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

	private ArrayList<String> items = new ArrayList<String>(11);
	private ArrayList<String> wantedItems = new ArrayList<String>(3);
	private ArrayList<AID> traderAgents = new ArrayList<AID>();
	private ArrayList<AID> contactList = new ArrayList<AID>();
	private int gold = 100;

  // Gambling for chance to auction
  private int currentGamblingRound = 1;
  private double myRoll;
  private int remainingRolls;
	
protected void setup(){
  //You have to say this in your head like the StarCraft unit
  System.out.println(this.getLocalName()+" agent online.");
  register();

  //Get items and wantedItems
  addBehaviour(new RequestInventoryAndWantedItems());

  //Wait for item checks
  addBehaviour(new CheckForItem());

  //Wait for Trade acceptances
  addBehaviour(new Trading());

  // Start the gambling round if all traders are present
  tryInitializeGamblingRound();

  // Answer requests to gamble for a chance at the auction
  addBehaviour(new GambleForAuctionChance());
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
  requestToGamble.setConversationId("gambling-round-" + currentGamblingRound);

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
      MessageTemplate.MatchConversationId("gambling-round-" + currentGamblingRound)
    );
    ACLMessage msg = myAgent.receive(mt);

    if(msg != null) {
      myRoll = rng.nextDouble();
      System.out.println(myAgent.getLocalName() + " rolled a " + myRoll);

      ACLMessage reply = new ACLMessage(ACLMessage.INFORM);
      reply.setConversationId("gambling-round-" + currentGamblingRound);
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
      MessageTemplate.MatchConversationId("gambling-round-" + currentGamblingRound)
    );
    ACLMessage msg = myAgent.receive(mt);
    if (msg != null) {
      rolls.add(Double.parseDouble(msg.getContent()));
      remainingRolls--;

      if (remainingRolls == 0) {
        myAgent.removeBehaviour(this);
        currentGamblingRound++;

        if (myRoll > Collections.max(rolls)) {
          System.out.println(myAgent.getLocalName() + " is the winner if the gambling round");
          myAgent.addBehaviour(new StartTrading());
        }
      }
    } else {
      block();
    }
  }
}

/**
 * Inner class StartTrading
 * This trader is asked to start trading, it will look at its own wantedItems
 * list and send out requests for that item to the other agents. If nobody
 * has it is removed, if trade is successful or failure it is removed. When no
 * items remain it passes trade priority on to another agent.
 */
private class StartTrading extends Behaviour {
	private MessageTemplate mt;
	private String tradeItem = null;
	private int bestBid = 0;
	private AID bestTrader = null;
	private int repliesCnt = 0;
	private int receivers = 0;
	private int step = 0;
	private int rej = 0;
	
	public void action() {
		switch (step) {
		case -1:
			//This agent is done trading
			block();
		case 0:
			//Agent checks whether they have an wantedItem and if they can afford it. If so
			//move to step 1, otherwise pass trade priority on to next agent.			
			try {
				tradeItem = wantedItems.get(0);
				if(Inventory.getValue(tradeItem) > gold){
					tradeItem = null;
					wantedItems.remove(0);
					step = 0;
					break;
				}
			} catch (IndexOutOfBoundsException e) {
				System.out.println("No more trade items");
				System.out.println("This agent is sending trade priority to another agent.");
				
				ACLMessage send = new ACLMessage(ACLMessage.CFP);
				AID next;
				try {
					next = contactList.get(0);
				} catch (IndexOutOfBoundsException e2) {
					step = -1;
					break;
				}
				
				send.addReceiver(next);
				myAgent.send(send);
				contactList.remove(0);
				step = -1;
				break;
			}
			
			System.out.println(myAgent.getLocalName()+" would like "+ tradeItem);
			step = 1;	
			break;

		case 1:		
			//Send trade request to all other agents
			
			ACLMessage order = new ACLMessage(ACLMessage.REQUEST);
			for (int i = 0; i < contactList.size(); i++) {
				order.addReceiver(contactList.get(i));
				receivers++;
			}
			order.setContent(tradeItem);
			order.setConversationId("trade-this");
			order.setReplyWith("Trade"+System.currentTimeMillis());
			System.out.println("Sending trade item to available agents: "+tradeItem);
			System.out.println("Sending to "+receivers+" available agents");
			myAgent.send(order);
			//Prepare the template to get the reply
			mt = MessageTemplate.and(MessageTemplate.MatchConversationId("trade-this"), MessageTemplate.MatchInReplyTo(order.getReplyWith()));
			step = 2;
			break;
			
		case 2:		
			//Get all request replies, update bestBid
			
			ACLMessage svar = myAgent.receive(mt);
			if(svar != null){
				System.out.println("Trade reply in Case 2 was: "+svar.getContent());
				//Trade received
				if(svar.getPerformative() == ACLMessage.CONFIRM){
					//trade is offered
					int temp = Integer.parseInt(svar.getContent());
					if(bestBid == 0 || temp < bestBid){
						//Update bestBid at present
						bestBid = temp;
						bestTrader = svar.getSender();
					}
				}
				else if (svar.getPerformative() == ACLMessage.REFUSE){
					rej++;
				}					
				repliesCnt++;
				if(rej == repliesCnt){
					System.out.println("None of "+tradeItem+" available.");
					System.out.println("");
					wantedItems.remove(0);
					tradeItem = null;
					bestBid = 0;
					bestTrader = null;
					repliesCnt = 0;
					receivers = 0;
					step = 0;
					rej = 0;
					step = 0;
					break;
				}
				if(repliesCnt >= receivers){
					//We got all replies
					System.out.println("We got all receivers.");
					step = 3;
				}
			}
			else{
				block();
			}
			break;
		case 3:
			System.out.println("Starting Case 3");
			//Trade agreed upon, update both buyer and seller
			
			//Inform seller
			ACLMessage accept = new ACLMessage(ACLMessage.AGREE);
			accept.addReceiver(bestTrader);
			accept.setContent(tradeItem);
			System.out.println("Sending trade accept to seller");
			myAgent.send(accept);
			
			//Buyer update
			wantedItems.remove(tradeItem);
			gold = gold - Inventory.getValue(tradeItem);

			step = 5;
			break;
		}
	}
	public boolean done() {
		if(step == 5){
			System.out.println("");
			tradeItem = null;
			bestBid = 0;
			bestTrader = null;
			repliesCnt = 0;
			receivers = 0;
			step = 0;
			rej = 0;
			step = 0;

      tryInitializeGamblingRound();

			return false;
		}
		return false;
	}
	
}

/**
 * Inner class CheckForItem
 * This class responds to trade requests from TA agent. Check to see if we have
 * the item requested and if so send confirm back.
 */
private class CheckForItem extends CyclicBehaviour {
	public void action() {
		String trItem;
		MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
		ACLMessage msg = myAgent.receive(mt);
		
		if(msg != null){
			trItem = msg.getContent();
			ACLMessage reply = msg.createReply();
			System.out.println(myAgent.getLocalName()+" agent checking for item: "+trItem);

      if (items.contains(trItem)) {;
					System.out.println(myAgent.getLocalName()+" agent has that item for sale");
					int bid = Inventory.getValue(trItem);
					reply.setPerformative(ACLMessage.CONFIRM);
					reply.setContent(Integer.toString(bid));
			} else {
					// We don't have that item
					reply.setPerformative(ACLMessage.REFUSE);
					reply.setContent("not-available");
			}
			myAgent.send(reply);
		} else {
			block();
		}
	}
	
}//End of inner class CheckForItem

/**
 * Inner class Trader
 * Handles acceptance of trades
 */
private class Trading extends CyclicBehaviour {

	public void action() {
		
		MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.AGREE);
		ACLMessage msg = myAgent.receive(mt);
		if(msg != null){
			//Update based upon sold item		
			items.remove(msg.getContent());
			gold += Inventory.getValue(msg.getContent());
		}
		else{
			block();
		}

	}
}//End of inner class Trading

/**
 * Inner class GetAgentList
 * Retrieves other available trader agents from DF agent
 * and adds them to traderAgents list.
 */
@Deprecated
private class GetAgentList extends OneShotBehaviour {
	public void action() {
	if(traderAgents.isEmpty()){
		System.out.println(myAgent.getLocalName()+" says: Getting registered trading agents.");
		
		DFAgentDescription template = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType(SERVICE_TYPE);
		template.addServices(sd);
		
		try{
			DFAgentDescription[] result = DFService.search(myAgent, template);	
			System.out.println(myAgent.getLocalName()+" says: Found "+(result.length-1)+" agents, acquiring.");
			for (int i = 0; i < result.length; ++i) {	
				if (result[i].getName().equals(myAgent)) {
					continue;
				}

				traderAgents.add(result[i].getName());
			}
			
			System.out.println("Agent list: ");
			for (int i = 0; i < traderAgents.size(); i++) {
				System.out.println("Agent #" + i + "-" +traderAgents.get(i));
			}
		}
		catch(FIPAException fe){
			System.out.println("Something went wrong Jimmy, Butters is dead (Trader agent - GetAgentList");
			fe.printStackTrace();
		}
		contactList = traderAgents;
		System.out.println("--\n");
	}
} 	
}// End of inner class getAgentList

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
    System.out.println(myAgent.getLocalName()+" says: I have these items;");
		for (int i = 0; i < 5; i++) {
			items.add(tItems.remove(0));
			System.out.println("  " + items.get(i));
		}

		System.out.println(myAgent.getLocalName()+" says: I want these items;");
		while (tItems.size() > 0) {
			wantedItems.add(tItems.remove(0));
			System.out.println("  " + wantedItems.get(wantedItems.size() - 1));
		}
		System.out.println("");
	}
}//End
}
