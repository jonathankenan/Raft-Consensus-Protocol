package com.labbrother;

import com.labbrother.raft.SnapshotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit Test untuk SnapshotManager (BONUS - Log Compaction)
 * 
 * Cakupan Test:
 * 1. Create snapshot
 * 2. Load snapshot
 * 3. Snapshot threshold checking
 * 4. Old snapshot cleanup
 * 5. Restore state from snapshot
 */
public class SnapshotManagerTest {

    private SnapshotManager snapshotManager;
    private static final String TEST_NODE_ID = "test_node_127.0.0.1_8001";

    @BeforeEach
    public void setUp() {
        snapshotManager = new SnapshotManager(TEST_NODE_ID);
    }

    @AfterEach
    public void tearDown() {
        // Bersihkan test snapshots
        File dir = new File("snapshots/" + TEST_NODE_ID);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            dir.delete();
        }
        
        // Bersihkan parent directory jika kosong
        File parentDir = new File("snapshots");
        if (parentDir.exists() && parentDir.list().length == 0) {
            parentDir.delete();
        }
    }

    @Test
    public void testCreateSnapshot() {
        System.out.println("\n=== Test: Create Snapshot ===");
        
        Map<String, String> data = new HashMap<>();
        data.put("key1", "value1");
        data.put("key2", "value2");
        data.put("key3", "value3");
        
        boolean success = snapshotManager.createSnapshot(data, 10, 1);
        
        assertTrue(success, "Snapshot creation harus berhasil");
        assertEquals(10, snapshotManager.getLastIncludedIndex());
        assertEquals(1, snapshotManager.getLastIncludedTerm());
        
        System.out.println("Snapshot berhasil dibuat pada indeks 10");
    }

    @Test
    public void testLoadSnapshot() {
        System.out.println("\n=== Test: Load Snapshot ===");
        
        // Create snapshot first
        Map<String, String> originalData = new HashMap<>();
        originalData.put("key1", "value1");
        originalData.put("key2", "value2");
        
        snapshotManager.createSnapshot(originalData, 5, 1);
        
        // Load snapshot
        SnapshotManager.Snapshot loaded = snapshotManager.loadLatestSnapshot();
        
        assertNotNull(loaded, "Harus dapat memuat snapshot");
        assertEquals(5, loaded.lastIncludedIndex);
        assertEquals(1, loaded.lastIncludedTerm);
        assertEquals(2, loaded.data.size());
        assertEquals("value1", loaded.data.get("key1"));
        assertEquals("value2", loaded.data.get("key2"));
        
        System.out.println("Snapshot berhasil dimuat");
    }

    @Test
    public void testLoadLatestSnapshot() {
        System.out.println("\n=== Test: Load Latest Snapshot (Multiple Snapshots) ===");
        
        // Create multiple snapshots
        Map<String, String> data1 = new HashMap<>();
        data1.put("key1", "old_value");
        snapshotManager.createSnapshot(data1, 5, 1);
        
        Map<String, String> data2 = new HashMap<>();
        data2.put("key1", "newer_value");
        snapshotManager.createSnapshot(data2, 10, 1);
        
        Map<String, String> data3 = new HashMap<>();
        data3.put("key1", "latest_value");
        snapshotManager.createSnapshot(data3, 15, 2);
        
        // Load should return latest (index 15)
        SnapshotManager.Snapshot loaded = snapshotManager.loadLatestSnapshot();
        
        assertNotNull(loaded);
        assertEquals(15, loaded.lastIncludedIndex);
        assertEquals(2, loaded.lastIncludedTerm);
        assertEquals("latest_value", loaded.data.get("key1"));
        
        System.out.println("Snapshot terbaru berhasil dimuat");
    }

    @Test
    public void testNoSnapshotExists() {
        System.out.println("\n=== Test: Load When No Snapshot Exists ===");
        
        SnapshotManager.Snapshot loaded = snapshotManager.loadLatestSnapshot();
        
        assertNull(loaded, "Harus mengembalikan null jika tidak ada snapshot yang tersedia");
        
        System.out.println("Snapshot yang hilang ditangani");
    }

    @Test
    public void testShouldCreateSnapshotThreshold() {
        System.out.println("\n=== Test: Snapshot Threshold Check ===");
        
        int threshold = 100;
        
        // Below threshold - should not create
        assertFalse(snapshotManager.shouldCreateSnapshot(50, threshold),
                   "Jangan mengambil snapshot saat di bawah threshold");
        
        // At threshold - should create
        assertTrue(snapshotManager.shouldCreateSnapshot(100, threshold),
                  "Harus mengambil snapshot saat mencapai threshold");
        
        // Above threshold - should create
        assertTrue(snapshotManager.shouldCreateSnapshot(150, threshold),
                  "Harus mengambil snapshot saat melebihi threshold");
        
        System.out.println("Pemeriksaan threshold berfungsi dengan benar");
    }

    @Test
    public void testSnapshotAfterPreviousSnapshot() {
        System.out.println("\n=== Test: Threshold After Previous Snapshot ===");
        
        Map<String, String> data = new HashMap<>();
        data.put("key1", "value1");
        
        // Create snapshot at index 50
        snapshotManager.createSnapshot(data, 50, 1);
        
        int threshold = 100;
        
        // Log size 100, but not much new since last snapshot (50)
        // Should still create because 100 > 50 + 1
        assertTrue(snapshotManager.shouldCreateSnapshot(100, threshold));
        
        // But if log size is 51 (only 1 new entry), should not create
        assertFalse(snapshotManager.shouldCreateSnapshot(51, threshold));
        
        System.out.println("Threshold mempertimbangkan indeks snapshot sebelumnya");
    }

    @Test
    public void testOldSnapshotCleanup() throws InterruptedException {
        System.out.println("\n=== Test: Old Snapshot Cleanup ===");
        
        Map<String, String> data = new HashMap<>();
        
        // Create 3 snapshots
        snapshotManager.createSnapshot(data, 10, 1);
        Thread.sleep(100); // Ensure different timestamps
        
        snapshotManager.createSnapshot(data, 20, 1);
        Thread.sleep(100);
        
        snapshotManager.createSnapshot(data, 30, 1);
        
        // Check files
        File dir = new File("snapshots/" + TEST_NODE_ID);
        File[] files = dir.listFiles((d, name) -> name.startsWith("snapshot-"));
        
        // Should keep latest + 1 backup = 2 files (10 deleted, 20 and 30 kept)
        assertNotNull(files);
        assertTrue(files.length <= 2, "Snapshot lama harus dibersihkan");
        
        System.out.println("Snapshots lama dibersihkan (kept " + files.length + " files)");
    }

    @Test
    public void testRestoreComplexState() {
        System.out.println("\n=== Test: Restore Complex State ===");
        
        // Create complex state
        Map<String, String> complexData = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            complexData.put("key" + i, "value" + i);
        }
        
        snapshotManager.createSnapshot(complexData, 100, 5);
        
        // Load and verify
        SnapshotManager.Snapshot loaded = snapshotManager.loadLatestSnapshot();
        
        assertNotNull(loaded);
        assertEquals(100, loaded.lastIncludedIndex);
        assertEquals(5, loaded.lastIncludedTerm);
        assertEquals(50, loaded.data.size());
        
        // Verify all data
        for (int i = 0; i < 50; i++) {
            assertEquals("value" + i, loaded.data.get("key" + i),
                        "Data harus dipulihkan dengan benar");
        }
        
        System.out.println("Complex state telah dipulihkan (50 entries)");
    }

    @Test
    public void testSnapshotWithEmptyState() {
        System.out.println("\n=== Test: Snapshot with Empty State ===");
        
        Map<String, String> emptyData = new HashMap<>();
        
        boolean success = snapshotManager.createSnapshot(emptyData, 0, 0);
        
        assertTrue(success, "Harus dapat mengambil snapshot dari keadaan kosong.");
        
        SnapshotManager.Snapshot loaded = snapshotManager.loadLatestSnapshot();
        assertNotNull(loaded);
        assertEquals(0, loaded.data.size());
        
        System.out.println("Snapshot keadaan kosong ditangani dengan benar");
    }
}