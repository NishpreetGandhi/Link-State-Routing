package socs.network.node;

import java.util.concurrent.atomic.AtomicLong;
import socs.network.message.LSA;
import socs.network.message.LinkDescription;
import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Router {

    protected LinkStateDatabase lsd;

    RouterDescription rd = new RouterDescription();

    // Maximum number of attached neighbors.
    private final Link[] ports = new Link[4];
    // Protect shared port and LSDB access.
    private final Object portsLock = new Object();

    // Set once start has run on this router; connect requires it.
    private volatile boolean started = false;

    // Pending attach requests are answered by the receiving router's terminal.
    private final ConcurrentLinkedQueue<PendingAttach> pendingAttachRequests =
            new ConcurrentLinkedQueue<>();

    private final AtomicLong helloSent = new AtomicLong();
    private final AtomicLong helloReceived = new AtomicLong();
    private final AtomicLong lsaUpdateSent = new AtomicLong();
    private final AtomicLong lsaUpdateReceived = new AtomicLong();
    private final AtomicLong appMessageSent = new AtomicLong();
    private final AtomicLong appMessageReceived = new AtomicLong();
    private final AtomicLong attachRequestSent = new AtomicLong();
    private final AtomicLong attachRequestReceived = new AtomicLong();
    private final AtomicLong attachResponseSent = new AtomicLong();
    private final AtomicLong attachResponseReceived = new AtomicLong();
    private final AtomicLong disconnectSent = new AtomicLong();
    private final AtomicLong disconnectReceived = new AtomicLong();

    public Router(Configuration config) {
        rd.simulatedIPAddress =
                config.getString("socs.network.router.ip");

        lsd = new LinkStateDatabase(rd);
        initializeNetworkServer(config);
    }

    private static class PendingAttach {
        final SOSPFPacket request;
        final CompletableFuture<Boolean> decision = new CompletableFuture<>();

        PendingAttach(SOSPFPacket request) {
            this.request = request;
        }
    }

    private void addLocalLSALink(String neighborIP, int portNumber, short weight) {
        synchronized (lsd._store) {
            LSA localLSA = lsd._store.get(rd.simulatedIPAddress);

            if (localLSA != null) {
                LinkDescription description = new LinkDescription();
                description.linkID = neighborIP;
                description.portNum = portNumber;
                description.weight = weight;
                description.tosMetrics = 0;

                localLSA.links.removeIf(item -> neighborIP.equals(item.linkID));
                localLSA.links.add(description);
                localLSA.lsaSeqNumber++;
            }
        }
    }

    private int findFreePortLocked() {
        for (int i = 0; i < ports.length; i++) {
            if (ports[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasNeighborLocked(String neighborIP) {
        for (Link link : ports) {
            if (link != null && link.router2 != null
                    && neighborIP.equals(link.router2.simulatedIPAddress)) {
                return true;
            }
        }
        return false;
    }

    private void initializeNetworkServer(Configuration config) {
        rd.processIPAddress = "127.0.0.1";

        int serverPort;

        switch (rd.simulatedIPAddress) {
            case "192.168.1.1":
                serverPort = 5000;
                break;
            case "192.168.1.2":
                serverPort = 5001;
                break;
            case "192.168.1.3":
                serverPort = 5002;
                break;
            case "192.168.1.4":
                serverPort = 5003;
                break;
            case "192.168.1.5":
                serverPort = 5004;
                break;
            case "192.168.1.6":
                serverPort = 5005;
                break;
            case "192.168.1.7":
                serverPort = 5006;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown simulated IP: " + rd.simulatedIPAddress);
        }

        rd.processPortNumber = (short) serverPort;
        rd.status = null;

        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket =
                         new ServerSocket(serverPort)) {

                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();

                    Thread worker =
                            new Thread(new IncomingPacketWorker(socket));
                    worker.start();
                }

            } catch (IOException e) {
                System.err.println(
                        "Router server stopped: " + e.getMessage());
            }
        });

        serverThread.setDaemon(true);
        serverThread.start();
    }

    private Link[] getPortsSnapshot() {
        synchronized (portsLock) {
            return ports.clone();
        }
    }

    private String getShortestPath(String destinationIP) {
        synchronized (lsd._store) {
            return lsd.getShortestPath(destinationIP);
        }
    }

    private RouterDescription findNeighbor(String neighborIP) {
        synchronized (portsLock) {
            for (Link link : ports) {
                if (link != null
                        && link.router2 != null
                        && neighborIP.equals(
                                link.router2.simulatedIPAddress)) {
                    return link.router2;
                }
            }
        }

        return null;
    }

    /**
     * output the shortest path to the given destination ip
     * <p/>
     * format: source ip address  -> ip address -> ... -> destination ip
     *
     * @param destinationIP the ip adderss of the destination simulated router
     */
    private void processDetect(String destinationIP) {
        String path = getShortestPath(destinationIP);

        if (path == null) {
            System.out.println("Path not found");
            return;
        }

        String[] nodes = path.split("\\s*->\\s*");

        if (nodes.length == 0) {
            System.out.println("Path not found");
            return;
        }

        StringBuilder result =
                new StringBuilder("Path found: ");

        result.append(nodes[0]);

        synchronized (lsd._store) {
            for (int i = 0; i < nodes.length - 1; i++) {
                LSA currentLSA = lsd._store.get(nodes[i]);

                if (currentLSA == null) {
                    System.out.println("Path not found");
                    return;
                }

                Integer cost = null;

                for (LinkDescription link : currentLSA.links) {
                    if (nodes[i + 1].equals(link.linkID)) {
                        cost = (int) link.weight;
                        break;
                    }
                }

                if (cost == null) {
                    System.out.println("Path not found");
                    return;
                }

                result.append(" -> (")
                        .append(cost)
                        .append(") ")
                        .append(nodes[i + 1]);
            }
        }

        System.out.println(result);
    }

    private boolean processAttach(
            String processIP,
            short processPort,
            String simulatedIP,
            short weight) {
        return processAttach(processIP, processPort, simulatedIP, weight, true);
    }

    /**
     * attach the link to the remote router, which is identified by the given simulated ip;
     * to establish the connection via socket, you need to indentify the process IP and process Port;
     * additionally, weight is the cost to transmitting data through the link
     * <p/>
     * NOTE: this command should not trigger link database synchronization
     *
     * @param announce whether to print the success message (connect prints its own)
     */
    private boolean processAttach(
            String processIP,
            short processPort,
            String simulatedIP,
            short weight,
            boolean announce) {

        if (simulatedIP == null
                || simulatedIP.equals(rd.simulatedIPAddress)
                || processIP == null
                || processIP.trim().isEmpty()
                || processPort <= 0) {
            System.out.println("The neighbor's address or port is invalid.");
            return false;
        }

        if (weight < 0) {
            System.out.println("Link cost must be zero or greater.");
            return false;
        }

        RouterDescription remote = new RouterDescription();
        remote.processIPAddress = processIP;
        remote.processPortNumber = processPort;
        remote.simulatedIPAddress = simulatedIP;
        remote.status = RouterStatus.INIT;

        int freePort;
        synchronized (portsLock) {
            if (hasNeighborLocked(simulatedIP)) {
                System.out.println("A connection to this router already exists.");
                return false;
            }

            freePort = findFreePortLocked();

            if (freePort == -1) {
                System.out.println("All connection slots are occupied.");
                return false;
            }

            ports[freePort] = new Link(rd, remote);
            ports[freePort].weight = weight;
        }

        boolean accepted = false;

        try (Socket socket = new Socket(processIP, processPort);
             ObjectOutputStream out =
                     new ObjectOutputStream(socket.getOutputStream())) {

            SOSPFPacket request = new SOSPFPacket();
            request.sospfType = 3;
            request.srcProcessIP = rd.processIPAddress;
            request.srcProcessPort = rd.processPortNumber;
            request.srcIP = rd.simulatedIPAddress;
            request.dstIP = simulatedIP;
            request.routerID = rd.simulatedIPAddress;
            request.neighborID = simulatedIP;
            request.linkWeight = weight;

            out.writeObject(request);
            out.flush();
            attachRequestSent.incrementAndGet();
            try (ObjectInputStream in =
                         new ObjectInputStream(socket.getInputStream())) {
                Object received = in.readObject();

                if (received instanceof SOSPFPacket) {
                    SOSPFPacket response = (SOSPFPacket) received;
                    if (response.sospfType == 4) {
                        attachResponseReceived.incrementAndGet();
                        accepted = response.accepted;
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Unable to complete attach request to " + simulatedIP
                    + ": " + e.getMessage());
        }

        if (!accepted) {
            synchronized (portsLock) {
                if (ports[freePort] != null
                        && ports[freePort].router2 != null
                        && simulatedIP.equals(
                                ports[freePort].router2.simulatedIPAddress)) {
                    ports[freePort] = null;
                }
            }

            System.out.println("Connection failed: the remote router did not approve the request.");
            return false;
        }

        addLocalLSALink(simulatedIP, freePort, weight);
        if (announce) {
            System.out.println("Connection established with " + simulatedIP + ".");
        }
        return true;
    }

    /**
     * process request from the remote router.
     * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request.
     * The intuition is that if router2 is an unknown/anomaly router, it is always safe to reject the attached request from router2.
     */
    private void requestHandler() {
        // Incoming HELLO requests are handled by the socket worker.
    }

    private boolean handleAttachRequest(SOSPFPacket packet) {
        synchronized (portsLock) {
            if (hasNeighborLocked(packet.srcIP)) {
                System.out.println(
                        "Router " + packet.srcIP + " is already attached.");
                return false;
            }

            if (findFreePortLocked() == -1) {
                System.out.println(
                        "No connection slots remain for router " + packet.srcIP);
                return false;
            }
        }

        PendingAttach pending = new PendingAttach(packet);
        pendingAttachRequests.add(pending);

        System.out.println("\nreceived HELLO from " + packet.srcIP + ";");
        System.out.println("Incoming connection request. Accept it? (Y/N):");

        try {
            return pending.decision.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean installIncomingNeighbor(SOSPFPacket packet) {
        RouterDescription remote = new RouterDescription();
        remote.processIPAddress = packet.srcProcessIP;
        remote.processPortNumber = packet.srcProcessPort;
        remote.simulatedIPAddress = packet.srcIP;
        remote.status = RouterStatus.INIT;

        int freePort;

        synchronized (portsLock) {
            if (hasNeighborLocked(packet.srcIP)) {
                return false;
            }

            freePort = findFreePortLocked();
            if (freePort == -1) {
                System.out.println("No connection slots remain for router " + packet.srcIP);
                return false;
            }

            ports[freePort] = new Link(rd, remote);
            ports[freePort].weight = packet.linkWeight;
        }

        addLocalLSALink(packet.srcIP, freePort, packet.linkWeight);
        System.out.println("Connection request approved and neighbor added.");
        return true;
    }

    /**
     * disconnect with the router identified by the given port.
     * Notice: this command triggers the synchronization of database.
     * Failure detection: the remote end is told explicitly with a type 5
     * (LINK_DOWN) packet; see the class comment and the README.
     *
     * @param portNumber the port number which the link attaches at
     */
    private void processDisconnect(short portNumber) {
        if (portNumber < 0 || portNumber >= ports.length) {
            System.out.println("That port is invalid or has no active connection.");
            return;
        }

        Link link;
        synchronized (portsLock) {
            link = ports[portNumber];
            if (link == null || link.router2 == null) {
                System.out.println("That port is invalid or has no active connection.");
                return;
            }
        }

        String neighborIP = link.router2.simulatedIPAddress;
        boolean notified = notifyNeighborDisconnect(link);

        System.out.println("Port " + portNumber + " disconnected");
        System.out.println("Broadcasting LSAUPDATE");

        // Removes the Link, removes the LinkDescription from our LSA,
        // bumps our sequence number once and floods the revised LSA.
        removeNeighborLocal(neighborIP);

        if (!notified) {
            System.out.println("Warning: the neighbor could not be notified. "
                    + "Its local link state may remain stale.");
        }
    }

    /** Notify the adjacent router that this link is being disconnected. */
    private boolean notifyNeighborDisconnect(Link link) {
        try (Socket socket = new Socket(
                    link.router2.processIPAddress,
                    link.router2.processPortNumber);
             ObjectOutputStream out =
                    new ObjectOutputStream(socket.getOutputStream())) {

            SOSPFPacket packet = new SOSPFPacket();
            packet.sospfType = 5;
            packet.srcIP = rd.simulatedIPAddress;
            packet.dstIP = link.router2.simulatedIPAddress;
            out.writeObject(packet);
            out.flush();
            disconnectSent.incrementAndGet();
            return true;
        } catch (IOException e) {
            System.out.println("Unable to notify "
                    + link.router2.simulatedIPAddress + " about the disconnect.");
            return false;
        }
    }

    /**
     * Remove a neighbor locally and then advertise the changed topology.
     * Duplicate calls for the same adjacency are ignored: if the neighbor is
     * no longer in ports, nothing changes and the sequence number is not bumped.
     */
    private void removeNeighborLocal(String neighborIP) {
        boolean removed = false;
        synchronized (portsLock) {
            for (int i = 0; i < ports.length; i++) {
                Link link = ports[i];
                if (link != null && link.router2 != null
                        && neighborIP.equals(link.router2.simulatedIPAddress)) {
                    ports[i] = null;
                    removed = true;
                    break;
                }
            }
        }

        if (!removed) {
            return;
        }

        synchronized (lsd._store) {
            LSA localLSA = lsd._store.get(rd.simulatedIPAddress);
            if (localLSA != null) {
                boolean changed = localLSA.links.removeIf(
                        item -> neighborIP.equals(item.linkID));
                if (changed) {
                    localLSA.lsaSeqNumber++;
                }
            }
        }

        broadcastLSAUpdate();
    }

    /**
     * broadcast Hello to neighbors
     */
    private void processStart() {
        started = true;
        Link[] snapshot = getPortsSnapshot();

        for (Link link : snapshot) {
            if (link == null || link.router2 == null) {
                continue;
            }

            RouterDescription neighbor = link.router2;

            try (Socket socket = new Socket(
                    neighbor.processIPAddress,
                    neighbor.processPortNumber);
                 ObjectOutputStream out =
                         new ObjectOutputStream(
                                 socket.getOutputStream())) {

                SOSPFPacket hello = new SOSPFPacket();

                hello.sospfType = 0;
                hello.srcProcessIP = rd.processIPAddress;
                hello.srcProcessPort = rd.processPortNumber;
                hello.srcIP = rd.simulatedIPAddress;
                hello.dstIP = neighbor.simulatedIPAddress;
                hello.routerID = rd.simulatedIPAddress;
                hello.neighborID = neighbor.simulatedIPAddress;

                out.writeObject(hello);
                out.flush();

                helloSent.incrementAndGet();

            } catch (IOException e) {
                System.out.println(
                        "Unable to send HELLO to "
                                + neighbor.simulatedIPAddress);
            }
        }

        broadcastLSAUpdate();
    }

    /**
     * Handle a received HELLO packet.
     */
    private void handleHello(SOSPFPacket packet) {
        helloReceived.incrementAndGet();

        String sourceIP = packet.srcIP;
        boolean becameTwoWay = false;
        boolean knownNeighbor = false;

        synchronized (portsLock) {
            for (Link link : ports) {
                if (link != null && link.router2 != null
                        && sourceIP.equals(link.router2.simulatedIPAddress)) {
                    knownNeighbor = true;
                    link.router2.processIPAddress = packet.srcProcessIP;
                    link.router2.processPortNumber = packet.srcProcessPort;

                    if (link.router2.status != RouterStatus.TWO_WAY) {
                        link.router2.status = RouterStatus.TWO_WAY;
                        becameTwoWay = true;
                    }
                    break;
                }
            }
        }

        System.out.println("\nreceived HELLO from " + sourceIP + ";");

        if (!knownNeighbor) {
            System.out.println("Ignoring HELLO from unattached router " + sourceIP + ".");
            return;
        }

        if (becameTwoWay) {
            System.out.println("set " + sourceIP + " STATE to TWO_WAY;");
            processStart();
        }
    }

    /**
     * Send our current LSDB to all TWO_WAY neighbors.
     */
    private void broadcastLSAUpdate() {
        broadcastLSAUpdateExcept(null);
    }

    /**
     * Send the LSDB to every TWO_WAY neighbor except excludedIP.
     * A null excludedIP means send to all TWO_WAY neighbors.
     */
    private void broadcastLSAUpdateExcept(String excludedIP) {
        // Deep-copy the LSAs while holding the lock, so other threads cannot
        // modify them while they are being serialized and sent.
        Vector<LSA> lsaSnapshot = new Vector<>();

        synchronized (lsd._store) {
            for (LSA lsa : lsd._store.values()) {
                LSA copy = new LSA();
                copy.linkStateID = lsa.linkStateID;
                copy.lsaSeqNumber = lsa.lsaSeqNumber;

                for (LinkDescription l : lsa.links) {
                    LinkDescription c = new LinkDescription();
                    c.linkID = l.linkID;
                    c.portNum = l.portNum;
                    c.weight = l.weight;
                    c.tosMetrics = l.tosMetrics;
                    copy.links.add(c);
                }

                lsaSnapshot.add(copy);
            }
        }

        Link[] portSnapshot = getPortsSnapshot();

        for (Link link : portSnapshot) {
            if (link == null || link.router2 == null) {
                continue;
            }

            RouterDescription neighbor = link.router2;
            RouterStatus status;
            synchronized (portsLock) {
                status = neighbor.status;
            }

            if (status != RouterStatus.TWO_WAY) {
                continue;
            }

            if (excludedIP != null
                    && excludedIP.equals(neighbor.simulatedIPAddress)) {
                continue;
            }

            try (Socket socket = new Socket(
                    neighbor.processIPAddress,
                    neighbor.processPortNumber);
                 ObjectOutputStream out =
                         new ObjectOutputStream(
                                 socket.getOutputStream())) {

                SOSPFPacket packet = new SOSPFPacket();

                packet.sospfType = 1;
                packet.srcIP = rd.simulatedIPAddress;
                packet.dstIP = neighbor.simulatedIPAddress;
                packet.lsaArray = new Vector<>(lsaSnapshot);

                out.writeObject(packet);
                out.flush();

                lsaUpdateSent.incrementAndGet();

            } catch (Exception e) {
                System.out.println(
                        "Unable to send LSA update to "
                                + neighbor.simulatedIPAddress);
            }
        }
    }

    /**
     * Merge newer LSAs and flood updates only when the database changes.
     * Our own LSA is never overwritten by a received copy.
     */
    private void handleLSAUpdate(SOSPFPacket packet) {
        lsaUpdateReceived.incrementAndGet();

        if (packet.lsaArray == null) {
            return;
        }

        boolean changed = false;

        synchronized (lsd._store) {
            for (LSA incomingLSA : packet.lsaArray) {
                if (incomingLSA == null
                        || incomingLSA.linkStateID == null) {
                    continue;
                }

                if (rd.simulatedIPAddress.equals(incomingLSA.linkStateID)) {
                    continue;
                }

                LSA currentLSA =
                        lsd._store.get(incomingLSA.linkStateID);

                if (currentLSA == null
                        || incomingLSA.lsaSeqNumber
                        > currentLSA.lsaSeqNumber) {

                    lsd._store.put(
                            incomingLSA.linkStateID,
                            incomingLSA);

                    changed = true;
                }
            }
        }

        if (changed) {
            broadcastLSAUpdateExcept(packet.srcIP);
        }
    }

    /**
     * Connect = attach + handshake + LSDB synchronization.
     * Only allowed after start has run on this router.
     */
    private void processConnect(
            String processIP,
            short processPort,
            String simulatedIP,
            short weight) {

        if (!started) {
            System.out.println("Please run start before using connect.");
            return;
        }

        if (processAttach(
                processIP,
                processPort,
                simulatedIP,
                weight,
                false)) {
            System.out.println("Link established with " + simulatedIP);
            System.out.println("Broadcasting LSAUPDATE.");
            // HELLO handshake with the new neighbor, then flood our new LSA.
            processStart();
        }
    }

    /**
     * output the neighbors of the routers
     */
    private void processNeighbors() {
        Link[] snapshot = getPortsSnapshot();

        for (Link link : snapshot) {
            if (link != null
                    && link.router2 != null
                    && link.router2.status == RouterStatus.TWO_WAY) {
                System.out.println(
                        link.router2.simulatedIPAddress);
            }
        }
    }

    /**
     * send an application-level message from this router to the destination router.
     * The message must be forwarded hop-by-hop according to the current shortest path.
     * <p/>
     * When you run send, the window of the router where you run the command should print:
     * "Sending message to <Destination IP>"
     * <p/>
     * For each intermediate router on the shortest path (excluding the source and destination),
     * the router window should print:
     * "Forwarding message from <Source IP> to <Destination IP>"
     * <p/>
     * When the destination router receives the message, the router window should print:
     * "Received message from <Source IP>:"
     * "<Message>"
     *
     * @param destinationIP the simulated IP address of the destination router
     * @param message the message content to send
     */
    private void processSend(
            String destinationIP,
            String message) {

        String path = getShortestPath(destinationIP);

        if (path == null) {
            System.out.println("Path not found");
            return;
        }

        String[] nodes = path.split("\\s*->\\s*");

        if (nodes.length < 2) {
            System.out.println(
                    "Cannot send a message to the local router.");
            return;
        }

        System.out.println("Sending message to " + destinationIP);

        String nextHop = nodes[1];

        sendApplicationPacket(
                rd.simulatedIPAddress,
                destinationIP,
                message,
                nextHop);
    }

    /**
     * Send an application packet to one directly connected next hop.
     * The source IP remains the original source throughout forwarding.
     */
    private void sendApplicationPacket(
            String sourceIP,
            String destinationIP,
            String message,
            String nextHopIP) {

        RouterDescription neighbor = findNeighbor(nextHopIP);

        if (neighbor == null
                || neighbor.status != RouterStatus.TWO_WAY) {
            System.out.println(
                    "Next hop is not connected: " + nextHopIP);
            return;
        }

        try (Socket socket = new Socket(
                neighbor.processIPAddress,
                neighbor.processPortNumber);
             ObjectOutputStream out =
                     new ObjectOutputStream(
                             socket.getOutputStream())) {

            SOSPFPacket packet = new SOSPFPacket();

            packet.sospfType = 2;
            packet.srcIP = sourceIP;
            packet.dstIP = destinationIP;
            packet.message = message;

            out.writeObject(packet);
            out.flush();

            appMessageSent.incrementAndGet();

        } catch (IOException e) {
            System.out.println(
                    "Unable to send message to " + nextHopIP);
        }
    }

    /**
     * handle incoming application message packet.
     * This method should be called when a router receives a SOSPFPacket with sospfType = 2 (Application Message).
     * <p/>
     * If this router is the destination (packet.dstIP equals this router's simulatedIPAddress):
     * - Print "Received message from <Source IP>:"
     * - Print the message content
     * <p/>
     * If this router is an intermediate router:
     * - Print "Forwarding message from <Source IP> to <Destination IP>"
     * - Forward the packet to the next hop on the shortest path to the destination
     * - Do NOT print or inspect the message payload
     *
     * @param packet the received application message packet
     */
    private void handleApplicationMessage(SOSPFPacket packet) {
        appMessageReceived.incrementAndGet();

        if (rd.simulatedIPAddress.equals(packet.dstIP)) {
            System.out.println("Received message from " + packet.srcIP + ":");
            System.out.println(packet.message);
            return;
        }

        System.out.println(
        "Forwarding message from "
                + packet.srcIP
                + " to "
                + packet.dstIP);

        String path = getShortestPath(packet.dstIP);

        if (path == null) {
            System.out.println("Path not found");
            return;
        }

        String[] nodes = path.split("\\s*->\\s*");
        int localIndex = -1;

        for (int i = 0; i < nodes.length; i++) {
            if (rd.simulatedIPAddress.equals(nodes[i])) {
                localIndex = i;
                break;
            }
        }

        if (localIndex == -1 || localIndex >= nodes.length - 1) {
            System.out.println(
                    "Unable to find next hop to " + packet.dstIP);
            return;
        }

        String nextHop = nodes[localIndex + 1];

        System.out.println("Next hop: " + nextHop);

        sendApplicationPacket(
                packet.srcIP,
                packet.dstIP,
                packet.message,
                nextHop);
    }

    /**
     * update the weight of a specific port.
     * This change triggers synchronization of the Link State Database by sending
     * a Link State Advertisement (LSA) update to all neighboring routers, and
     * the remote endpoint is told explicitly (type 6 WEIGHT_UPDATE) so it can
     * update its own LinkDescription, bump its own sequence number and
     * advertise its revised LSA.
     *
     * @param portNumber the port number (0-3) to update
     * @param newWeight the new weight/cost for the link attached to this port
     */
    private void processUpdate(short portNumber, short newWeight) {
        if (portNumber < 0
                || portNumber >= ports.length
                || newWeight < 0) {
            System.out.println("Invalid port number or link weight.");
            return;
        }

        Link link;

        synchronized (portsLock) {
            link = ports[portNumber];
        }

        if (link == null || link.router2 == null) {
            System.out.println("That port is invalid or has no active connection.");
            return;
        }

        String neighborIP =
                link.router2.simulatedIPAddress;

        if (!setLocalLinkWeight(neighborIP, newWeight)) {
            System.out.println("No matching neighbor link was found.");
            return;
        }

        System.out.println("Link weight updated");
        System.out.println("Broadcasting LSAUPDATE");

        // Tell the remote endpoint so both sides keep the same weight.
        notifyNeighborWeight(link, newWeight);

        broadcastLSAUpdate();
    }

    /**
     * Set the weight of our LinkDescription for neighborIP and bump our
     * sequence number. Returns false if no such link exists.
     */
    private boolean setLocalLinkWeight(String neighborIP, short newWeight) {
        synchronized (portsLock) {
        for (Link link : ports) {
            if (link != null && link.router2 != null
                    && neighborIP.equals(link.router2.simulatedIPAddress)) {
                link.weight = newWeight;}}}
        synchronized (lsd._store) {
            LSA localLSA = lsd._store.get(rd.simulatedIPAddress);

            if (localLSA != null) {
                for (LinkDescription description : localLSA.links) {
                    if (neighborIP.equals(description.linkID)) {
                        description.weight = newWeight;
                        localLSA.lsaSeqNumber++;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Send the new weight of this link to the router at the other end. */
    private void notifyNeighborWeight(Link link, short newWeight) {
        try (Socket socket = new Socket(
                    link.router2.processIPAddress,
                    link.router2.processPortNumber);
             ObjectOutputStream out =
                    new ObjectOutputStream(socket.getOutputStream())) {

            SOSPFPacket packet = new SOSPFPacket();
            packet.sospfType = 6;
            packet.srcIP = rd.simulatedIPAddress;
            packet.dstIP = link.router2.simulatedIPAddress;
            packet.linkWeight = newWeight;
            out.writeObject(packet);
            out.flush();
        } catch (IOException e) {
            System.out.println("Unable to notify "
                    + link.router2.simulatedIPAddress + " about the new weight.");
        }
    }

    /** Handle a WEIGHT_UPDATE from the router at the other end of a link. */
    private void handleWeightUpdate(SOSPFPacket packet) {
        if (findNeighbor(packet.srcIP) == null) {
            return;
        }

        if (setLocalLinkWeight(packet.srcIP, packet.linkWeight)) {
            System.out.println("\nLink weight to " + packet.srcIP
                    + " updated to " + packet.linkWeight);
            broadcastLSAUpdate();
        }
    }

    private void processStats() {
        System.out.println("HELLO sent: " + helloSent.get());
        System.out.println("HELLO received: " + helloReceived.get());
        System.out.println("LSAUPDATE sent: " + lsaUpdateSent.get());
        System.out.println("LSAUPDATE received: " + lsaUpdateReceived.get());
        System.out.println("APP_MESSAGE sent: " + appMessageSent.get());
        System.out.println("APP_MESSAGE received: " + appMessageReceived.get());
        System.out.println("ATTACH_REQUEST sent: " + attachRequestSent.get());
        System.out.println("ATTACH_REQUEST received: " + attachRequestReceived.get());
        System.out.println("ATTACH_RESPONSE sent: " + attachResponseSent.get());
        System.out.println("ATTACH_RESPONSE received: " + attachResponseReceived.get());
        System.out.println("DISCONNECT sent: " + disconnectSent.get());
        System.out.println("DISCONNECT received: " + disconnectReceived.get());
    }

    /**
     * Remove this router from the network and exit.
     * Order matters:
     *  1. clear all links from our own LSA and bump the sequence number once;
     *  2. send ONE final LSAUPDATE with that LSA to all neighbors (ports are
     *     still populated, so every TWO_WAY neighbor receives it);
     *  3. send the explicit ROUTER_DOWN notification (type 5) to each
     *     neighbor so it removes its own adjacency and re-advertises;
     *  4. clear ports and terminate.
     */
    private void processQuit() {
        List<Link> links = new ArrayList<>();
        synchronized (portsLock) {
            for (Link link : ports) {
                if (link != null && link.router2 != null) {
                    links.add(link);
                }
            }
        }

        synchronized (lsd._store) {
            LSA localLSA = lsd._store.get(rd.simulatedIPAddress);
            if (localLSA != null) {
                localLSA.links.clear();
                localLSA.lsaSeqNumber++;
            }
        }

        System.out.println("Broadcasting final LSAUPDATE");
        broadcastLSAUpdate();

        for (Link link : links) {
            notifyNeighborDisconnect(link);
        }

        synchronized (portsLock) {
            Arrays.fill(ports, null);
        }

        System.out.println("Router shutting down.");
        System.exit(0);
    }

    private class IncomingPacketWorker implements Runnable {
        private final Socket socket;

        IncomingPacketWorker(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (Socket s = socket;
                 ObjectInputStream in =
                         new ObjectInputStream(s.getInputStream())) {

                Object received = in.readObject();

                if (!(received instanceof SOSPFPacket)) {
                    return;
                }

                SOSPFPacket packet = (SOSPFPacket) received;

                if (packet.sospfType == 3) {
                    attachRequestReceived.incrementAndGet();
                    boolean accepted = handleAttachRequest(packet);

                    if (accepted) {
                        accepted = installIncomingNeighbor(packet);
                    }

                    SOSPFPacket response = new SOSPFPacket();
                    response.sospfType = 4;
                    response.srcIP = rd.simulatedIPAddress;
                    response.dstIP = packet.srcIP;
                    response.accepted = accepted;

                    try (ObjectOutputStream out =
                        new ObjectOutputStream(s.getOutputStream())) {
                        out.writeObject(response);
                        out.flush();
                        attachResponseSent.incrementAndGet();
                    }

                    if (!accepted) {
                        System.out.println("Connection request declined.");
                    }

                } else if (packet.sospfType == 0) {
                    handleHello(packet);

                } else if (packet.sospfType == 1) {
                    handleLSAUpdate(packet);

                } else if (packet.sospfType == 2) {
                    handleApplicationMessage(packet);

                } else if (packet.sospfType == 5) {
                    disconnectReceived.incrementAndGet();
                    System.out.println("\nReceived disconnect notification from "
                            + packet.srcIP);
                    // removeNeighborLocal ignores duplicates, so the same
                    // adjacency loss is only processed once.
                    removeNeighborLocal(packet.srcIP);
                    System.out.println("Link to neighbor " + packet.srcIP + " has been closed.");

                } else if (packet.sospfType == 6) {
                    handleWeightUpdate(packet);

                } else {
                    System.out.println(
                            "Received unknown packet type: "
                                    + packet.sospfType);
                }

            } catch (Exception e) {
                System.err.println(
                        "Error handling incoming packet: "
                                + e.getMessage());
            }
        }
    }

    public void terminal() {
        try (BufferedReader br =
                     new BufferedReader(
                             new InputStreamReader(System.in))) {

            String command;

            System.out.print(">> ");

            while ((command = br.readLine()) != null) {
                command = command.trim();

                PendingAttach pending = pendingAttachRequests.peek();
                if (pending != null) {
                    if (command.equalsIgnoreCase("Y")) {
                        pendingAttachRequests.poll();
                        pending.decision.complete(true);
                    } else if (command.equalsIgnoreCase("N")) {
                        pendingAttachRequests.poll();
                        pending.decision.complete(false);
                    } else {
                        System.out.println("Please enter Y to approve or N to decline the pending connection.");
                    }
                    System.out.print(">> ");
                    continue;
                }

                if (command.isEmpty()) {
                    System.out.print(">> ");
                    continue;
                }

                String[] args = command.split("\\s+", 4);

                try {
                    if (command.startsWith("detect ")) {
                        if (args.length >= 2) {
                            processDetect(args[1]);
                        } else {
                            System.out.println(
                                    "Usage: detect <destination-IP>");
                        }

                    } else if (command.startsWith("disconnect ")) {
                        if (args.length >= 2) {
                            processDisconnect(
                                    Short.parseShort(args[1]));
                        } else {
                            System.out.println(
                                    "Usage: disconnect <port>");
                        }

                    } else if (command.equals("quit")) {
                        processQuit();
                        return;

                    } else if (command.startsWith("attach ")) {
                        if (args.length >= 4) {
                            String[] attachArgs =
                                    command.split("\\s+");

                            if (attachArgs.length == 5) {
                                processAttach(
                                        attachArgs[1],
                                        Short.parseShort(attachArgs[2]),
                                        attachArgs[3],
                                        Short.parseShort(attachArgs[4]));
                            } else {
                                System.out.println(
                                        "Usage: attach <processIP> "
                                                + "<processPort> "
                                                + "<simulatedIP> <weight>");
                            }
                        } else {
                            System.out.println(
                                    "Usage: attach <processIP> "
                                            + "<processPort> "
                                            + "<simulatedIP> <weight>");
                        }

                    } else if (command.equals("start")) {
                        processStart();

                    } else if (command.startsWith("connect ")) {
                        String[] connectArgs =
                                command.split("\\s+");

                        if (connectArgs.length == 5) {
                            processConnect(
                                    connectArgs[1],
                                    Short.parseShort(connectArgs[2]),
                                    connectArgs[3],
                                    Short.parseShort(connectArgs[4]));
                        } else {
                            System.out.println(
                                    "Usage: connect <processIP> "
                                            + "<processPort> "
                                            + "<simulatedIP> <weight>");
                        }

                    } else if (command.equals("neighbors")) {
                        processNeighbors();

                    } else if (command.startsWith("send ")) {

                        String[] sendArgs =
                                command.split("\\s+", 3);

                        if (sendArgs.length == 3) {
                            processSend(sendArgs[1], sendArgs[2]);
                        } else {
                            System.out.println(
                                    "Usage: send [Destination IP] [Message]");
                        }

                    } else if (command.startsWith("update ")) {

                        String[] updateArgs =
                                command.split("\\s+");

                        if (updateArgs.length == 3) {
                            processUpdate(
                                    Short.parseShort(updateArgs[1]),
                                    Short.parseShort(updateArgs[2]));
                        } else {
                            System.out.println(
                                    "Usage: update [port_number] [new_weight]");
                        }

                    } else if (command.equals("stats")) {
                        processStats();

                    } else {
                        System.out.println(
                                "Unrecognized command. Supported commands: "
                                        + "attach, connect, start, neighbors, "
                                        + "detect, send, update, disconnect, "
                                        + "stats, quit");
                    }

                } catch (NumberFormatException e) {
                    System.out.println(
                            "Invalid numeric argument: " + e.getMessage());
                } catch (Exception e) {
                    System.out.println(
                            "Command failed: " + e.getMessage());
                }

                System.out.print(">> ");
            }

        } catch (IOException e) {
            System.err.println(
                    "Terminal input error: " + e.getMessage());
        }
    }
}