package com.labbrother.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model class representing a transaction containing multiple commands.
 * Used for atomic execution of multiple KV store operations.
 */
public class Transaction {

    private String transactionId;
    private List<String> commands;

    // Default constructor for JSON serialization
    public Transaction() {
        this.transactionId = UUID.randomUUID().toString().substring(0, 8);
        this.commands = new ArrayList<>();
    }

    // Constructor with commands
    public Transaction(List<String> commands) {
        this.transactionId = UUID.randomUUID().toString().substring(0, 8);
        this.commands = new ArrayList<>(commands);
    }

    // Getters
    public String getTransactionId() {
        return transactionId;
    }

    public List<String> getCommands() {
        return commands;
    }

    // Setters
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public void setCommands(List<String> commands) {
        this.commands = commands;
    }

    // Helper to add a command
    public void addCommand(String command) {
        this.commands.add(command);
    }

    // Check if transaction is empty
    public boolean isEmpty() {
        return commands == null || commands.isEmpty();
    }

    // Get the number of commands
    public int size() {
        return commands == null ? 0 : commands.size();
    }

    @Override
    public String toString() {
        return "Transaction[" + transactionId + "] (" + size() + " commands)";
    }
}
