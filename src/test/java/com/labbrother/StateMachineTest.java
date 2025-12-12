package com.labbrother;

import com.labbrother.raft.StateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Test untuk StateMachine (KV Store)
 * 
 * Cakupan Test:
 * 1. ping - pemeriksaan konektivitas
 * 2. get - dapatkan value (empty & existing key)
 * 3. set - simpan value (new & overwrite)
 * 4. append - konkatenasi ke existing value (new key & existing key)
 * 5. del - hapus key (existing & non-existing)
 * 6. strln - dapatkan panjang string
 * 7. Skenario kompleks - sesuai spesifikasi tubes
 * 8. Thread safety - test akses secara konkuren
 */
public class StateMachineTest {

    private StateMachine sm;

    @BeforeEach
    public void setUp() {
        sm = new StateMachine();
    }

    @Test
    public void testPing() {
        System.out.println("\n=== Test: Ping ===");
        String result = sm.ping();
        assertEquals("PONG", result, "Ping harus mengembalikan PONG");
        System.out.println("Ping returned: " + result);
    }

    @Test
    public void testGetEmptyKey() {
        System.out.println("\n=== Test: Get Empty Key ===");
        String result = sm.get("nonexistent");
        assertEquals("", result, "Get pada non-existing key harus mengembalikan string kosong");
        System.out.println("Get empty key mengembalikan: \"" + result + "\"");
    }

    @Test
    public void testSetAndGet() {
        System.out.println("\n=== Test: Set and Get ===");
        
        String setResult = sm.set("key1", "value1");
        assertEquals("OK", setResult, "Set harus mengembalikan OK");
        System.out.println("Set mengembalikan: " + setResult);
        
        String getResult = sm.get("key1");
        assertEquals("value1", getResult, "Get harus mengembalikan set value");
        System.out.println("Get mengembalikan: \"" + getResult + "\"");
    }

    @Test
    public void testSetOverwrite() {
        System.out.println("\n=== Test: Set Overwrite ===");
        
        sm.set("key1", "oldvalue");
        String result1 = sm.get("key1");
        assertEquals("oldvalue", result1);
        System.out.println("Value awal: \"" + result1 + "\"");
        
        sm.set("key1", "newvalue");
        String result2 = sm.get("key1");
        assertEquals("newvalue", result2, "Set harus overwrite existing value");
        System.out.println("Overwritten value: \"" + result2 + "\"");
    }

    @Test
    public void testAppendNewKey() {
        System.out.println("\n=== Test: Append ke New Key ===");
        
        String appendResult = sm.append("newkey", "hello");
        assertEquals("OK", appendResult, "Append harus mengembalikan OK");
        System.out.println("Append mengembalikan: " + appendResult);
        
        String getResult = sm.get("newkey");
        assertEquals("hello", getResult, "Append ke new key harus membuat key dengan value");
        System.out.println("Get mengembalikan: \"" + getResult + "\"");
    }

    @Test
    public void testAppendExistingKey() {
        System.out.println("\n=== Test: Append ke Existing Key ===");
        
        sm.set("key1", "hello");
        System.out.println("Value awal: \"" + sm.get("key1") + "\"");
        
        sm.append("key1", " world");
        String result = sm.get("key1");
        assertEquals("hello world", result, "Append harus konkatenasi ke existing value");
        System.out.println("Setelah append: \"" + result + "\"");
    }

    @Test
    public void testAppendMultipleTimes() {
        System.out.println("\n=== Test: Multiple Appends ===");
        
        sm.append("key1", "a");
        sm.append("key1", "b");
        sm.append("key1", "c");
        
        String result = sm.get("key1");
        assertEquals("abc", result, "Multiple appends harus konkatenasi dengan benar");
        System.out.println("Hasil setelah multiple appends: \"" + result + "\"");
    }

    @Test
    public void testDelExistingKey() {
        System.out.println("\n=== Test: Hapus Existing Key ===");
        
        sm.set("key1", "value1");
        String deletedValue = sm.del("key1");
        assertEquals("value1", deletedValue, "Del harus mengembalikan deleted value");
        System.out.println("Deleted value: \"" + deletedValue + "\"");
        
        String getAfterDel = sm.get("key1");
        assertEquals("", getAfterDel, "Key harus kosong setelah dihapus");
        System.out.println("Value setelah dihapus: \"" + getAfterDel + "\"");
    }

    @Test
    public void testDelNonExistingKey() {
        System.out.println("\n=== Test: Hapus Non-Existing Key ===");
        
        String result = sm.del("nonexistent");
        assertEquals("", result, "Del pada non-existing key harus mengembalikan string kosong");
        System.out.println("Del non-existing key mengembalikan: \"" + result + "\"");
    }

    @Test
    public void testStrlenExistingKey() {
        System.out.println("\n=== Test: Strlen Existing Key ===");
        
        sm.set("key1", "hello");
        int length = sm.strln("key1");
        assertEquals(5, length, "Strlen harus mengembalikan panjang yang benar");
        System.out.println("Strlen mengembalikan: " + length);
    }

    @Test
    public void testStrlenNonExistingKey() {
        System.out.println("\n=== Test: Strlen Non-Existing Key ===");
        
        int length = sm.strln("nonexistent");
        assertEquals(0, length, "Strlen pada non-existing key harus mengembalikan 0");
        System.out.println("Strlen non-existing key mengembalikan: " + length);
    }

    @Test
    public void testStrlenEmptyString() {
        System.out.println("\n=== Test: Strlen String Kosong ===");
        
        sm.set("key1", "");
        int length = sm.strln("key1");
        assertEquals(0, length, "Strlen pada string kosong harus mengembalikan 0");
        System.out.println("Strlen string kosong mengembalikan: " + length);
    }

    @Test
    public void testComplexScenario() {
        System.out.println("\n=== Test: Skenario kompleks (Sesuai Spesifikasi Tubes) ===");
        
        // Skenario dari spesifikasi tubes:
        // > set kunci satu
        // OK
        // > append kunci dua
        // OK
        // > get kunci
        // "satudua"
        // > strln kunci
        // 7
        // > del kunci
        // "satudua"
        // > get kunci
        // ""
        
        // set kunci satu
        sm.set("kunci", "satu");
        System.out.println("set kunci satu -> OK");
        
        // append kunci dua
        sm.append("kunci", "dua");
        System.out.println("append kunci dua -> OK");
        
        // get kunci
        String getValue = sm.get("kunci");
        assertEquals("satudua", getValue, "Setelah set 'satu' dan append 'dua', harus menjadi 'satudua'");
        System.out.println("get kunci -> \"" + getValue + "\"");
        
        // strln kunci
        int length = sm.strln("kunci");
        assertEquals(7, length, "'satudua' punya 7 karakter");
        System.out.println("strln kunci -> " + length);
        
        // del kunci
        String delValue = sm.del("kunci");
        assertEquals("satudua", delValue, "del harus mengembalikan the deleted value");
        System.out.println("del kunci -> \"" + delValue + "\"");
        
        // get kunci (after delete)
        String getAfterDel = sm.get("kunci");
        assertEquals("", getAfterDel, "Setelah delete, get harus mengembalikan string kosong");
        System.out.println("get kunci (setelah del) -> \"" + getAfterDel + "\"");
        
        System.out.println("Test skenario kompleks BERHASIL!");
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        System.out.println("\n=== Test: Akses konkuren (Thread Safety) ===");
        
        // Test thread safety dengan beberapa thread yang menulis ke key yang sama
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                sm.append("konkuren", "a");
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                sm.append("konkuren", "b");
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        String result = sm.get("konkuren");
        assertEquals(200, result.length(), "appends konkuren harus menghasilkan 200 karakter");
        System.out.println("Test akses konkuren berhasil. Panjang total: " + result.length());
    }
}