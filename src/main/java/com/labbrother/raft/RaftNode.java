package com.labbrother.raft;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.googlecode.jsonrpc4j.ProxyUtil;
import com.labbrother.model.Address;
import com.labbrother.model.LogEntry;
import com.labbrother.model.Message;
import com.labbrother.model.TransactionResult;
import com.labbrother.network.RpcService;

public class RaftNode implements RpcService {

    // --- 1. Node Identity ---
    private Address myAddress;
    private List<Address> peers;
    private NodeState state;

    // --- 2. Persistent State ---
    private int currentTerm;
    private Address votedFor;
    private List<LogEntry> log;

    // --- 3. Volatile State ---
    private int commitIndex;
    private int lastApplied;
    private Address leaderAddress;

    // --- 4. Support Components ---
    private Map<Address, Integer> nextIndex; // Next log index to send to follower
    private Map<Address, Integer> matchIndex; // Highest known replicated log index on follower
    private StateMachine stateMachine;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimerTask;
    private ScheduledFuture<?> heartbeatTimerTask;
    private Random random;
    private Map<Integer, String> executionResults = new ConcurrentHashMap<>();

    // Time Constants (in milliseconds)
    private static final int HEARTBEAT_INTERVAL = 500;
    private static final int ELECTION_MIN_DELAY = 1500;
    private static final int ELECTION_MAX_DELAY = 3000;

    public RaftNode(Address myAddress, List<Address> peers) {
        this.myAddress = myAddress;
        this.peers = peers;
        this.stateMachine = new StateMachine();
        this.log = new ArrayList<>();
        // Pool size 4 for timer and parallel network calls
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.random = new Random();

        this.state = NodeState.FOLLOWER;
        this.currentTerm = 0;
        this.votedFor = null;
        this.commitIndex = -1;
        this.lastApplied = -1;

        // Initialize volatile leader state
        this.nextIndex = new ConcurrentHashMap<>();
        this.matchIndex = new ConcurrentHashMap<>();

        System.out.println("Node " + myAddress + " started. State: FOLLOWER");
    }

    public void start() {
        // Start election timer when node starts
        resetElectionTimer();
        System.out.println("Raft Node running at " + myAddress);
    }

    // ==============================================================================
    // ELECTION & HEARTBEAT
    // ==============================================================================
    // ==============================================================================

    private synchronized void resetElectionTimer() {
        if (electionTimerTask != null && !electionTimerTask.isCancelled()) {
            electionTimerTask.cancel(false);
        }
        int delay = ELECTION_MIN_DELAY + random.nextInt(ELECTION_MAX_DELAY - ELECTION_MIN_DELAY);
        electionTimerTask = scheduler.schedule(this::runElection, delay, TimeUnit.MILLISECONDS);
    }

    private void runElection() {
        if (state == NodeState.LEADER) {
            System.out.println(myAddress + " [Election] Already Leader, skipping election");
            return;
        }

        // 1. Prepare for election
        synchronized (this) {
            state = NodeState.CANDIDATE;
            currentTerm++;
            votedFor = myAddress;
        }

        System.out.println("!!! ELECTION STARTED !!! Term: " + currentTerm + " | Candidate: " + myAddress);

        // 2. Count votes (Atomic for thread safety)
        AtomicInteger votesReceived = new AtomicInteger(1); // Vote from self
        int requiredVotes = (peers.size() + 1) / 2 + 1; // Majority: (N/2) + 1

        // Single node cluster wins immediately
        if (votesReceived.get() >= requiredVotes) {
            becomeLeader();
            return;
        }

        // Get local node's last log entry
        int myLastLogIndex = log.isEmpty() ? -1 : log.size() - 1;
        int myLastLogTerm = log.isEmpty() ? 0 : log.get(myLastLogIndex).getTerm();

        // 3. Send RequestVote to ALL Peers in parallel
        for (Address peer : peers) {
            scheduler.submit(() -> {
                try {
                    RpcService peerProxy = getPeerProxy(peer);

                    // Payload contains log details for receiver validation
                    Map<String, Integer> payload = new HashMap<>();
                    payload.put("lastLogIndex", myLastLogIndex);
                    payload.put("lastLogTerm", myLastLogTerm);

                    Message request = new Message("REQUEST_VOTE", myAddress, currentTerm, payload);

                    System.out.println(" -> Sending RequestVote to " + peer);
                    Message response = peerProxy.requestVote(request);

                    if (response != null) {
                        if ((boolean) response.getPayload()) {
                            int totalVotes = votesReceived.incrementAndGet();
                            System.out.println(" + Got vote from " + peer + " (Total: " + totalVotes + ")");
                            if (totalVotes >= requiredVotes && state == NodeState.CANDIDATE) {
                                becomeLeader();
                            }
                        } else if (response.getTerm() > currentTerm) {
                            stepDown(response.getTerm());
                        }
                    }
                } catch (Exception e) {
                    // Peer down/timeout, ignore
                }
            });
        }

        // Reset timer, if lost this election, timer will expire and start new election
        resetElectionTimer();
    }

    private synchronized void becomeLeader() {
        if (state != NodeState.CANDIDATE)
            return;

        state = NodeState.LEADER;
        leaderAddress = myAddress;
        System.out.println("$$$ I AM THE LEADER $$$ Term: " + currentTerm);

        // Reset volatile leader state
        nextIndex.clear();
        matchIndex.clear();
        int lastLogIndex = log.size(); // Next log index (empty)

        for (Address peer : peers) {
            nextIndex.put(peer, lastLogIndex);
            matchIndex.put(peer, -1);
        }

        // Cancel election timer with proper null check
        if (electionTimerTask != null && !electionTimerTask.isCancelled()) {
            electionTimerTask.cancel(false);
            electionTimerTask = null;
        }

        startHeartbeat();
    }

    private synchronized void stepDown(int newTerm) {
        currentTerm = newTerm;
        state = NodeState.FOLLOWER;
        votedFor = null;
        leaderAddress = null;

        // Cancel heartbeat timer if exists
        if (heartbeatTimerTask != null && !heartbeatTimerTask.isCancelled()) {
            heartbeatTimerTask.cancel(false);
            heartbeatTimerTask = null;
        }

        resetElectionTimer();
        System.out.println("Stepping down to FOLLOWER. New term: " + currentTerm);
    }

    private void startHeartbeat() {
        heartbeatTimerTask = scheduler.scheduleAtFixedRate(() -> {
            if (state != NodeState.LEADER)
                return;
            sendHeartbeat();
        }, 0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        if (state != NodeState.LEADER)
            return; // Double check

        System.out.println("[Leader] Sending Heartbeat...");
        for (Address peer : peers) {
            scheduler.submit(() -> {
                try {
                    // Call replicateLogToPeer with acksReceived = null (for Heartbeat)
                    replicateLogToPeer(peer, null);
                } catch (Exception e) {
                    // Silent fail for heartbeat
                }
            });
        }
    }

    private RpcService getPeerProxy(Address address) throws Exception {
        URL url = new URL("http://" + address.getIp() + ":" + address.getPort() + "/");
        JsonRpcHttpClient client = new JsonRpcHttpClient(url);
        client.setConnectionTimeoutMillis(500);
        client.setReadTimeoutMillis(500);
        return ProxyUtil.createClientProxy(getClass().getClassLoader(), RpcService.class, client);
    }

    // --- RPC HANDLER IMPLEMENTATION (Receiver) ---

    @Override
    public synchronized Message requestVote(Message request) {
        // Add candidate as peer if not already known
        addPeer(request.getSender());

        int candidateTerm = request.getTerm();
        Address candidateAddress = request.getSender();

        if (candidateTerm > currentTerm) {
            stepDown(candidateTerm);
        }

        boolean voteGranted = false;

        // --- Get Log Details from Candidate ---
        int candidateLastLogIndex = -1;
        int candidateLastLogTerm = 0;

        if (request.getPayload() != null) {
            Map<String, Integer> candidateLog = (Map<String, Integer>) request.getPayload();
            candidateLastLogIndex = candidateLog.getOrDefault("lastLogIndex", -1);
            candidateLastLogTerm = candidateLog.getOrDefault("lastLogTerm", 0);
        } else {
            // If payload is null, assume candidate has empty log
            System.out.println(myAddress + " [RequestVote] WARNING: Null payload from " +
                    request.getSender() + ", assuming empty log");
        }

        if (candidateTerm == currentTerm && (votedFor == null || votedFor.equals(candidateAddress))) {
            if (isLogUpToDate(candidateLastLogIndex, candidateLastLogTerm)) {
                voteGranted = true;
                votedFor = candidateAddress;
                resetElectionTimer();
                System.out.println("Vote granted to " + candidateAddress);
            }
        }

        return new Message("VOTE_RESPONSE", myAddress, currentTerm, voteGranted);
    }

    // ==============================================================================
    // LOG REPLICATION & EXECUTION
    // ==============================================================================
    // ==============================================================================

    private List<LogEntry> convertToLogEntries(Object entriesObject) {
        if (entriesObject == null) {
            return null;
        }

        List<LogEntry> logEntries = new ArrayList<>();

        try {
            List<Map<String, Object>> rawList = (List<Map<String, Object>>) entriesObject;

            for (Map<String, Object> map : rawList) {
                int term = (Integer) map.get("term");
                String command = (String) map.get("command");
                logEntries.add(new LogEntry(term, command));
            }

            return logEntries;

        } catch (Exception e) {
            System.err.println("Error converting entries: " + e.getMessage());
            return null;
        }
    }

    @Override
    public synchronized Message appendEntries(Message request) {
        // Add sender as peer if not already known
        addPeer(request.getSender());

        int leaderTerm = request.getTerm();

        // 1. Leader Validation (Term Check)
        if (leaderTerm < currentTerm) {
            // Leader has smaller Term. Reject.
            System.out
                    .println(myAddress + " [AE] Rejected Leader Term " + leaderTerm + ". My Term: " + currentTerm);
            return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
        }

        // 2. Acknowledge Leader and Reset Timer
        // If Leader Term is greater, or Term is same but I'm not Follower, step down.
        if (leaderTerm > currentTerm || state != NodeState.FOLLOWER) {
            System.out
                    .println(myAddress + " [AE] Acknowledging Leader " + request.getSender() + ", New term: "
                            + leaderTerm);
            currentTerm = leaderTerm;
            state = NodeState.FOLLOWER; // Step down to Follower
            votedFor = null;
        }
        // Ensure timer is reset to prevent new Election
        resetElectionTimer();
        leaderAddress = request.getSender(); // Record the new/active Leader

        // Pure heartbeat (Payload logEntries = null)
        if (request.getPayload() == null) {
            // Heartbeat successful, check commitIndex below.
            System.out.println(myAddress + " [AE] Heartbeat received from " + leaderAddress);
        } else {
            // --- Log Replication Request ---

            // Payload contains Map for log details and List<LogEntry> for new entries
            Map<String, Object> payloadMap = (Map<String, Object>) request.getPayload();

            // Learn about other peers from leader
            Object clusterPeersObj = payloadMap.get("clusterPeers");
            if (clusterPeersObj != null) {
                try {
                    List<Map<String, Object>> peerMaps = (List<Map<String, Object>>) clusterPeersObj;
                    for (Map<String, Object> peerMap : peerMaps) {
                        String ip = (String) peerMap.get("ip");
                        Integer port = (Integer) peerMap.get("port");
                        if (ip != null && port != null) {
                            Address peerAddr = new Address(ip, port);
                            addPeer(peerAddr);
                        }
                    }
                } catch (Exception e) {
                    System.err.println(myAddress + " [AE] Error parsing clusterPeers: " + e.getMessage());
                }
            }

            // Get PrevLog Index and Term from request
            int prevLogIndex = (int) payloadMap.getOrDefault("prevLogIndex", -1);
            int prevLogTerm = (int) payloadMap.getOrDefault("prevLogTerm", 0);

            // Get Log Entries to be added
            List<LogEntry> newEntries = convertToLogEntries(payloadMap.get("entries"));

            System.out.println(myAddress + " [AE] Log received. Prev Index: " + prevLogIndex +
                    " | New Entries: " + (newEntries == null ? 0 : newEntries.size()));

            // Log Matching Property check
            // Check if log at prevLogIndex matches Leader's Term
            if (prevLogIndex >= 0) {
                if (prevLogIndex >= log.size()) {
                    // 3A. Local Log Too Short
                    System.out.println(myAddress + " [AE] FAILED: Local log too short at Index " + prevLogIndex);
                    // Ask Leader to send earlier logs
                    return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
                }

                LogEntry entryAtPrevIndex = log.get(prevLogIndex);
                if (entryAtPrevIndex.getTerm() != prevLogTerm) {
                    // 3B. Log Term Conflict
                    System.out.println(myAddress + " [AE] FAILED: Term conflict at Index " + prevLogIndex +
                            ". Local Term: " + entryAtPrevIndex.getTerm() +
                            " | Leader Term: " + prevLogTerm);
                    // Delete all conflicting logs from this index onwards
                    log.subList(prevLogIndex, log.size()).clear();
                    // Ask Leader to resend
                    return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
                }
            }

            // Append new entries to log
            if (newEntries != null && !newEntries.isEmpty()) {
                // Add new entries to local log
                for (int i = 0; i < newEntries.size(); i++) {
                    LogEntry newEntry = newEntries.get(i);
                    int index = prevLogIndex + 1 + i;

                    if (index < log.size()) {
                        // If entry already exists at this index
                        LogEntry existingEntry = log.get(index);
                        if (existingEntry.getTerm() != newEntry.getTerm()) {
                            // Term Conflict: Delete existing entry and all after
                            log.subList(index, log.size()).clear();
                            log.add(newEntry);
                        }
                    } else {
                        // No conflict, directly add
                        log.add(newEntry);
                    }
                }
            }

            // 5. Update Commit Index (Rule 4)
            int leaderCommit = (int) payloadMap.getOrDefault("leaderCommit", -1);
            if (leaderCommit > commitIndex) {
                // Commit index cannot exceed last added log index
                commitIndex = Math.min(leaderCommit, log.size() - 1);
                // Apply committed logs to state machine
                applyLogToStateMachine();
            }

            //
        }

        // Include our peer list in response so leader learns about all cluster members
        Map<String, Object> responsePayload = new HashMap<>();
        responsePayload.put("success", true);
        List<Map<String, Object>> myPeerList = new ArrayList<>();
        for (Address p : peers) {
            Map<String, Object> peerInfo = new HashMap<>();
            peerInfo.put("ip", p.getIp());
            peerInfo.put("port", p.getPort());
            myPeerList.add(peerInfo);
        }
        responsePayload.put("clusterPeers", myPeerList);

        return new Message("APPEND_RESPONSE", myAddress, currentTerm, responsePayload);
    }

    // ... (Volatile state: commitIndex, lastApplied, etc.)

    // ... (Constructor and start())

    // --- Internal Raft Methods (Complete Implementation) ---

    // Method to apply committed Log to State Machine
    private synchronized void applyLogToStateMachine() {
        // Only apply committed entries (commitIndex) that haven't been applied yet
        // (lastApplied)
        while (commitIndex > lastApplied) {
            lastApplied++;

            // Get LogEntry at the index to be applied
            LogEntry entry = log.get(lastApplied);

            // Parse and execute command on StateMachine (KVStore)
            String result = parseAndExecuteCommand(entry.getCommand());

            // Store execution result to be retrieved by execute()
            executionResults.put(lastApplied, result);

            // Log the applied action
            System.out.println(myAddress + " [APPLY] Log Index " + lastApplied + " (Term " + entry.getTerm() +
                    ") applied. Command: " + entry.getCommand() + ". Result: " + result);

            // Note: If this is a GET/PING command, result is ignored
            // since log is only for state-changing operations (SET, APPEND, DEL).
        }

        // Notify waiting threads
        this.notifyAll();
    }

    /**
     * Helper to parse command string from Raft Log
     * and execute it on StateMachine (KVStore).
     * 
     * @param commandString Example: "set key1 val1" or "ping"
     * @return Execution result from StateMachine (String)
     */
    private String parseAndExecuteCommand(String commandString) {
        // Handle Transaction commands (TXN: prefix)
        if (commandString.startsWith("TXN:")) {
            return executeTransactionCommand(commandString);
        }

        // Split command string into max 3 parts: COMMAND KEY VALUE
        String[] parts = commandString.trim().split("\\s+", 3);

        if (parts.length == 0)
            return "ERROR: Empty command";

        String command = parts[0].toLowerCase();
        String key = parts.length > 1 ? parts[1] : "";
        String value = parts.length > 2 ? parts[2] : "";

        try {
            // Logika eksekusi KV Store
            switch (command) {
                case "ping":
                    return stateMachine.ping();
                case "set":
                    return stateMachine.set(key, value);
                case "append":
                    return stateMachine.append(key, value);
                case "del":
                    // del mengembalikan nilai yang dihapus
                    return stateMachine.del(key);
                case "strln":
                    // strln mengembalikan int, kita ubah jadi string
                    return String.valueOf(stateMachine.strln(key));
                case "get":
                    // get tidak mengubah state, tapi jika ada di log, tetap di-apply (meskipun ini
                    // tidak efisien)
                    return stateMachine.get(key);
                default:
                    // Di masa depan, di sini bisa diisi logika Membership Change (C/O)
                    return "ERROR: Unknown command in log: " + command;
            }
        } catch (Exception e) {
            // Handle error eksekusi (misalnya, parsing gagal)
            return "ERROR executing command: " + e.getMessage();
        }
    }

    /**
     * Execute a transaction command.
     * Format: TXN:txnId:["cmd1","cmd2",...]
     * 
     * @param transactionPayload The full TXN: prefixed command
     * @return Result string indicating success/failure
     */
    private String executeTransactionCommand(String transactionPayload) {
        try {
            // Parse TXN:txnId:[...]
            String content = transactionPayload.substring(4); // Remove "TXN:"
            int colonIndex = content.indexOf(':');

            if (colonIndex == -1) {
                return "ERROR: Invalid transaction format";
            }

            String transactionId = content.substring(0, colonIndex);
            String jsonCommands = content.substring(colonIndex + 1);

            // Parse JSON array of commands
            ObjectMapper mapper = new ObjectMapper();
            List<String> commands = mapper.readValue(jsonCommands, new TypeReference<List<String>>() {
            });

            // Execute transaction on StateMachine
            TransactionResult result = stateMachine.executeTransaction(transactionId, commands);

            System.out.println(myAddress + " [TXN] " + result.toString());

            if (result.isSuccess()) {
                // Return success with results as JSON
                return "TXN_OK:" + mapper.writeValueAsString(result.getResults());
            } else {
                return "TXN_FAIL:" + result.getErrorMessage();
            }

        } catch (Exception e) {
            return "ERROR: Transaction parsing failed: " + e.getMessage();
        }
    }

    @Override
    public Message execute(Message request) {
        // Ensure synchronized access to RaftNode state
        synchronized (this) {
            // 1. Check State: If not LEADER, return REDIRECT
            if (state != NodeState.LEADER) {
                System.out.println(
                        myAddress + " [Execute] Rejected from " + request.getSender() + ". Redirect to "
                                + leaderAddress);
                return createRedirectResponse();
            }

            // Handle Client Request

            String commandString = (String) request.getPayload();
            String command = commandString.trim().split("\\s+", 2)[0].toLowerCase();

            // If PING, GET, execute immediately without log replication
            if (command.equals("ping") || command.equals("get") || command.equals("strln")) {
                String result = parseAndExecuteCommand(commandString);
                Message response = new Message("EXECUTE_RESPONSE", myAddress, currentTerm, result);
                response.setStatus("OK");
                return response;
            }

            // 2. Create new LogEntry and add locally
            LogEntry newEntry = new LogEntry(currentTerm, commandString);
            int newLogIndex = log.size();
            log.add(newEntry);
            System.out.println(myAddress + " [Execute] New log added locally: " + newEntry.toString());

            // Update Leader's matchIndex and nextIndex for itself
            matchIndex.put(myAddress, log.size() - 1);
            nextIndex.put(myAddress, log.size());

            // 3. Initiate Log Replication to Followers

            // Use AtomicInteger to count successful ACKs (including local append)
            AtomicInteger acksReceived = new AtomicInteger(1);
            int requiredAcks = (peers.size() + 1) / 2 + 1; // Majority

            if (peers.isEmpty() || requiredAcks <= 1) {
                // Cluster has only 1 node (self). Commit immediately.
            } else {
                // Send log to all peers in parallel
                for (Address peer : peers) {
                    // Run in scheduler since replicateLogToPeer performs blocking RPC
                    scheduler.submit(() -> replicateLogToPeer(peer, acksReceived));
                }
            }

            // 4. Block client thread until replication completes (or timeout)
            long startTime = System.currentTimeMillis();
            long timeout = 5000; // 5 second timeout (Example)

            boolean majorityReached = false;
            while (System.currentTimeMillis() - startTime < timeout) {
                if (acksReceived.get() >= requiredAcks) {
                    majorityReached = true;
                    break;
                }

                try {
                    // Wait for notification from replicateLogToPeer when ACK is received
                    this.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 5. Apply Log dan Respon
            if (majorityReached) {
                updateCommitIndex();

                // Tunggu sampai log ini ter-apply
                long waitStart = System.currentTimeMillis();
                while (lastApplied < newLogIndex && (System.currentTimeMillis() - waitStart) < 1000) {
                    try {
                        this.wait(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                // Ambil hasil dari cache (sudah dieksekusi di applyLogToStateMachine)
                String result = executionResults.getOrDefault(newLogIndex, "OK");
                executionResults.remove(newLogIndex); // Cleanup

                Message response = new Message("EXECUTE_RESPONSE", myAddress, currentTerm, result);
                response.setStatus("OK");
                return response;
            } else {
                Message response = new Message("FAIL", myAddress, currentTerm,
                        "Log replication failed: Timeout waiting for majority ACK.");
                response.setStatus("FAIL");
                return response;
            }
        }
    }

    /**
     * Helper untuk mengirim Log/Heartbeat ke peer tertentu.
     * 
     * @param peer         Alamat follower
     * @param acksReceived AtomicInteger untuk menghitung ACK sukses dari client
     *                     request. Null jika dipanggil dari Heartbeat.
     */
    private void replicateLogToPeer(Address peer, AtomicInteger acksReceived) {
        try {
            RpcService peerProxy = getPeerProxy(peer);

            synchronized (this) {
                // Initialize nextIndex correctly for new peers
                int lastLogIndex = log.size() - 1;
                int nextIdx = nextIndex.getOrDefault(peer, lastLogIndex);

                // Pastikan nextIdx tidak melebihi size (jika ada race condition)
                if (nextIdx > log.size())
                    nextIdx = log.size();

                int prevLogIndex = nextIdx - 1;

                List<LogEntry> entriesToSend = Collections.emptyList();
                if (nextIdx < log.size()) {
                    entriesToSend = new ArrayList<>(log.subList(nextIdx, log.size()));
                }

                Map<String, Object> payload = new HashMap<>();
                payload.put("prevLogIndex", prevLogIndex);

                int prevLogTerm = (prevLogIndex >= 0 && prevLogIndex < log.size())
                        ? log.get(prevLogIndex).getTerm()
                        : 0;
                payload.put("prevLogTerm", prevLogTerm);
                payload.put("leaderCommit", commitIndex);
                payload.put("entries", entriesToSend);

                // Include known peers so followers learn about the cluster
                List<Map<String, Object>> clusterPeers = new ArrayList<>();
                for (Address p : peers) {
                    Map<String, Object> peerInfo = new HashMap<>();
                    peerInfo.put("ip", p.getIp());
                    peerInfo.put("port", p.getPort());
                    clusterPeers.add(peerInfo);
                }
                // Also include self (leader) so follower knows about leader
                Map<String, Object> selfInfo = new HashMap<>();
                selfInfo.put("ip", myAddress.getIp());
                selfInfo.put("port", myAddress.getPort());
                clusterPeers.add(selfInfo);
                payload.put("clusterPeers", clusterPeers);

                // Debug Print
                // System.out.println("DEBUG: Mengirim ke " + peer + " | prevIdx: " +
                // prevLogIndex + " | entries: " + entriesToSend.size());

                Message request = new Message("APPEND_ENTRIES", myAddress, currentTerm, payload);
                Message response = peerProxy.appendEntries(request);

                if (response != null) {
                    if (response.getTerm() > currentTerm) {
                        stepDown(response.getTerm());
                        return;
                    }

                    // Handle both old (boolean) and new (Map) response formats
                    Object responsePayload = response.getPayload();
                    boolean success = false;

                    if (responsePayload instanceof Boolean) {
                        success = (Boolean) responsePayload;
                    } else if (responsePayload instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> responseMap = (Map<String, Object>) responsePayload;
                        success = Boolean.TRUE.equals(responseMap.get("success"));

                        // Learn about new peers from follower's response
                        Object followerPeers = responseMap.get("clusterPeers");
                        if (followerPeers instanceof List) {
                            try {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> peerMaps = (List<Map<String, Object>>) followerPeers;
                                for (Map<String, Object> peerMap : peerMaps) {
                                    String ip = (String) peerMap.get("ip");
                                    Object portObj = peerMap.get("port");
                                    if (ip != null && portObj != null) {
                                        int port = (portObj instanceof Integer) ? (Integer) portObj
                                                : Integer.parseInt(portObj.toString());
                                        addPeer(new Address(ip, port));
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println(myAddress + " [Rep] Error parsing peer list: " + e.getMessage());
                            }
                        }
                    }

                    if (success) {
                        // SUKSES
                        int newMatchIndex = prevLogIndex + entriesToSend.size();
                        matchIndex.put(peer, newMatchIndex);
                        nextIndex.put(peer, newMatchIndex + 1);

                        // Print only for actual log replication, not heartbeats
                        if (!entriesToSend.isEmpty()) {
                            System.out.println(
                                    myAddress + " [Rep] Sukses ke " + peer + ". Match Index: " + newMatchIndex);
                        }

                        if (acksReceived != null) {
                            acksReceived.incrementAndGet();
                            this.notifyAll();
                        }
                        updateCommitIndex();

                    } else {
                        // GAGAL (Log Matching Failed)
                        int newNextIndex = nextIndex.getOrDefault(peer, 1);
                        if (newNextIndex > 0) { // Fix infinite loop di 0
                            nextIndex.put(peer, newNextIndex - 1);
                            // System.out.println(myAddress + " [Rep] Gagal ke " + peer + ". Mundur Next
                            // Index ke: " + (newNextIndex - 1));

                            // Retry rekursif
                            replicateLogToPeer(peer, acksReceived);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log connection errors
            System.err.println(myAddress + " [Error Rep] Gagal ke " + peer + ": " + e.getMessage());
            // e.printStackTrace(); // Uncomment jika butuh detail lengkap
        }
    }

    // ... (Metode-metode lain)

    private boolean isLogUpToDate(int candidateLastLogIndex, int candidateLastLogTerm) {
        // Log Up-to-Date Rule implementation

        // 1. Dapatkan Log Terakhir Node Lokal (Saya)
        int myLastLogIndex = log.isEmpty() ? -1 : log.size() - 1;
        // Term log terakhir 0 jika log kosong
        int myLastLogTerm = log.isEmpty() ? 0 : log.get(myLastLogIndex).getTerm();

        System.out.println(
                myAddress + " [Log Check] Kandidat: Term " + candidateLastLogTerm + " Index " + candidateLastLogIndex +
                        " | Saya: Term " + myLastLogTerm + " Index " + myLastLogIndex);

        // Logika Raft Rule:
        // Kandidat diizinkan menang jika lognya lebih up-to-date atau sama
        // up-to-date-nya.
        // Log dibandingkan dulu berdasarkan Term, baru Index.

        // a. Jika Last Log Term Kandidat lebih besar, maka Kandidat lebih up-to-date.
        if (candidateLastLogTerm > myLastLogTerm) {
            return true;
        }

        // b. Jika Last Log Term sama, cek panjang log. Log yang lebih panjang lebih
        // up-to-date.
        if (candidateLastLogTerm == myLastLogTerm && candidateLastLogIndex >= myLastLogIndex) {
            return true;
        }

        // Kandidat memiliki Term atau Index yang lebih kecil.
        return false;
    }

    // ... (Metode-metode lain)

    // ==============================================================================
    // MEMBERSHIP & UTILS
    // ==============================================================================

    @Override
    public Message requestLog(Message request) {
        // Gunakan synchronized untuk akses state yang aman
        synchronized (this) {
            // 1. Cek Authority: Hanya Leader yang boleh mengembalikan Log
            if (state != NodeState.LEADER) {
                // Jika saya bukan Leader, beritahu Client alamat Leader yang saya tahu
                return createRedirectResponse();
            }

            // 2. Return Log
            // Kita kembalikan salinan (copy) dari log agar list asli aman dari modifikasi
            // luar
            List<LogEntry> logCopy = new ArrayList<>(log);

            System.out.println(myAddress + " [LogRequest] Mengirimkan " + log.size() + " entri log ke Client.");

            return new Message("LOG_RESPONSE", myAddress, currentTerm, logCopy);
        }
    }

    public synchronized void addPeer(Address newPeer) {
        // Auto Discovery: add peer if not already known
        if (!newPeer.equals(myAddress) && !peers.contains(newPeer)) {
            peers.add(newPeer);
            System.out.println("[Membership] Node baru ditemukan & ditambahkan: " + newPeer);
        }
    }

    public synchronized void removePeer(Address peer) {
        // Remove peer from cluster membership
        if (peers.contains(peer)) {
            peers.remove(peer);

            // Jika saya Leader, bersihkan state tracking untuk peer ini
            if (nextIndex != null) {
                nextIndex.remove(peer);
            }
            if (matchIndex != null) {
                matchIndex.remove(peer);
            }

            System.out.println("[Membership] Node dihapus dari cluster: " + peer);

            // Opsional: Cek ulang komitmen log karena quorum mungkin berubah (jumlah peer
            // berkurang)
            if (state == NodeState.LEADER) {
                updateCommitIndex();
            }
        }
    }

    // --- Redirect Response Helper ---
    private Message createRedirectResponse() {
        // Create redirect response to leader
        return new Message("REDIRECT", myAddress, currentTerm, leaderAddress);
    }

    // --- Commit Index Update Helper ---
    private synchronized void updateCommitIndex() {
        // Logika untuk mengupdate commitIndex

        int N = log.size();
        for (int i = commitIndex + 1; i < N; i++) {
            LogEntry entry = log.get(i);

            // Raft Rule: Hanya bisa meng-commit log dari Term saat ini
            if (entry.getTerm() != currentTerm)
                continue;

            int count = 1; // Leader sendiri sudah mereplikasi (local append)
            for (Address peer : peers) {
                // Hitung berapa banyak follower yang sudah mereplikasi log di index 'i'
                if (matchIndex.getOrDefault(peer, -1) >= i) {
                    count++;
                }
            }

            // Cek jika sudah mayoritas (N/2 + 1)
            if (count > (peers.size() + 1) / 2) {
                commitIndex = i;
                // Terapkan log yang baru ter-commit
                applyLogToStateMachine();
            } else {
                break; // Karena log harus berurutan (jika log i gagal, log i+1 pasti gagal)
            }
        }
    }
}