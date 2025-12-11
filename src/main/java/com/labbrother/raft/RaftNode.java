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
    private Map<Address, Integer> nextIndex;  // Index log berikutnya yang akan dikirim ke follower
    private Map<Address, Integer> matchIndex; // Index log tertinggi yang diketahui ter-replikasi di follower
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
        
        // Inisialisasi State Leader yang volatil
        this.nextIndex = new ConcurrentHashMap<>();
        this.matchIndex = new ConcurrentHashMap<>();
        
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

        // Dapatkan Log Terakhir Node Lokal
        int myLastLogIndex = log.isEmpty() ? -1 : log.size() - 1;
        int myLastLogTerm = log.isEmpty() ? 0 : log.get(myLastLogIndex).getTerm();

        // 3. Kirim RequestVote ke SEMUA Peers secara Paralel
        for (Address peer : peers) {
            scheduler.submit(() -> {
                try {
                    RpcService peerProxy = getPeerProxy(peer);
                    
                    // Payload diisi dengan Log Details untuk validasi penerima
                    Map<String, Integer> payload = new HashMap<>();
                    payload.put("lastLogIndex", myLastLogIndex);
                    payload.put("lastLogTerm", myLastLogTerm);
                    
                    Message request = new Message("REQUEST_VOTE", myAddress, currentTerm, payload);
                    
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

        // [TAMBAHAN]: Reset volatile state Leader
        nextIndex.clear();
        matchIndex.clear();
        int lastLogIndex = log.size(); // Index log selanjutnya (kosong)
        
        for (Address peer : peers) {
            nextIndex.put(peer, lastLogIndex);
            matchIndex.put(peer, -1);
        }

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
                // Panggil replicateLogToPeer dengan acksReceived = null (karena ini Heartbeat)
                replicateLogToPeer(peer, null);
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
        
        // --- Ambil Log Details dari Kandidat ---
        Map<String, Integer> candidateLog = (Map<String, Integer>) request.getPayload();
        int candidateLastLogIndex = candidateLog.getOrDefault("lastLogIndex", -1);
        int candidateLastLogTerm = candidateLog.getOrDefault("lastLogTerm", 0);


        if (candidateTerm == currentTerm && (votedFor == null || votedFor.equals(candidateAddress))) {
            if (isLogUpToDate(candidateLastLogIndex, candidateLastLogTerm)) { 
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
        addPeer(request.getSender()); //

        int leaderTerm = request.getTerm();
        
        // 1. Validasi Leader (Term Check)
        if (leaderTerm < currentTerm) {
            // Leader yang mengirim AppendEntries memiliki Term yang lebih kecil. Tolak.
            System.out.println(myAddress + " [AE] Tolak dari Leader Term " + leaderTerm + ". Saya: Term " + currentTerm);
            return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
        }
        
        // 2. Akui Leader dan Reset Timer
        // Jika Term Leader lebih besar, atau Term sama tapi saya bukan Follower, kita mundur.
        if (leaderTerm > currentTerm || state != NodeState.FOLLOWER) {
            System.out.println(myAddress + " [AE] Mengakui Leader " + request.getSender() + ", Term baru: " + leaderTerm);
            currentTerm = leaderTerm;
            state = NodeState.FOLLOWER; // Mundur menjadi Follower
            votedFor = null;
        }
        // Pastikan timer di-reset agar tidak terjadi Election baru
        resetElectionTimer(); 
        leaderAddress = request.getSender(); // Catat siapa Leader yang baru/aktif

        // Heartbeat murni (Payload logEntries = null)
        if (request.getPayload() == null) {
            // Heartbeat berhasil, lakukan pengecekan commitIndex di bawah.
            System.out.println(myAddress + " [AE] Heartbeat diterima dari " + leaderAddress);
        } else {
            // --- Log Replication Request ---

            // Payload berisi Map untuk detail log dan List<LogEntry> untuk entri baru
            Map<String, Object> payloadMap = (Map<String, Object>) request.getPayload();
            
            // Ambil PrevLog Index dan Term dari request
            int prevLogIndex = (int) payloadMap.getOrDefault("prevLogIndex", -1);
            int prevLogTerm = (int) payloadMap.getOrDefault("prevLogTerm", 0);
            
            // Ambil Entri Log yang akan ditambahkan
            List<LogEntry> newEntries = (List<LogEntry>) payloadMap.get("entries");
            
            System.out.println(myAddress + " [AE] Log diterima. Prev Index: " + prevLogIndex + 
                               " | Entries baru: " + (newEntries == null ? 0 : newEntries.size()));


            // 3. [ANGGOTA 2]: Implementasi Log Matching Property (Rule 2)
            // Cek apakah log di indeks prevLogIndex lokal sama dengan Term Leader
            if (prevLogIndex >= 0) {
                if (prevLogIndex >= log.size()) {
                    // 3A. Log Lokal Terlalu Pendek
                    System.out.println(myAddress + " [AE] GAGAL: Log lokal terlalu pendek di Index " + prevLogIndex);
                    // Minta Leader mengirim log yang lebih mundur
                    return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
                }
                
                LogEntry entryAtPrevIndex = log.get(prevLogIndex);
                if (entryAtPrevIndex.getTerm() != prevLogTerm) {
                    // 3B. Log Term Konflik
                    System.out.println(myAddress + " [AE] GAGAL: Konflik Term di Index " + prevLogIndex + 
                                       ". Lokal Term: " + entryAtPrevIndex.getTerm() + 
                                       " | Leader Term: " + prevLogTerm);
                    // Hapus semua log yang konflik dari Index ini ke atas
                    log.subList(prevLogIndex, log.size()).clear();
                    // Minta Leader mengirim ulang
                    return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
                }
            }
            
            // 4. [ANGGOTA 2]: Append New Entries (Rule 3)
            if (newEntries != null && !newEntries.isEmpty()) {
                // Tambahkan entri baru ke log lokal
                for (int i = 0; i < newEntries.size(); i++) {
                    LogEntry newEntry = newEntries.get(i);
                    int index = prevLogIndex + 1 + i;
                    
                    if (index < log.size()) {
                        // Jika sudah ada entri di indeks ini
                        LogEntry existingEntry = log.get(index);
                        if (existingEntry.getTerm() != newEntry.getTerm()) {
                            // Konflik Term: Hapus entri yang ada dan semua setelahnya
                            log.subList(index, log.size()).clear();
                            log.add(newEntry);
                        }
                    } else {
                        // Tidak ada konflik, langsung tambahkan
                        log.add(newEntry);
                    }
                }
            }
            
            // 5. Update Commit Index (Rule 4)
            int leaderCommit = (int) payloadMap.getOrDefault("leaderCommit", -1);
            if (leaderCommit > commitIndex) {
                // Commit index tidak boleh melebihi indeks log terakhir yang baru ditambahkan
                commitIndex = Math.min(leaderCommit, log.size() - 1);
                // Terapkan log yang sudah di-commit ke state machine
                applyLogToStateMachine(); // Nanti akan kita implementasikan
            }

            // 
        }

        return new Message("APPEND_RESPONSE", myAddress, currentTerm, true);
    }

    // ... (State volatile: commitIndex, lastApplied, dll)
    
    // ... (Konstruktor dan start())

    // --- Metode Internal Raft (Lengkapi Implementasi) ---

    // Metode untuk menerapkan Log yang sudah di-commit ke State Machine
    private synchronized void applyLogToStateMachine() {
        // Hanya terapkan entri yang sudah di-commit (commitIndex) dan belum diterapkan (lastApplied)
        while (commitIndex > lastApplied) {
            lastApplied++;
            
            // Ambil LogEntry pada indeks yang akan diterapkan
            LogEntry entry = log.get(lastApplied);
            
            // Lakukan parsing dan eksekusi command pada StateMachine (KVStore)
            String result = parseAndExecuteCommand(entry.getCommand());
            
            // Log aksi yang diterapkan
            System.out.println(myAddress + " [APPLY] Log Index " + lastApplied + " (Term " + entry.getTerm() + 
                               ") diterapkan. Command: " + entry.getCommand() + ". Hasil: " + result);
            
            // Catatan: Jika ini adalah command GET/PING, hasilnya (result) diabaikan 
            // karena log hanya untuk operasi yang mengubah state (SET, APPEND, DEL).
        }
    }


    /**
     * Helper untuk mengurai (parse) string perintah dari Log Raft 
     * dan mengeksekusinya pada StateMachine (KVStore).
     * @param commandString Contoh: "set key1 val1" atau "ping"
     * @return Hasil eksekusi dari StateMachine (String)
     */
    private String parseAndExecuteCommand(String commandString) {
        // Memecah string command menjadi maksimal 3 bagian: COMMAND KEY VALUE
        String[] parts = commandString.trim().split("\\s+", 3);
        
        if (parts.length == 0) return "ERROR: Empty command";

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
                    // get tidak mengubah state, tapi jika ada di log, tetap di-apply (meskipun ini tidak efisien)
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

    @Override
    public Message execute(Message request) {
        // Pastikan akses ke state RaftNode synchronized
        synchronized(this) {
            // 1. Cek State: Jika bukan LEADER, return REDIRECT
            if (state != NodeState.LEADER) {
                System.out.println(myAddress + " [Execute] Tolak dari " + request.getSender() + ". Redirect ke " + leaderAddress);
                return createRedirectResponse();
            }

            // [ANGGOTA 2 - SELESAI]: Handle Client Request
            
            String commandString = (String) request.getPayload();
            String command = commandString.trim().split("\\s+", 2)[0].toLowerCase();
            
            // Jika PING, langsung eksekusi tanpa log replication (sesuai spesifikasi tugas)
            if (command.equals("ping")) {
                String result = parseAndExecuteCommand(commandString);
                Message response = new Message("EXECUTE_RESPONSE", myAddress, currentTerm, result);
                response.setStatus("OK"); 
                return response;
            }
            
            // 2. Buat LogEntry baru dan tambahkan secara lokal
            LogEntry newEntry = new LogEntry(currentTerm, commandString);
            log.add(newEntry);
            System.out.println(myAddress + " [Execute] Log baru ditambahkan secara lokal: " + newEntry.toString());
            
            // Update matchIndex dan nextIndex Leader untuk dirinya sendiri
            matchIndex.put(myAddress, log.size() - 1);
            nextIndex.put(myAddress, log.size());

            // 3. Inisiasi Replikasi Log ke Followers
            
            // Gunakan AtomicInteger untuk menghitung ACK sukses (termasuk local append)
            AtomicInteger acksReceived = new AtomicInteger(1); 
            int requiredAcks = (peers.size() + 1) / 2 + 1; // Mayoritas

            if (peers.isEmpty() || requiredAcks <= 1) {
                // Kluster hanya 1 node (diri sendiri). Langsung commit.
            } else {
                // Kirim log ke semua peers secara paralel
                for (Address peer : peers) {
                    // Jalankan di scheduler karena replicateLogToPeer akan melakukan RPC blocking
                    scheduler.submit(() -> replicateLogToPeer(peer, acksReceived));
                }
            }
            
            // 4. Blok thread client sampai replikasi selesai (atau timeout)
            long startTime = System.currentTimeMillis();
            long timeout = 5000; // 5 detik timeout (Contoh)
            
            boolean majorityReached = false;
            while (System.currentTimeMillis() - startTime < timeout) {
                if (acksReceived.get() >= requiredAcks) {
                    majorityReached = true;
                    break;
                }
                
                try {
                    // Menunggu notifikasi dari replicateLogToPeer saat ACK diterima
                    this.wait(100); 
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // 5. Apply Log dan Respon
            if (majorityReached) {
                updateCommitIndex();
                String finalResult = parseAndExecuteCommand(commandString);
                Message response = new Message("EXECUTE_RESPONSE", myAddress, currentTerm, finalResult);
                response.setStatus("OK"); // [TAMBAHAN]
                return response;
            } else {
                Message response = new Message("FAIL", myAddress, currentTerm, "Log replication failed: Timeout waiting for majority ACK.");
                response.setStatus("FAIL"); // [TAMBAHAN]
                return response;
            }
        }
    }

    /**
     * Helper untuk mengirim Log/Heartbeat ke peer tertentu.
     * @param peer Alamat follower
     * @param acksReceived AtomicInteger untuk menghitung ACK sukses dari client request. Null jika dipanggil dari Heartbeat.
     */
    private void replicateLogToPeer(Address peer, AtomicInteger acksReceived) {
        try {
            RpcService peerProxy = getPeerProxy(peer);
            
            synchronized(this) {
                // [FIX BUG]: Inisialisasi nextIndex yang benar.
                // Jika nextIndex belum ada, mulai dari log terakhir (bukan log.size() yang baru saja ditambah)
                // Kita mau kirim log yang baru saja ditambah, jadi defaultnya harus index log itu.
                int lastLogIndex = log.size() - 1;
                int nextIdx = nextIndex.getOrDefault(peer, lastLogIndex); 
                
                // Pastikan nextIdx tidak melebihi size (jika ada race condition)
                if (nextIdx > log.size()) nextIdx = log.size();

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
                
                // Debug Print
                // System.out.println("DEBUG: Mengirim ke " + peer + " | prevIdx: " + prevLogIndex + " | entries: " + entriesToSend.size());

                Message request = new Message("APPEND_ENTRIES", myAddress, currentTerm, payload);
                Message response = peerProxy.appendEntries(request);
                
                if (response != null) {
                    if (response.getTerm() > currentTerm) {
                        stepDown(response.getTerm());
                        return;
                    }
                    
                    if ((boolean) response.getPayload()) {
                        // SUKSES
                        int newMatchIndex = prevLogIndex + entriesToSend.size();
                        matchIndex.put(peer, newMatchIndex);
                        nextIndex.put(peer, newMatchIndex + 1);
                        
                        // [UPDATE]: Print hanya jika bukan heartbeat murni
                        if (!entriesToSend.isEmpty()) {
                            System.out.println(myAddress + " [Rep] Sukses ke " + peer + ". Match Index: " + newMatchIndex);
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
                            // System.out.println(myAddress + " [Rep] Gagal ke " + peer + ". Mundur Next Index ke: " + (newNextIndex - 1));
                            
                            // Retry rekursif
                            replicateLogToPeer(peer, acksReceived);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // [PENTING]: Tampilkan error koneksi!
            System.err.println(myAddress + " [Error Rep] Gagal ke " + peer + ": " + e.getMessage());
            // e.printStackTrace(); // Uncomment jika butuh detail lengkap
        }
    }

// ... (Metode-metode lain)

    private boolean isLogUpToDate(int candidateLastLogIndex, int candidateLastLogTerm) {
        // [ANGGOTA 2 - SELESAI]: Implementasi Log Up-to-Date Rule
        
        // 1. Dapatkan Log Terakhir Node Lokal (Saya)
        int myLastLogIndex = log.isEmpty() ? -1 : log.size() - 1;
        // Term log terakhir 0 jika log kosong
        int myLastLogTerm = log.isEmpty() ? 0 : log.get(myLastLogIndex).getTerm(); 
        
        System.out.println(myAddress + " [Log Check] Kandidat: Term " + candidateLastLogTerm + " Index " + candidateLastLogIndex + 
                           " | Saya: Term " + myLastLogTerm + " Index " + myLastLogIndex);

        // Logika Raft Rule:
        // Kandidat diizinkan menang jika lognya lebih up-to-date atau sama up-to-date-nya.
        // Log dibandingkan dulu berdasarkan Term, baru Index.

        // a. Jika Last Log Term Kandidat lebih besar, maka Kandidat lebih up-to-date.
        if (candidateLastLogTerm > myLastLogTerm) {
            return true;
        }
        
        // b. Jika Last Log Term sama, cek panjang log. Log yang lebih panjang lebih up-to-date.
        if (candidateLastLogTerm == myLastLogTerm && candidateLastLogIndex >= myLastLogIndex) {
            return true;
        }

        // Kandidat memiliki Term atau Index yang lebih kecil.
        return false;
    }

    // ... (Metode-metode lain)

    // ==============================================================================
    // [AREA ANGGOTA 3] MEMBERSHIP & UTILS (PERLU DIKERJAKAN)
    // ==============================================================================

    @Override
    public Message requestLog(Message request) {
        // Gunakan synchronized untuk akses state yang aman
        synchronized(this) {
            // 1. Cek Authority: Hanya Leader yang boleh mengembalikan Log
            if (state != NodeState.LEADER) {
                // Jika saya bukan Leader, beritahu Client alamat Leader yang saya tahu
                return createRedirectResponse();
            }
            
            // 2. Return Log
            // Kita kembalikan salinan (copy) dari log agar list asli aman dari modifikasi luar
            List<LogEntry> logCopy = new ArrayList<>(log);
            
            System.out.println(myAddress + " [LogRequest] Mengirimkan " + log.size() + " entri log ke Client.");
            
            return new Message("LOG_RESPONSE", myAddress, currentTerm, logCopy);
        }
    }

    public synchronized void addPeer(Address newPeer) {
        // [ANGGOTA 3 - SELESAI]: Auto Discovery Logic
        if (!newPeer.equals(myAddress) && !peers.contains(newPeer)) {
            peers.add(newPeer);
            System.out.println("[Membership] Node baru ditemukan & ditambahkan: " + newPeer);
        }
    }

    public synchronized void removePeer(Address peer) {
        // [ANGGOTA 3 - SELESAI]: Dynamic Membership Remove
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
            
            // Opsional: Cek ulang komitmen log karena quorum mungkin berubah (jumlah peer berkurang)
            if (state == NodeState.LEADER) {
                updateCommitIndex();
            }
        }
    }
    
    // --- Helper Anggota 3: Redirection ---
    private Message createRedirectResponse() {
        // [ANGGOTA 3 - SELESAI]: Redirect Client
        return new Message("REDIRECT", myAddress, currentTerm, leaderAddress);
    }
    
    // --- Helper Anggota 2: Update Commit Index ---
    private synchronized void updateCommitIndex() {
        // Logika untuk mengupdate commitIndex
        
        int N = log.size();
        for (int i = commitIndex + 1; i < N; i++) {
            LogEntry entry = log.get(i);
            
            // Raft Rule: Hanya bisa meng-commit log dari Term saat ini
            if (entry.getTerm() != currentTerm) continue;
            
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