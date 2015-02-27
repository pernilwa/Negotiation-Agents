package tools;

import java.util.*;

public class Inventory {
  private static Random rng = new Random(System.currentTimeMillis());

	//Using arraylist as it allows for shuffle()
	static ArrayList<String> items = new ArrayList<String>(Arrays.asList("axe","sword","pants","food","sleeping bag",
																		 "amulet","tunic","helmet","mace","armguards",
																		 "boots","potion","tent","belt"));
	static ArrayList<Integer> values = new ArrayList<Integer>(Arrays.asList(35, 30, 15, 10, 10, 55, 30, 25, 30, 15, 15, 20, 25, 5));
	
	public static ArrayList<String> getRandomItemSet(){
		Collections.shuffle(items, rng);
		
		//Items to give to agent, and the items it wants
		ArrayList<String> myItems = new ArrayList<String>(8);
		
		for (int i = 0; i < 8; i++) {
			myItems.add(items.get(i));
		}
		
		return myItems;
	}
	
	public static int getValue(String str){
		int amt = -1;
		
		for (int i = 0; i < items.size(); i++) {
			if(str.compareTo(items.get(i)) == 0){
				//System.out.println("Returning item value of: "+values.get(i));	
				return values.get(i);
			}
			else{
				//System.out.println("Item did not match up");
			}
		}
		
		return amt;
	}
	
}
