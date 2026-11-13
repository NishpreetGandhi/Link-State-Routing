# COMP535 Project Starter Code

## Requirements

To compile and run this project, you need:

- **Java**: Version 8 or higher (JDK 8+)
- **Maven**: Version 3.0 or higher

### Checking Your Versions

You can check if you have the required versions installed:

```bash
java -version
mvn -version
```

If you don't have these installed, please install them before proceeding.

## Building the Project

To compile the project:

```bash
mvn clean compile
```

To compile and create a JAR file:

```bash
mvn clean package
```

The compiled classes will be in `target/classes/` directory.

## Running the Program

Create an executable JAR with dependencies and run it:

```bash
mvn clean package assembly:single
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf
```

## Troubleshooting

### "JAVA_HOME not set" error
- Make sure Java is installed and JAVA_HOME environment variable points to your Java installation
- On Linux/Mac: `export JAVA_HOME=/path/to/java`
- On Windows: Set JAVA_HOME in System Environment Variables

### "Maven not found" error
- Make sure Maven is installed and added to your PATH
- Verify with: `mvn -version`

### Compilation errors
- Ensure you have Java 8 or higher installed
- Ensure you have Maven 3.0 or higher installed
- Try: `mvn clean compile` to rebuild from scratch

### "Could not find or load main class" error
- Make sure you built the JAR file: `mvn clean package assembly:single`
- Verify the JAR exists: `ls target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar`
- Rebuild the project: `mvn clean package assembly:single`
- Use the correct command: `java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf`

### Maven settings.xml warning
- If you see a warning about "Unrecognised tag: 'blocked'" in Maven settings.xml, you can safely ignore it
- This is a system-level Maven configuration issue and does not affect the build
- The build will still succeed despite this warning

## Project Structure

```
src/
  main/
    java/          # Source code
  resources/       # Configuration files
conf/              # Router configuration files
target/            # Compiled classes (generated)
```


## PA1 Implementation and Running Instructions

### PA1 Topology-Building Functionality for Link-State Routing Simulation

Implementation of PA1 Functionality for Link-State Routing (LSR) Simulator

Implemented Commands 
attach: establish a link with another remote router
start: exchange HELLO message, establish two-way neighbors among routers. 
neighbors: show all routers which are in two-way state.

Build:

mvn clean package assembly:single

Run:

java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf

Router configuration files must be used appropriately for each router.




# COMP535 PA2 — Distributed Router

## Overview

This assignment consists of creating a router using Java and Maven. The routers interact with each other using SOSPF packets and keep a link state database to learn network routes.

## Requirements

- Java JDK
- Maven
- The provided router configuration files in the `conf` directory

## Build

From the project root, run:

mvn clean package assembly:single

## Run a Router

Open a separate terminal for each router. From the project root, run one router per terminal, using its configuration file:

java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf

For other routers, replace `router1.conf` with `router2.conf`, `router3.conf`, and so on, as needed.

## Supported Commands

- attach <processIP> <processPort> <neighborIP> <weight> — Request a connection to another router. The remote router must approve it (Y/N). It does not trigger LSDB synchronization.
- connect <processIP> <processPort> <neighborIP> <weight> — Like attach, but allowed only after start has run on this router. Completes the HELLO handshake and floods the new LSA to all neighbors.
- start — Send HELLO to the attached neighbors and synchronize the link-state database.
- neighbors — Display directly connected neighbors.
- detect <destinationIP> — Find the shortest path to a destination using the link-state database.
- send <destinationIP> <message> — Send an application message to a destination router, forwarded hop by hop along the shortest path.
- update <port> <weight> — Change the weight of a link.
- disconnect <port> — Disconnect a neighbor.
- stats — Display packet statistics.
- quit — Send a final LSAUPDATE, notify all neighbors, and exit.

## Testing

The implementation was tested with multiple router processes using configuration files in the `conf` directory.

Tests performed included:

- Attaching routers and accepting connection requests.
- Starting routers and exchanging link-state updates.
- Discovering a path across multiple routers using `detect`.
- Sending application messages between routers using `send`.
- Updating a link weight and checking the resulting route.
- Disconnecting and reconnecting a neighbor.
- Checking router packet statistics.
- Checking that a duplicate neighbor attachment is rejected.


## Project Files

- `src/main/java/socs/network/` — Router implementation and supporting classes.
- `conf/` — Router configuration files.
- `pom.xml` — Maven build configuration.


# COMP535 PA3 — Dynamic Topology and Failure Detection

## Overview

PA3 extends the PA2 router with `connect`, `update`, `disconnect` and `quit`, so the topology and link weights can change while the network is running. After each change, the routers update their ports, their own LSA and their LSDB, and `detect` and `send` use the shortest paths. A failure-detection mechanism handles adjacency loss caused by `disconnect` and `quit`.

Build and run commands are unchanged from PA2.

## New and Updated Commands

- `connect <processIP> <processPort> <neighborIP> <weight>`: like `attach`, but allowed only after `start` has run on this router. Completes the HELLO handshake so both ends reach TWO_WAY, then floods the new LSA.
- `update <port> <weight>`: changes the cost of the link on port 0 to 3. Invalid or unused ports are rejected.
- `disconnect <port>`: removes the link on port 0 to 3. The freed port can be reused by a later `attach` or `connect`.
- `quit`: removes this router from the network and exits.

## Implementation Notes

- Each router has 4 ports. A new link takes the lowest free port.
- Each router owns one LSA. Every change to its own links (new link, removed link, weight change) increments that LSA's sequence number once, then the revised LSA is flooded.
- A router accepts a received LSA only if its sequence number is newer than the stored one, and floods it to all TWO_WAY neighbors except the one it came from. It floods only when its database actually changed. A router never overwrites its own LSA with a received copy.
- `detect` and `send` calculate the shortest path from the current LSDB each time.
- The weight of each link is stored in both the Link object (in the ports array) and the LinkDescription (in the router's own LSA), and both are updated together.

### Packet types

| sospfType | Meaning |
|-----------|---------|
| 0 | HELLO |
| 1 | LSAUPDATE |
| 2 | Application message |
| 3 | Attach request |
| 4 | Attach response |
| 5 | LINK_DOWN / ROUTER_DOWN (failure notification) |
| 6 | WEIGHT_UPDATE |

### connect
1. Rejected if `start` has not been run on this router.
2. Attach handshake (types 3 and 4) the remote router must approve.
3. Both endpoints add the link to their ports and to their own LSA, and increment their own sequence number.
4. HELLO exchange brings both ends to TWO_WAY, then an LSAUPDATE is flooded.

### update
1. Update the local LinkDescription, increment the local sequence number and flood an LSAUPDATE to all neighbors.
2. Send a type 6 WEIGHT_UPDATE to the router at the other end. It updates its own LinkDescription for the same link, increments its own sequence number and advertises the revised LSA.

`update` is not a failure, so it does not use the failure-detection mechanism.

### disconnect
1. Send a type 5 notification to the remote router.
2. Remove the Link from ports, remove the LinkDescription from the local LSA, increment the sequence number and flood the revised LSA.
3. The remote router does the same on receiving the notification.

### quit
1. Clear all links from this router's own LSA and increment its sequence number once.
2. Send one final LSAUPDATE with that LSA to all neighbors.
3. Send a type 5 notification to every neighbor.
4. Clear the ports and exit.

## Failure-Detection Mechanism

Approach: explicit protocol notifications. No heartbeat or keepalive timer is used.

- On `disconnect`, the initiating router sends a type 5 packet (LINK_DOWN) to the router at the other end of the link.
- On `quit`, the quitting router sends a type 5 packet (ROUTER_DOWN) to each directly connected neighbor, after its final LSAUPDATE.

What a router does on receiving a type 5 packet from router X:
1. Remove the Link to X from its ports array (freeing the port).
2. Remove the LinkDescription for X from its own LSA.
3. Increment its own LSA sequence number.
4. Flood its revised LSA to its remaining TWO_WAY neighbors.

Duplicate handling- The same adjacency loss is processed only once. The removal routine returns immediately if X is no longer in the ports array, so a repeated notification does not remove the adjacency again and does not increment the sequence number a second time.

Interaction with HELLO- HELLO is used only for the initial INIT to TWO_WAY handshake. It is not used as a keepalive.

Why both the final LSAUPDATE and the notification are needed- The final LSAUPDATE updates the quitting router's own LSA in every router's LSDB. The type 5 notification makes each directly connected neighbor update its own ports array and its own LSA, so a neighbor does not rely only on the quitting router's final LSA.

Limitation- A router that crashes without running `quit` is not detected, because there is no heartbeat or timeout.

## PA3 Testing

Tests performed with multiple router processes:

- `connect` was rejected before `start` and worked after it, with the new neighbor showing in `neighbors` and in `detect`.
- `update` was applied on both endpoints, and `detect` switched to the new shortest path.
- Invalid ports were rejected for `update` and `disconnect`.
- `disconnect` updated both ends, and the freed port was reused by a later `connect`.
- `quit` on a leaf router and on a middle router: the neighbors removed the links and the surviving routers printed `Path not found` for the quit router.
- Reconnecting a router after it quit.
- A declined connection request, and a duplicate connection attempt, were rejected.

## Collaboration Statement

I completed this project individually. All implementation, testing, and documentation were done by me.
