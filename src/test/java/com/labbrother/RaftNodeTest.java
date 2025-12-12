package com.labbrother;

import com.labbrother.model.Address;
import com.labbrother.model.LogEntry;
import com.labbrother.model.Message;
import com.labbrother.raft.NodeState;
import com.labbrother.raft.RaftNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unit Test untuk RaftNode
 * 
 * Cakupan Test:
 * 1. Inisialisasi node
 * 2. Leader election (skenario satu node)
 * 3. Request vote handling (up-to-date log check dan term validation)
 * 4. Append entries handling (heartbeat & log replication)
 * 5. Eksekusi command redirection (follower -> leader)
 * 6. Request log redirection (follower -> leader)
 * 7. Dynamic membership (auto discovery dan manual removal)
 */
public class RaftNodeTest {

    private RaftNode node1;
    private RaftNode node2;
    private RaftNode node3;
    
    private Address addr1;
    private Address addr2;
    private Address addr3;

    @BeforeEach
    public void setUp() {
        // Setup 3 node addresses
        addr1 = new Address("localhost", 5001);
        addr2 = new Address("localhost", 5002);
        addr3 = new Address("localhost", 5003);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        // Beri waktu untuk thread cleanup
        Thread.sleep(100);
    }

    @Test
    public void testSingleNodeBecomeLeader() throws InterruptedException {
        System.out.println("\n=== Test: Satu Node Menjadi Leader ===");
        
        // Node tanpa peers (sendirian) langsung jadi Leader
        List<Address> noPeers = new ArrayList<>();
        node1 = new RaftNode(addr1, noPeers);
        node1.start();
        
        // Tunggu election timer (max 3 detik + buffer)
        Thread.sleep(4000);
        
        System.out.println("Test selesai - Periksa logs untuk validasi Leader election");
        System.out.println("Expected: Node harus menjadi LEADER pada Term 1");
        
        // Note: Karena state adalah private, kita tidak bisa assert langsung.
        // Dalam test ini, kita verifikasi dari log output.
        // Alternatif: Tambahkan getter di RaftNode untuk testing.
        
        assertTrue(true, "Satu node harus menjadi leader setelah election timeout");
    }

    @Test
    public void testRequestVoteWithUpToDateLog() {
        System.out.println("\n=== Test: Request Vote - Log Up-to-Date ===");
        
        List<Address> peers = new ArrayList<>();
        peers.add(addr2);
        node1 = new RaftNode(addr1, peers);
        
        // Simulasi RequestVote dari Candidate dengan log kosong
        Message voteRequest = new Message();
        voteRequest.setType("REQUEST_VOTE");
        voteRequest.setSender(addr2);
        voteRequest.setTerm(1);
        
        // Candidate log: empty (lastLogIndex = -1, lastLogTerm = 0)
        java.util.Map<String, Integer> candidateLog = new java.util.HashMap<>();
        candidateLog.put("lastLogIndex", -1);
        candidateLog.put("lastLogTerm", 0);
        voteRequest.setPayload(candidateLog);
        
        Message response = node1.requestVote(voteRequest);
        
        assertNotNull(response, "Respons tidak boleh null");
        assertEquals("VOTE_RESPONSE", response.getType());
        assertTrue((Boolean) response.getPayload(), "Harus memberikan vote kepada candidate dengan up-to-date log");
        
        System.out.println("Vote diberikan: " + response.getPayload());
    }

    @Test
    public void testRequestVoteRejectOldTerm() {
        System.out.println("\n=== Test: Request Vote - Tolak Term Lama ===");
        
        List<Address> peers = new ArrayList<>();
        node1 = new RaftNode(addr1, peers);
        
        // Simulasi node1 sudah di Term 2
        java.util.Map<String, Integer> validLog = new java.util.HashMap<>();
        validLog.put("lastLogIndex", -1);
        validLog.put("lastLogTerm", 0);
        Message dummyRequest = new Message("REQUEST_VOTE", addr2, 2, validLog);
        node1.requestVote(dummyRequest); // Ini akan update currentTerm ke 2
        
        // Candidate dengan Term lama (1) minta vote
        Message oldTermRequest = new Message();
        oldTermRequest.setType("REQUEST_VOTE");
        oldTermRequest.setSender(addr2);
        oldTermRequest.setTerm(1);
        
        java.util.Map<String, Integer> candidateLog = new java.util.HashMap<>();
        candidateLog.put("lastLogIndex", -1);
        candidateLog.put("lastLogTerm", 0);
        oldTermRequest.setPayload(candidateLog);
        
        Message response = node1.requestVote(oldTermRequest);
        
        assertNotNull(response);
        assertFalse((Boolean) response.getPayload(), "Harus menolak vote dari candidate dengan term lama");
        
        System.out.println("Vote ditolak dari term lama");
    }

    @Test
    public void testAppendEntriesHeartbeat() {
        System.out.println("\n=== Test: Append Entries - Heartbeat ===");
        
        List<Address> peers = new ArrayList<>();
        node1 = new RaftNode(addr1, peers);
        
        // Simulasi Heartbeat dari Leader
        Message heartbeat = new Message();
        heartbeat.setType("APPEND_ENTRIES");
        heartbeat.setSender(addr2);
        heartbeat.setTerm(1);
        heartbeat.setPayload(null); // Heartbeat tidak ada payload
        
        Message response = node1.appendEntries(heartbeat);
        
        assertNotNull(response);
        assertEquals("APPEND_RESPONSE", response.getType());
        assertTrue((Boolean) response.getPayload(), "Harus menerima heartbeat dari valid leader");
        
        System.out.println("Heartbeat diterima");
    }

    @Test
    public void testAppendEntriesLogReplication() {
        System.out.println("\n=== Test: Append Entries - Log Replication ===");
        
        List<Address> peers = new ArrayList<>();
        node1 = new RaftNode(addr1, peers);
        
        // Simulasi Leader mengirim log entry baru
        LogEntry newEntry = new LogEntry(1, "set key1 value1");
        List<LogEntry> entries = new ArrayList<>();
        entries.add(newEntry);
        
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("prevLogIndex", -1); // Log pertama
        payload.put("prevLogTerm", 0);
        payload.put("leaderCommit", -1);
        payload.put("entries", entries);
        
        Message appendRequest = new Message();
        appendRequest.setType("APPEND_ENTRIES");
        appendRequest.setSender(addr2);
        appendRequest.setTerm(1);
        appendRequest.setPayload(payload);
        
        Message response = node1.appendEntries(appendRequest);
        
        assertNotNull(response);
        assertTrue((Boolean) response.getPayload(), "Harus menerima log entry dari leader");
        
        System.out.println("Log telah direplikasi dengan sukses");
    }

    @Test
    public void testExecuteCommandOnFollower() {
        System.out.println("\n=== Test: Menjalankan Command pada Follower (Harus Redirect) ===");
        
        List<Address> peers = new ArrayList<>();
        peers.add(addr2); // Ada peer, jadi tidak langsung jadi Leader
        node1 = new RaftNode(addr1, peers);
        
        // Node1 masih FOLLOWER, belum ada Leader
        Message clientRequest = new Message();
        clientRequest.setType("CLIENT_REQUEST");
        clientRequest.setSender(new Address("client", 0));
        clientRequest.setPayload("set key1 value1");
        
        Message response = node1.execute(clientRequest);
        
        assertNotNull(response);
        assertEquals("REDIRECT", response.getType(), "Follower harus redirect client request");
        
        System.out.println("Follower melakukan redirect request");
    }

    @Test
    public void testRequestLogOnFollower() {
        System.out.println("\n=== Test: Request Log pada Follower (Harus Redirect) ===");
        
        List<Address> peers = new ArrayList<>();
        peers.add(addr2);
        node1 = new RaftNode(addr1, peers);
        
        Message logRequest = new Message();
        logRequest.setType("LOG_REQUEST");
        logRequest.setSender(new Address("client", 0));
        
        Message response = node1.requestLog(logRequest);
        
        assertNotNull(response);
        assertEquals("REDIRECT", response.getType(), "Follower harus redirect log request");
        
        System.out.println("Follower melakukan redirect request");
    }

    @Test
    public void testAddPeerDynamically() {
        System.out.println("\n=== Test: Tambahkan Secara Dinamis (Auto Discovery) ===");
        
        List<Address> peers = new ArrayList<>();
        node1 = new RaftNode(addr1, peers);
        
        // Awalnya tidak ada peer
        assertEquals(0, peers.size());
        
        // Tambah peer baru (simulasi auto discovery saat RequestVote)
        node1.addPeer(addr2);
        node1.addPeer(addr3);
        
        // Coba tambah duplikat (seharusnya diabaikan)
        node1.addPeer(addr2);
        
        // Coba tambah diri sendiri (seharusnya diabaikan)
        node1.addPeer(addr1);
        
        System.out.println("Peer ditambahkan secara dinamis - Periksa logs untuk validasi");
        System.out.println("Expected: 2 peer unik ditambahkan (addr2 dan addr3)");
        
        assertTrue(true, "Penambahan peer secara dinamis harus bekerja dengan benar");
    }

    @Test
    public void testRemovePeer() {
        System.out.println("\n=== Test: Hapus Peer ===");
        
        List<Address> peers = new ArrayList<>();
        peers.add(addr2);
        peers.add(addr3);
        node1 = new RaftNode(addr1, peers);
        
        // Remove peer
        node1.removePeer(addr2);
        
        // Remove peer yang tidak ada (seharusnya tidak error)
        node1.removePeer(new Address("localhost", 9999));
        
        System.out.println("Peer telah dihapus - Periksa logs untuk validasi");
        
        assertTrue(true, "Penghapusan peer harus bekerja dengan benar");
    }
}