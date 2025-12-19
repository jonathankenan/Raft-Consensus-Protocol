package com.labbrother.raft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.labbrother.model.TransactionResult;

public class StateMachine {

    private Map<String, String> db = new HashMap<>();

    // Layanan ping: Cek koneksi
    public String ping() {
        return "PONG";
    }

    // Layanan get: Ambil value, return kosong jika tidak ada
    public synchronized String get(String key) {
        return db.getOrDefault(key, "");
    }

    // Layanan set: Simpan/Overwrite value
    public synchronized String set(String key, String value) {
        db.put(key, value);
        return "OK";
    }

    // Layanan del: Hapus key & kembalikan nilai yang dihapus
    public synchronized String del(String key) {
        if (!db.containsKey(key))
            return "";
        return db.remove(key);
    }

    // Layanan append: Tambah string ke value lama (buat baru jika null)
    public synchronized String append(String key, String value) {
        if (!db.containsKey(key)) {
            db.put(key, "");
        }
        String oldVal = db.get(key);
        db.put(key, oldVal + value);
        return "OK";
    }

    // Layanan strln: Mengembalikan panjang karakter value
    public synchronized int strln(String key) {
        return db.getOrDefault(key, "").length();
    }

    // Helper: Lihat semua data (untuk debug)
    public synchronized Map<String, String> getAllData() {
        return new HashMap<>(db);
    }

    /**
     * Execute multiple commands as an atomic transaction.
     * If any command fails, all changes are rolled back.
     * 
     * @param transactionId Unique identifier for the transaction
     * @param commands      List of command strings (e.g., "set key value")
     * @return TransactionResult with success/failure status and results
     */
    public synchronized TransactionResult executeTransaction(String transactionId, List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return TransactionResult.failure(transactionId, "Empty transaction", -1);
        }

        // 1. Extract all keys that will be affected
        Set<String> affectedKeys = new HashSet<>();
        for (String cmd : commands) {
            String key = extractKeyFromCommand(cmd);
            if (key != null) {
                affectedKeys.add(key);
            }
        }

        // 2. Create snapshot of affected keys
        Map<String, String> snapshot = snapshotKeys(affectedKeys);

        // 3. Execute commands one by one
        List<String> results = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            String cmd = commands.get(i);
            try {
                String result = executeSingleCommand(cmd);
                if (result.startsWith("ERROR")) {
                    // Command failed - rollback
                    restoreSnapshot(snapshot, affectedKeys);
                    return TransactionResult.failure(transactionId, result, i);
                }
                results.add(result);
            } catch (Exception e) {
                // Exception occurred - rollback
                restoreSnapshot(snapshot, affectedKeys);
                return TransactionResult.failure(transactionId, "Exception: " + e.getMessage(), i);
            }
        }

        // 4. All commands succeeded
        return TransactionResult.success(transactionId, results);
    }

    /**
     * Execute a single command string.
     */
    private String executeSingleCommand(String commandString) {
        String[] parts = commandString.trim().split("\\s+", 3);
        if (parts.length == 0)
            return "ERROR: Empty command";

        String command = parts[0].toLowerCase();
        String key = parts.length > 1 ? parts[1] : "";
        String value = parts.length > 2 ? parts[2] : "";

        switch (command) {
            case "ping":
                return ping();
            case "set":
                if (key.isEmpty())
                    return "ERROR: set requires a key";
                return set(key, value);
            case "append":
                if (key.isEmpty())
                    return "ERROR: append requires a key";
                return append(key, value);
            case "del":
                if (key.isEmpty())
                    return "ERROR: del requires a key";
                return del(key);
            case "strln":
                if (key.isEmpty())
                    return "ERROR: strln requires a key";
                return String.valueOf(strln(key));
            case "get":
                if (key.isEmpty())
                    return "ERROR: get requires a key";
                return get(key);
            default:
                return "ERROR: Unknown command: " + command;
        }
    }

    /**
     * Extract the key from a command string.
     */
    private String extractKeyFromCommand(String commandString) {
        String[] parts = commandString.trim().split("\\s+", 3);
        if (parts.length < 2)
            return null;

        String command = parts[0].toLowerCase();
        // ping doesn't have a key
        if ("ping".equals(command))
            return null;

        return parts[1];
    }

    /**
     * Create a snapshot of the specified keys.
     * Stores null for keys that don't exist.
     */
    private Map<String, String> snapshotKeys(Set<String> keys) {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : keys) {
            if (db.containsKey(key)) {
                snapshot.put(key, db.get(key));
            } else {
                // Mark as non-existent with null
                snapshot.put(key, null);
            }
        }
        return snapshot;
    }

    /**
     * Restore db to the state captured in the snapshot.
     */
    private void restoreSnapshot(Map<String, String> snapshot, Set<String> affectedKeys) {
        for (String key : affectedKeys) {
            if (snapshot.containsKey(key)) {
                String value = snapshot.get(key);
                if (value == null) {
                    // Key didn't exist before transaction
                    db.remove(key);
                } else {
                    db.put(key, value);
                }
            }
        }
    }

    @Override
    public String toString() {
        return db.toString();
    }
}
