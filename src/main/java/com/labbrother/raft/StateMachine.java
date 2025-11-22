package com.labbrother.raft;

import java.util.HashMap;
import java.util.Map;

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
        if (!db.containsKey(key)) return "";
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
    
    @Override
    public String toString() {
        return db.toString();
    }
}