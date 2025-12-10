package com.labbrother.raft;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.googlecode.jsonrpc4j.ProxyUtil;
import com.labbrother.model.Address;
import com.labbrother.model.LogEntry;
import com.labbrother.model.Message;
import com.labbrother.network.RpcService;

public class RaftNode implements RpcService {

    // --- 1. Identitas Node ---
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

    // --- 4. Komponen Pendukung ---
    private StateMachine stateMachine;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> electionTimerTask;
    private ScheduledFuture<?> heartbeatTimerTask;
    private Random random;

    // Konstanta Waktu (dalam milidetik)
    private static final int HEARTBEAT_INTERVAL = 1000; 
    private static final int ELECTION_MIN_DELAY = 3000; 
    private static final int ELECTION_MAX_DELAY = 6000; 

    public RaftNode(Address myAddress, List<Address> peers) {
        this.myAddress = myAddress;
        this.peers = peers;
        this.stateMachine = new StateMachine();
        this.log = new ArrayList<>();
        // Pool size 4 agar cukup untuk timer dan network call paralel
        this.scheduler = Executors.newScheduledThreadPool(4); 
        this.random = new Random();
        
        this.state = NodeState.FOLLOWER;
        this.currentTerm = 0;
        this.votedFor = null;
        this.commitIndex = -1;
        this.lastApplied = -1;
        
        System.out.println("Node " + myAddress + " started. State: FOLLOWER");
    }

    public void start() {
        // [ANGGOTA 1]: Memulai timer pemilihan saat node hidup
        resetElectionTimer();
        System.out.println("Raft Node berjalan di " + myAddress);
    }

    // ==============================================================================
    // [AREA ANGGOTA 1] ELECTION & HEARTBEAT (SUDAH SELESAI / IMPLEMENTED)
    // ==============================================================================

    private synchronized void resetElectionTimer() {
        if (electionTimerTask != null && !electionTimerTask.isCancelled()) {
            electionTimerTask.cancel(false);
        }
        int delay = ELECTION_MIN_DELAY + random.nextInt(ELECTION_MAX_DELAY - ELECTION_MIN_DELAY);
        electionTimerTask = scheduler.schedule(this::runElection, delay, TimeUnit.MILLISECONDS);
    }

    private void runElection() {
        if (state == NodeState.LEADER) return;

        // 1. Persiapan Diri
        synchronized(this) {
            state = NodeState.CANDIDATE;
            currentTerm++;
            votedFor = myAddress;
        }
        
        System.out.println("!!! ELECTION STARTED !!! Term: " + currentTerm + " | Candidate: " + myAddress);

        // 2. Hitung Suara (Atomic biar aman diakses banyak thread)
        AtomicInteger votesReceived = new AtomicInteger(1); // Suara dari diri sendiri
        int requiredVotes = (peers.size() + 1) / 2 + 1; // Mayoritas: (N/2) + 1

        // [FIX SINGLE NODE]: Jika sendirian, langsung menang
        if (votesReceived.get() >= requiredVotes) {
            becomeLeader();
            return;
        }

        // 3. Kirim RequestVote ke SEMUA Peers secara Paralel
        for (Address peer : peers) {
            scheduler.submit(() -> {
                try {
                    RpcService peerProxy = getPeerProxy(peer);
                    
                    // Payload sementara null, nanti diisi Anggota 2
                    Message request = new Message("REQUEST_VOTE", myAddress, currentTerm, null); 
                    
                    System.out.println(" -> Mengirim RequestVote ke " + peer);
                    Message response = peerProxy.requestVote(request);

                    if (response != null) {
                        if ((boolean) response.getPayload()) {
                            int totalVotes = votesReceived.incrementAndGet();
                            System.out.println(" + Dapat suara dari " + peer + " (Total: " + totalVotes + ")");
                            if (totalVotes >= requiredVotes && state == NodeState.CANDIDATE) {
                                becomeLeader();
                            }
                        } else if (response.getTerm() > currentTerm) {
                            stepDown(response.getTerm());
                        }
                    }
                } catch (Exception e) {
                    // Peer mati/timeout, abaikan
                }
            });
        }
        
        // Reset timer, jika kalah election ini, timer akan habis dan mulai election baru
        resetElectionTimer();
    }

    private synchronized void becomeLeader() {
        if (state != NodeState.CANDIDATE) return;

        state = NodeState.LEADER;
        leaderAddress = myAddress;
        System.out.println("$$$ SAYA ADALAH LEADER $$$ Term: " + currentTerm);

        if (electionTimerTask != null) electionTimerTask.cancel(false);
        startHeartbeat();
    }
    
    private synchronized void stepDown(int newTerm) {
        currentTerm = newTerm;
        state = NodeState.FOLLOWER;
        votedFor = null;
        leaderAddress = null;
        resetElectionTimer();
        System.out.println("Mundur menjadi FOLLOWER. Term baru: " + currentTerm);
    }

    private void startHeartbeat() {
        heartbeatTimerTask = scheduler.scheduleAtFixedRate(() -> {
            if (state != NodeState.LEADER) return;
            sendHeartbeat();
        }, 0, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        System.out.println("[Leader] Mengirim Heartbeat...");
        for (Address peer : peers) {
            scheduler.submit(() -> {
                try {
                    // [ANGGOTA 2 TODO]: Nanti ganti ini dengan replicateLogToPeer(peer)
                    RpcService peerProxy = getPeerProxy(peer);
                    Message heartbeat = new Message("APPEND_ENTRIES", myAddress, currentTerm, null);
                    Message response = peerProxy.appendEntries(heartbeat);
                    
                    if (response != null && response.getTerm() > currentTerm) {
                        stepDown(response.getTerm());
                    }
                } catch (Exception e) {
                    // System.err.println("Gagal Heartbeat ke " + peer);
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

    // --- IMPLEMENTASI RPC HANDLER (Receiver) ---

    @Override
    public synchronized Message requestVote(Message request) {
        // [FIX INVISIBLE FOLLOWER]: Kenalan dulu kalau belum kenal
        addPeer(request.getSender());

        int candidateTerm = request.getTerm();
        Address candidateAddress = request.getSender();
        
        if (candidateTerm > currentTerm) {
            stepDown(candidateTerm);
        }
        
        boolean voteGranted = false;
        
        // Cek Term sudah benar, sekarang cek Log (Tugas Anggota 2)
        if (candidateTerm == currentTerm && (votedFor == null || votedFor.equals(candidateAddress))) {
            // [ANGGOTA 2 TODO]: Ganti (0,0) dengan log index/term dari request payload
            if (isLogUpToDate(0, 0)) { 
                voteGranted = true;
                votedFor = candidateAddress;
                resetElectionTimer(); 
                System.out.println("Vote diberikan kepada " + candidateAddress);
            }
        }

        return new Message("VOTE_RESPONSE", myAddress, currentTerm, voteGranted);
    }

    // ==============================================================================
    // [AREA ANGGOTA 2] LOG REPLICATION & EXECUTION (PERLU DIKERJAKAN)
    // ==============================================================================

    @Override
    public synchronized Message appendEntries(Message request) {
        // [FIX INVISIBLE FOLLOWER]: Kenalan dulu kalau belum kenal
        addPeer(request.getSender());

        int leaderTerm = request.getTerm();
        
        // 1. Validasi Leader (Sudah dikerjakan Anggota 1)
        if (leaderTerm < currentTerm) {
            return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
        }
        
        // 2. Akui Leader (Sudah dikerjakan Anggota 1)
        if (leaderTerm > currentTerm || state != NodeState.FOLLOWER) {
            currentTerm = leaderTerm;
            state = NodeState.FOLLOWER;
            leaderAddress = request.getSender();
            votedFor = null;
        }
        resetElectionTimer(); 

        // 3. [ANGGOTA 2 TODO]: Implementasi Log Matching Property
        // -----------------------------------------------------------------
        // Ambil prevLogIndex dan prevLogTerm dari request.payload
        // Cek log lokal:
        // if (log.size() <= prevLogIndex || log.get(prevLogIndex).term != prevLogTerm) {
        //     return false (Log tidak konsisten, Leader harus mundur)
        // }
        
        // 4. [ANGGOTA 2 TODO]: Append New Entries
        // -----------------------------------------------------------------
        // Hapus log mulai dari prevLogIndex + 1 ke atas (jika konflik)
        // Tambahkan entries baru dari request ke log lokal
        // Update commitIndex jika leaderCommit > commitIndex
        
        return new Message("APPEND_RESPONSE", myAddress, currentTerm, true);
    }

    @Override
    public Message execute(Message request) {
        // [ANGGOTA 2 TODO]: Handle Client Request
        // -----------------------------------------------------------------
        // 1. Cek State: Jika bukan LEADER, return REDIRECT (Panggil helper Anggota 3)
        // 2. Jika LEADER:
        //    - Buat LogEntry baru dari payload request
        //    - log.add(newEntry)
        //    - Tunggu sampai entry ter-replikasi ke mayoritas (Blocking atau Async Response)
        //    - Apply ke stateMachine.execute(command)
        //    - Return hasil eksekusi ke Client
        
        return new Message("EXECUTE_RESPONSE", myAddress, currentTerm, "LOGIC_BELUM_DIBUAT");
    }

    private void replicateLogToPeer(Address peer) {
        // [ANGGOTA 2 TODO]: Helper untuk mengirim Log
        // -----------------------------------------------------------------
        // Ambil nextIndex untuk peer tersebut
        // Buat List<LogEntry> entries berisi log dari nextIndex sampai akhir
        // Kirim RPC appendEntries dengan entries tersebut
        // Jika sukses -> update matchIndex & nextIndex peer
        // Jika gagal -> kurangi nextIndex, coba lagi (retrying)
    }

    private boolean isLogUpToDate(int candidateLastLogIndex, int candidateLastLogTerm) {
        // [ANGGOTA 2 TODO]: Validasi Vote
        // -----------------------------------------------------------------
        // Ambil log terakhir kita: myLastLogIndex & myLastLogTerm
        // Return true jika:
        // (candidateLastLogTerm > myLastLogTerm) ATAU
        // (candidateLastLogTerm == myLastLogTerm && candidateLastLogIndex >= myLastLogIndex)
        
        return true; // MOCK: Sementara return true agar Election Anggota 1 jalan
    }

    // ==============================================================================
    // [AREA ANGGOTA 3] MEMBERSHIP & UTILS (PERLU DIKERJAKAN)
    // ==============================================================================

    @Override
    public Message requestLog(Message request) {
        // [ANGGOTA 3 TODO]: Return Log
        // -----------------------------------------------------------------
        // Pastikan format kembalian sesuai spek (List log)
        return new Message("LOG_RESPONSE", myAddress, currentTerm, log);
    }

    public synchronized void addPeer(Address newPeer) {
        // [ANGGOTA 3 - SELESAI]: Auto Discovery Logic
        if (!newPeer.equals(myAddress) && !peers.contains(newPeer)) {
            peers.add(newPeer);
            System.out.println("[Membership] Node baru ditemukan & ditambahkan: " + newPeer);
        }
    }

    public synchronized void removePeer(Address peer) {
        // [ANGGOTA 3 TODO]: Dynamic Membership
        // -----------------------------------------------------------------
        // Hapus peer dari list `peers`
    }
    
    private Message createRedirectResponse() {
        // [ANGGOTA 3 TODO]: Redirect Client
        // -----------------------------------------------------------------
        // Kembalikan pesan dengan status "REDIRECT"
        // Payload berisi String/Object alamat `leaderAddress`
        return new Message("REDIRECT", myAddress, currentTerm, leaderAddress);
    }
}