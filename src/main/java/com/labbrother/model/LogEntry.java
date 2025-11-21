package com.labbrother.model;

public class LogEntry {

    private int term;
    private String command;

    // 1. Constructor Kosong (Wajib untuk JSON)
    public LogEntry() {
        // Kosong
    }

    // 2. Constructor Utama
    public LogEntry(int term, String command) {
        this.term = term;
        this.command = command;
    }

    // 3. Getters
    public int getTerm() {
        return term;
    }

    public String getCommand() {
        return command;
    }

    // 4. Setters
    public void setTerm(int term) {
        this.term = term;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    // 5. toString (Agar enak dibaca saat logging)
    @Override
    public String toString() {
        return "[Term " + term + "] " + command;
    }
}