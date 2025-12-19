package com.labbrother.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a transaction execution.
 * Contains success/failure status and individual command results.
 */
public class TransactionResult {

    private boolean success;
    private String transactionId;
    private List<String> results;
    private String errorMessage;
    private int failedAtIndex;

    // Default constructor
    public TransactionResult() {
        this.results = new ArrayList<>();
        this.failedAtIndex = -1;
    }

    // Success constructor
    public static TransactionResult success(String transactionId, List<String> results) {
        TransactionResult result = new TransactionResult();
        result.success = true;
        result.transactionId = transactionId;
        result.results = results;
        return result;
    }

    // Failure constructor
    public static TransactionResult failure(String transactionId, String errorMessage, int failedAtIndex) {
        TransactionResult result = new TransactionResult();
        result.success = false;
        result.transactionId = transactionId;
        result.errorMessage = errorMessage;
        result.failedAtIndex = failedAtIndex;
        return result;
    }

    // Getters
    public boolean isSuccess() {
        return success;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public List<String> getResults() {
        return results;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getFailedAtIndex() {
        return failedAtIndex;
    }

    // Setters
    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public void setResults(List<String> results) {
        this.results = results;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setFailedAtIndex(int failedAtIndex) {
        this.failedAtIndex = failedAtIndex;
    }

    @Override
    public String toString() {
        if (success) {
            return "TXN[" + transactionId + "] SUCCESS: " + results;
        } else {
            return "TXN[" + transactionId + "] FAILED at index " + failedAtIndex + ": " + errorMessage;
        }
    }
}
