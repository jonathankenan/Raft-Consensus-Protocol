package com.labbrother.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.labbrother.model.Address;
import com.labbrother.model.LogEntry;
import com.labbrother.model.Message;
import com.labbrother.network.RpcService;

public class RaftNode implements RpcService {

    // --- 1. Identitas Node ---
    private Address myAddress;
    private List<Address> peers; // Daftar alamat teman se-cluster
    private NodeState state;     // Status: FOLLOWER, CANDIDATE, atau LEADER
    
    // --- 2. Persistent State (Data yang harus diingat) ---
    private int currentTerm;
    private Address votedFor;
    private List<LogEntry> log;
    
    // --- 3. Volatile State (Data yang berubah-ubah) ---
    private int commitIndex;
    private int lastApplied;
    private Address leaderAddress; // Siapa Bos saat ini?

    // --- 4. Komponen Pendukung ---
    private StateMachine stateMachine; // Gudang Data (KVStore)
    
    // Timer untuk Heartbeat & Election (PENTING UNTUK TUGAS INI)
    private ScheduledExecutorService scheduler; 

    // --- CONSTRUCTOR ---
    public RaftNode(Address myAddress, List<Address> peers) {
        this.myAddress = myAddress;
        this.peers = peers;
        this.stateMachine = new StateMachine();
        this.log = new ArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        // Inisialisasi awal: Mulai sebagai FOLLOWER, Term 0
        this.state = NodeState.FOLLOWER;
        this.currentTerm = 0;
        this.votedFor = null;
        this.commitIndex = -1;
        this.lastApplied = -1;
        
        System.out.println("Node " + myAddress + " started. State: FOLLOWER");
    }

    // --- IMPLEMENTASI INTERFACE RPCSERVICE (Wajib Ada) ---

    @Override
    public Message requestVote(Message request) {
        // TODO: Nanti kita isi logika Pemilihan Leader di sini
        // Sekarang kita return pesan kosong dulu biar tidak error
        System.out.println("[DEBUG] Menerima RequestVote dari " + request.getSender());
        return new Message("VOTE_RESPONSE", myAddress, currentTerm, false);
    }

    @Override
    public Message appendEntries(Message request) {
        // TODO: Nanti kita isi logika Terima Heartbeat/Log di sini
        System.out.println("[DEBUG] Menerima AppendEntries dari " + request.getSender());
        return new Message("APPEND_RESPONSE", myAddress, currentTerm, false);
    }

    @Override
    public Message execute(Message request) {
        // TODO: Nanti kita isi logika Client Request di sini
        System.out.println("[DEBUG] Menerima Client Command: " + request.getPayload());
        return new Message("EXECUTE_RESPONSE", myAddress, currentTerm, "LOGIC_BELUM_DIBUAT");
    }

    @Override
    public Message requestLog(Message request) {
        // TODO: Mengembalikan seluruh isi log
        return new Message("LOG_RESPONSE", myAddress, currentTerm, log);
    }

    // --- Helper Methods ---
    
    public void start() {
        // Method untuk menyalakan timer (nanti diisi)
        System.out.println("Raft Node berjalan di " + myAddress);
    }
}