package com.labbrother;

import com.labbrother.raft.StateMachine;
import com.labbrother.model.TransactionResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;

/**
 * Unit Test untuk Transaction Feature
 * 
 * Cakupan Test:
 * 1. Transaksi sukses dengan beberapa command
 * 2. Rollback ketika ada command yang gagal
 * 3. Transaksi kosong
 * 4. Transaksi dengan satu command
 */
public class TransactionTest {

    private StateMachine sm;

    @BeforeEach
    public void setUp() {
        sm = new StateMachine();
    }

    @Test
    public void testSuccessfulTransaction() {
        System.out.println("\n=== Test: Transaksi Sukses ===");

        List<String> commands = Arrays.asList(
                "set user1 alice",
                "set user2 bob",
                "append user1 _wonderland");

        TransactionResult result = sm.executeTransaction("txn1", commands);

        assertTrue(result.isSuccess(), "Transaksi harus sukses");
        assertEquals(3, result.getResults().size(), "Harus ada 3 hasil");
        assertEquals("OK", result.getResults().get(0));
        assertEquals("OK", result.getResults().get(1));
        assertEquals("OK", result.getResults().get(2));

        // Verify state
        assertEquals("alice_wonderland", sm.get("user1"));
        assertEquals("bob", sm.get("user2"));

        System.out.println("Transaksi berhasil: " + result);
    }

    @Test
    public void testTransactionRollbackOnError() {
        System.out.println("\n=== Test: Rollback saat Error ===");

        // Set initial state
        sm.set("existing", "initial_value");

        List<String> commands = Arrays.asList(
                "set existing new_value", // Ini akan dirollback
                "set newkey somevalue", // Ini juga dirollback
                "invalidcmd broken" // Command tidak valid
        );

        TransactionResult result = sm.executeTransaction("txn2", commands);

        assertFalse(result.isSuccess(), "Transaksi harus gagal");
        assertEquals(2, result.getFailedAtIndex(), "Harus gagal di index 2");
        assertTrue(result.getErrorMessage().contains("ERROR"), "Pesan error harus ada");

        // Verify rollback - state harus kembali ke sebelum transaksi
        assertEquals("initial_value", sm.get("existing"), "existing harus rollback ke nilai awal");
        assertEquals("", sm.get("newkey"), "newkey tidak boleh ada karena di-rollback");

        System.out.println("Rollback berhasil: " + result);
    }

    @Test
    public void testEmptyTransaction() {
        System.out.println("\n=== Test: Transaksi Kosong ===");

        List<String> emptyCommands = Arrays.asList();

        TransactionResult result = sm.executeTransaction("txn3", emptyCommands);

        assertFalse(result.isSuccess(), "Transaksi kosong harus gagal");
        assertTrue(result.getErrorMessage().contains("Empty"), "Pesan harus menyebutkan empty");

        System.out.println("Transaksi kosong ditolak dengan benar: " + result);
    }

    @Test
    public void testNullTransaction() {
        System.out.println("\n=== Test: Transaksi Null ===");

        TransactionResult result = sm.executeTransaction("txn4", null);

        assertFalse(result.isSuccess(), "Transaksi null harus gagal");

        System.out.println("Transaksi null ditolak dengan benar: " + result);
    }

    @Test
    public void testSingleCommandTransaction() {
        System.out.println("\n=== Test: Transaksi Satu Command ===");

        List<String> singleCommand = Arrays.asList("set solo value123");

        TransactionResult result = sm.executeTransaction("txn5", singleCommand);

        assertTrue(result.isSuccess(), "Transaksi satu command harus sukses");
        assertEquals(1, result.getResults().size());
        assertEquals("value123", sm.get("solo"));

        System.out.println("Transaksi satu command berhasil: " + result);
    }

    @Test
    public void testTransactionWithReadOperations() {
        System.out.println("\n=== Test: Transaksi dengan Operasi Baca ===");

        // Setup initial state
        sm.set("readable", "hello");

        List<String> commands = Arrays.asList(
                "get readable", // Read operation
                "strln readable", // Read operation
                "set readable world" // Write operation
        );

        TransactionResult result = sm.executeTransaction("txn6", commands);

        assertTrue(result.isSuccess(), "Transaksi harus sukses");
        assertEquals("hello", result.getResults().get(0), "get harus return nilai lama");
        assertEquals("5", result.getResults().get(1), "strln harus return 5");
        assertEquals("world", sm.get("readable"), "nilai harus terupdate");

        System.out.println("Transaksi dengan read berhasil: " + result);
    }

    @Test
    public void testTransactionRollbackPreservesNonExistentKeys() {
        System.out.println("\n=== Test: Rollback Key yang Tidak Ada Sebelumnya ===");

        // Pastikan key belum ada
        assertEquals("", sm.get("brandnew"));

        List<String> commands = Arrays.asList(
                "set brandnew created", // Buat key baru
                "del brandnew", // Hapus (valid)
                "unknowncmd error" // Error trigger
        );

        TransactionResult result = sm.executeTransaction("txn7", commands);

        assertFalse(result.isSuccess(), "Transaksi harus gagal");
        // Key yang tidak ada sebelumnya harus tetap tidak ada setelah rollback
        assertEquals("", sm.get("brandnew"), "Key baru harus di-rollback");

        System.out.println("Rollback key baru berhasil: " + result);
    }

    @Test
    public void testTransactionAtomicity() {
        System.out.println("\n=== Test: Atomisitas Transaksi ===");

        // Setup initial state
        sm.set("counter", "0");

        // First transaction - success
        List<String> txn1 = Arrays.asList(
                "set counter 1",
                "set counter 2",
                "set counter 3");
        TransactionResult result1 = sm.executeTransaction("txn-atomic-1", txn1);
        assertTrue(result1.isSuccess());
        assertEquals("3", sm.get("counter"));

        // Second transaction - fails at second command
        List<String> txn2 = Arrays.asList(
                "set counter 100",
                "badcmd fail");
        TransactionResult result2 = sm.executeTransaction("txn-atomic-2", txn2);
        assertFalse(result2.isSuccess());

        // Counter harus tetap 3 (dari transaksi pertama)
        assertEquals("3", sm.get("counter"), "Nilai harus rollback ke keadaan sebelum txn2");

        System.out.println("Atomisitas terjaga: " + result2);
    }
}
