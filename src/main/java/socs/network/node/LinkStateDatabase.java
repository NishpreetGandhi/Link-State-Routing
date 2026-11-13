package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.PriorityQueue;

public class LinkStateDatabase {

  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null;

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   * <p/>
   * This method should use Dijkstra's algorithm with link weights (not hop count) to find the shortest path.
   * The weights are stored in the weight field of LinkDescription objects in the LSA entries.
   * <p/>
   * format: source ip address -> ip address -> ... -> destination ip
   *
   * @param destinationIP the simulated IP address of the destination router
   * @return the shortest path as a string, or null if no path exists
   */
    String getShortestPath(String destinationIP) {
    if (!_store.containsKey(rd.simulatedIPAddress)) {
      return null;
    }

    HashMap<String, Integer> distanceMap = new HashMap<>();
    HashMap<String, String> parentMap = new HashMap<>();
    HashSet<String> settledNodes = new HashSet<>();

    // Distance from the local router to itself is zero
    distanceMap.put(rd.simulatedIPAddress, 0);
    PriorityQueue<Map.Entry<String, Integer>> queue =
            new PriorityQueue<>(Map.Entry.comparingByValue());
    queue.add(new AbstractMap.SimpleEntry<>(rd.simulatedIPAddress, 0));

    while (!queue.isEmpty()) {
      Map.Entry<String, Integer> entry = queue.poll();
      String u = entry.getKey();

      if (settledNodes.contains(u)) continue;
      settledNodes.add(u);

      if (u.equals(destinationIP)) break;

      LSA lsa = _store.get(u);
      if (lsa == null) continue;

      int currentDistU = distanceMap.get(u);

      for (LinkDescription neighborLink : lsa.links) {
        String v = neighborLink.linkID;
        if (v.equals(u)) continue;
        if (settledNodes.contains(v)) continue;

        LSA vLsa = _store.get(v);
        boolean reverseExists = false;
        if (vLsa != null) {
          for (LinkDescription back : vLsa.links) {
            if (u.equals(back.linkID)) {
              reverseExists = true;
              break;
            }
          }
        }
        if (!reverseExists) continue;

        int tentativeDist = currentDistU + neighborLink.weight;

        if (!distanceMap.containsKey(v) || tentativeDist < distanceMap.get(v)) {
          distanceMap.put(v, tentativeDist);
          parentMap.put(v, u);
          queue.add(new AbstractMap.SimpleEntry<>(v, tentativeDist));
        }
      }
    }

    if (!distanceMap.containsKey(destinationIP) || !settledNodes.contains(destinationIP)) {
      return null;
    }

    LinkedList<String> fullPathSequence = new LinkedList<>();
    String walkNode = destinationIP;
    while (walkNode != null) {
      fullPathSequence.addFirst(walkNode);
      walkNode = parentMap.get(walkNode);
    }
    return String.join(" -> ", fullPathSequence);
  }

  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    ld.weight = 0; //self-link has weight 0
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").
                append(ld.weight).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

}
