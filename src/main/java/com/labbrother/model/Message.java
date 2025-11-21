package com.labbrother.model;

import java.io.Serializable;

public class Message implements Serializable {

    private String type; // Jenis pesan: "REQUEST_VOTE", "APPEND_ENTRIES", "CLIENT_REQUEST", dll
    private Address sender; // Siapa yang mengirim pesan ini (IP & Port pengirim)
    private int term; // Term pengirim (Sangat penting untuk validasi Leader)
    private Object payload; // Isi pesan utama (Bisa LogEntry, String command, atau null), kita pakai Object agar fleksibel bisa diisi apa saja
    private String status; // Status balasan (Opsional, untuk response): "OK", "FAIL", "REDIRECT"

    // 1. Constructor Kosong (Wajib untuk JSON)
    public Message() {
        // Kosong
    }

    // 2. Constructor Helper (Untuk memudahkan pembuatan pesan baru)
    public Message(String type, Address sender, int term, Object payload) {
        this.type = type;
        this.sender = sender;
        this.term = term;
        this.payload = payload;
    }

    // --- Getters (Untuk baca data) ---
    public String getType() {
        return type;
    }

    public Address getSender() {
        return sender;
    }

    public int getTerm() {
        return term;
    }

    public Object getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    // --- Setters (Untuk isi data) ---
    public void setType(String type) {
        this.type = type;
    }

    public void setSender(Address sender) {
        this.sender = sender;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // toString (Untuk debugging di terminal)
    @Override
    public String toString() {
        return "Msg{type='" + type + "', term=" + term + ", status='" + status + "'}";
    }
}