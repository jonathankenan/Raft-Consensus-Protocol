package com.labbrother.raft;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * SnapshotManager - Mengelola snapshot untuk log compaction
 * 
 * Features:
 * - Create snapshot dari state machine
 * - Save snapshot ke disk (persistent)
 * - Load snapshot dari disk
 * - Discard old log entries setelah snapshot
 */
public class SnapshotManager {
    
    private String snapshotDir;
    private ObjectMapper objectMapper;
    
    // Snapshot metadata
    private int lastIncludedIndex; // Indeks log terakhir yang termasuk dalam snapshot
    private int lastIncludedTerm; // Term dari lastIncludedIndex
    
    public SnapshotManager(String nodeId) {
        this.snapshotDir = "snapshots/" + nodeId;
        this.objectMapper = new ObjectMapper();
        this.lastIncludedIndex = -1;
        this.lastIncludedTerm = 0;
        
        // Create snapshot directory
        try {
            Files.createDirectories(Paths.get(snapshotDir));
        } catch (IOException e) {
            System.err.println("Gagal membuat direktori snapshot: " + e.getMessage());
        }
    }
    
    /**
     * Create snapshot dari current state machine
     * @param stateMachineData Data dari state machine (KV Store)
     * @param lastLogIndex Index log terakhir yang di-apply
     * @param lastLogTerm Term dari log terakhir
     */
    public synchronized boolean createSnapshot(
            Map<String, String> stateMachineData, 
            int lastLogIndex, 
            int lastLogTerm) {
        
        try {
            Snapshot snapshot = new Snapshot();
            snapshot.lastIncludedIndex = lastLogIndex;
            snapshot.lastIncludedTerm = lastLogTerm;
            snapshot.data = new HashMap<>(stateMachineData);
            
            // Simpan ke disk
            String filename = snapshotDir + "/snapshot-" + lastLogIndex + ".json";
            objectMapper.writerWithDefaultPrettyPrinter()
                       .writeValue(new File(filename), snapshot);
            
            // Update metadata
            this.lastIncludedIndex = lastLogIndex;
            this.lastIncludedTerm = lastLogTerm;
            
            System.out.println("[Snapshot] Membuat snapshot pada indeks " + lastLogIndex + 
                             " (term " + lastLogTerm + ") -> " + filename);
            
            // Hapus snapshot lama
            deleteOldSnapshots(lastLogIndex);
            
            return true;
            
        } catch (IOException e) {
            System.err.println("[Snapshot] Gagal membuat snapshot: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Load latest snapshot dari disk
     */
    public synchronized Snapshot loadLatestSnapshot() {
        try {
            File dir = new File(snapshotDir);
            File[] files = dir.listFiles((d, name) -> name.startsWith("snapshot-") && name.endsWith(".json"));
            
            if (files == null || files.length == 0) {
                System.out.println("[Snapshot] Tidak ditemukan snapshot");
                return null;
            }
            
            // Temukan latest snapshot (highest index)
            File latestFile = null;
            int maxIndex = -1;
            
            for (File file : files) {
                String name = file.getName();
                // Ekstrak index dari "snapshot-123.json"
                String indexStr = name.substring(9, name.length() - 5);
                int index = Integer.parseInt(indexStr);
                
                if (index > maxIndex) {
                    maxIndex = index;
                    latestFile = file;
                }
            }
            
            if (latestFile == null) {
                return null;
            }
            
            // Load snapshot
            Snapshot snapshot = objectMapper.readValue(latestFile, Snapshot.class);
            
            // Update metadata
            this.lastIncludedIndex = snapshot.lastIncludedIndex;
            this.lastIncludedTerm = snapshot.lastIncludedTerm;
            
            System.out.println("[Snapshot] Memuat snapshot dari " + latestFile.getName() + 
                             " (index: " + snapshot.lastIncludedIndex + ", term: " + snapshot.lastIncludedTerm + ")");
            
            return snapshot;
            
        } catch (Exception e) {
            System.err.println("[Snapshot] Gagal memuat snapshot: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Hapus snapshot lama (keep only latest)
     */
    private void deleteOldSnapshots(int currentIndex) {
        try {
            File dir = new File(snapshotDir);
            File[] files = dir.listFiles((d, name) -> name.startsWith("snapshot-") && name.endsWith(".json"));
            
            if (files == null) return;
            
            for (File file : files) {
                String name = file.getName();
                String indexStr = name.substring(9, name.length() - 5);
                int index = Integer.parseInt(indexStr);
                
                // Hapus snapshot yang lebih lama dari saat ini - 1 (simpan cadangan)
                if (index < currentIndex - 1) {
                    if (file.delete()) {
                        System.out.println("[Snapshot] Hapus snapshot lama: " + name);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Snapshot] Gagal menghapus snapshot lama: " + e.getMessage());
        }
    }
    
    /**
     * Check jika perlu create snapshot (threshold reached)
     */
    public boolean shouldCreateSnapshot(int currentLogSize, int threshold) {
        // Snapshot jika log size melebihi threshold DAN ada log baru sejak snapshot terakhir
        return currentLogSize >= threshold && currentLogSize > lastIncludedIndex + 1;
    }
    
    // Getters
    public int getLastIncludedIndex() {
        return lastIncludedIndex;
    }
    
    public int getLastIncludedTerm() {
        return lastIncludedTerm;
    }
    
    /**
     * Snapshot data structure
     */
    public static class Snapshot {
        public int lastIncludedIndex;
        public int lastIncludedTerm;
        public Map<String, String> data;
        
        // Default constructor for Jackson
        public Snapshot() {
            this.data = new HashMap<>();
        }
    }
}