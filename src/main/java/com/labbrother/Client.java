package com.labbrother;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.labbrother.model.Address;
import com.labbrother.model.Message;
import com.labbrother.network.RpcService;

public class Client {

    private static Address currentServer;
    private static RpcService serverProxy;

    // Transaction state
    private static boolean inTransaction = false;
    private static List<String> transactionBuffer = new ArrayList<>();
    private static String currentTransactionId = null;

    public static void main(String[] args) {
        // Validasi argumen: java Client <server_ip> <server_port>
        if (args.length < 2) {
            System.out.println("Usage: java Client <server_ip> <server_port>");
            System.exit(1);
        }

        String serverIp = args[0];
        int serverPort = Integer.parseInt(args[1]);
        currentServer = new Address(serverIp, serverPort);

        try {
            // 1. Setup Koneksi ke Server (Proxy)
            // Kita seolah-olah punya objek RpcService lokal, padahal fungsinya ada di
            // server
            connectToServer(currentServer);

            System.out.println("=== Raft KV Store Client ===");
            System.out.println("Terhubung ke Server di " + currentServer);
            System.out.println("\nPerintah yang tersedia:");
            System.out.println("  ping                    - Cek koneksi");
            System.out.println("  set <key> <value>       - Set nilai");
            System.out.println("  get <key>               - Ambil nilai");
            System.out.println("  append <key> <value>    - Append ke nilai");
            System.out.println("  del <key>               - Hapus key");
            System.out.println("  strln <key>             - Panjang string");
            System.out.println("  logs                    - Lihat log Leader");
            System.out.println("  --- Transaction ---");
            System.out.println("  begin                   - Mulai transaksi");
            System.out.println("  commit                  - Commit transaksi");
            System.out.println("  abort                   - Batalkan transaksi");
            System.out.println("  exit                    - Keluar");
            System.out.println("=========================================\n");

            // 2. Loop Baca Input Terminal
            Scanner scanner = new Scanner(System.in);
            while (true) {
                // Show different prompt based on transaction state
                if (inTransaction) {
                    System.out.print("[TXN]> ");
                } else {
                    System.out.print("> ");
                }
                String line = scanner.nextLine();

                if (line.equalsIgnoreCase("exit")) {
                    if (inTransaction) {
                        System.out.println("[TXN] Warning: Transaksi dibatalkan karena exit.");
                        abortTransaction();
                    }
                    System.out.println("Goodbye!");
                    break;
                }

                if (line.trim().isEmpty())
                    continue;

                String cmd = line.trim().toLowerCase().split("\\s+")[0];

                // Handle transaction commands
                if ("begin".equals(cmd)) {
                    handleBeginTransaction();
                    continue;
                }

                if ("commit".equals(cmd)) {
                    handleCommitTransaction();
                    continue;
                }

                if ("abort".equals(cmd)) {
                    abortTransaction();
                    continue;
                }

                // Perintah khusus: logs
                if (line.trim().equalsIgnoreCase("logs")) {
                    if (inTransaction) {
                        System.out.println("[TXN] Log request tidak diizinkan dalam transaksi.");
                        continue;
                    }
                    handleRequestLog();
                    continue;
                }

                // If in transaction mode, buffer the command
                if (inTransaction) {
                    transactionBuffer.add(line.trim());
                    System.out.println("[TXN] Command ditambahkan (" + transactionBuffer.size() + " total)");
                    continue;
                }

                // 3. Normal command execution
                handleExecuteCommand(line);
            }

            scanner.close();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle BEGIN transaction command
     */
    private static void handleBeginTransaction() {
        if (inTransaction) {
            System.out.println("[TXN] Error: Transaksi sudah aktif. Commit atau abort dulu.");
            return;
        }

        inTransaction = true;
        transactionBuffer.clear();
        currentTransactionId = UUID.randomUUID().toString().substring(0, 8);
        System.out.println("[TXN] Transaksi dimulai (ID: " + currentTransactionId + ")");
        System.out.println("[TXN] Ketik command, lalu 'commit' untuk eksekusi atau 'abort' untuk batal.");
    }

    /**
     * Handle COMMIT transaction - send all buffered commands as one transaction
     */
    private static void handleCommitTransaction() {
        if (!inTransaction) {
            System.out.println("Error: Tidak ada transaksi yang aktif.");
            return;
        }

        if (transactionBuffer.isEmpty()) {
            System.out.println("[TXN] Warning: Transaksi kosong, tidak ada yang di-commit.");
            abortTransaction();
            return;
        }

        try {
            // Build TXN payload: TXN:txnId:["cmd1","cmd2",...]
            ObjectMapper mapper = new ObjectMapper();
            String jsonCommands = mapper.writeValueAsString(transactionBuffer);
            String transactionPayload = "TXN:" + currentTransactionId + ":" + jsonCommands;

            System.out.println("[TXN] Mengirim transaksi dengan " + transactionBuffer.size() + " command...");

            // Send as a single execute request
            handleExecuteCommand(transactionPayload);

        } catch (Exception e) {
            System.out.println("[TXN] Error: Gagal membuat payload transaksi: " + e.getMessage());
        } finally {
            // Reset transaction state
            inTransaction = false;
            transactionBuffer.clear();
            currentTransactionId = null;
        }
    }

    /**
     * Abort/cancel the current transaction
     */
    private static void abortTransaction() {
        if (!inTransaction) {
            System.out.println("Error: Tidak ada transaksi yang aktif.");
            return;
        }

        int discarded = transactionBuffer.size();
        inTransaction = false;
        transactionBuffer.clear();
        currentTransactionId = null;
        System.out.println("[TXN] Transaksi dibatalkan. " + discarded + " command dibuang.");
    }

    /**
     * Koneksi ke server tertentu dan buat proxy RPC. Proxy ini adalah "Jembatan
     * Ajaib"
     */
    private static void connectToServer(Address serverAddress) throws Exception {
        URL serverUrl = new URL("http://" + serverAddress.getIp() + ":" + serverAddress.getPort() + "/");
        JsonRpcHttpClient client = new JsonRpcHttpClient(serverUrl);
        client.setConnectionTimeoutMillis(2000);
        client.setReadTimeoutMillis(5000);

        serverProxy = com.googlecode.jsonrpc4j.ProxyUtil.createClientProxy(
                Client.class.getClassLoader(),
                RpcService.class,
                client);

        currentServer = serverAddress;
    }

    /**
     * Handle eksekusi command (set, get, append, del, strln, ping, TXN:...)
     */
    private static void handleExecuteCommand(String commandLine) {

        // Siapkan Pesan
        Message request = new Message();
        request.setType("CLIENT_REQUEST");
        request.setSender(new Address("client", 0)); // Identitas client dummy

        // Masukkan perintah asli ke dalam payload
        // Format payload string: "command key value" or "TXN:id:[...]"
        request.setPayload(commandLine);

        int maxRedirects = 5; // Hindari infinite redirect loop
        int redirectCount = 0;

        while (redirectCount < maxRedirects) {
            try {
                // 4. KIRIM KE SERVER!
                // Kita panggil fungsi execute() milik server lewat proxy
                Message response = serverProxy.execute(request);

                // 5. Tampilkan Jawaban
                if (response == null) {
                    System.out.println("ERROR: Tidak ada respons dari server");
                    return;
                }

                String status = response.getStatus();
                String responseType = response.getType();

                // Handle NULL status atau gunakan responseType sebagai fallback
                if (status == null && "REDIRECT".equals(responseType)) {
                    status = "REDIRECT";
                }

                // Cek jika disuruh Redirect (Nanti berguna kalau ada banyak node)
                if ("REDIRECT".equals(status) || "REDIRECT".equals(responseType)) {
                    // Server bukan Leader, redirect ke Leader
                    // Payload bisa berupa Address object atau LinkedHashMap
                    Address leaderAddress = null;

                    Object payload = response.getPayload();
                    if (payload instanceof Address) {
                        leaderAddress = (Address) payload;
                    } else if (payload instanceof Map) {
                        // Deserialize dari LinkedHashMap
                        Map<String, Object> map = (Map<String, Object>) payload;
                        String ip = (String) map.get("ip");
                        Integer port = (Integer) map.get("port");
                        if (ip != null && port != null) {
                            leaderAddress = new Address(ip, port);
                        }
                    }

                    if (leaderAddress == null) {
                        System.out.println("ERROR: Leader tidak diketahui. Cluster mungkin sedang election.");
                        return;
                    }

                    if (leaderAddress.equals(currentServer)) {
                        System.out.println("ERROR: Redirect loop detected!");
                        return;
                    }

                    System.out.println("[Redirect] Berpindah ke Leader: " + leaderAddress);
                    connectToServer(leaderAddress);
                    redirectCount++;

                    // Retry request ke Leader baru
                    continue;

                } else if ("OK".equals(status) || response.getPayload() != null) {
                    // Sukses
                    Object payload = response.getPayload();
                    String payloadStr = payload != null ? payload.toString() : "";

                    // Check if this is a transaction response
                    if (payloadStr.startsWith("TXN_OK:")) {
                        String results = payloadStr.substring(7);
                        System.out.println("[TXN] Transaksi berhasil!");
                        System.out.println("[TXN] Hasil: " + results);
                        return;
                    } else if (payloadStr.startsWith("TXN_FAIL:")) {
                        String error = payloadStr.substring(9);
                        System.out.println("[TXN] Transaksi GAGAL (rollback dilakukan)");
                        System.out.println("[TXN] Error: " + error);
                        return;
                    }

                    // Format output sesuai spesifikasi (non-transaction)
                    String command = commandLine.trim().split("\\s+")[0].toLowerCase();
                    if ("ping".equals(command)) {
                        System.out.println(payload); // PONG
                    } else if ("get".equals(command)) {
                        System.out.println("\"" + payload + "\"");
                    } else if ("strln".equals(command)) {
                        System.out.println(payload);
                    } else if ("del".equals(command)) {
                        System.out.println("\"" + payload + "\"");
                    } else {
                        System.out.println(payload); // OK untuk set/append
                    }
                    return;

                } else if ("FAIL".equals(status)) {
                    System.out.println("GAGAL: " + response.getPayload());
                    return;

                } else {
                    System.out.println("ERROR: Status tidak diketahui: " + status + " (tipe: " + responseType + ")");
                    if (response.getPayload() != null) {
                        System.out.println("  Payload: " + response.getPayload());
                    }
                    return;
                }

            } catch (Exception e) {
                System.err.println("ERROR: Gagal kirim request: " + e.getMessage());
                return;
            }
        }

        System.out.println("ERROR: Terlalu banyak redirect (" + maxRedirects + " kali)");
    }

    /**
     * Handle request untuk melihat log dari Leader
     */
    private static void handleRequestLog() {
        Message request = new Message();
        request.setType("LOG_REQUEST");
        request.setSender(new Address("client", 0));

        int maxRedirects = 5;
        int redirectCount = 0;

        while (redirectCount < maxRedirects) {
            try {
                Message response = serverProxy.requestLog(request);

                if (response == null) {
                    System.out.println("ERROR: No response from server");
                    return;
                }

                String responseType = response.getType();

                if ("REDIRECT".equals(responseType)) {
                    Address leaderAddress = null;

                    Object payload = response.getPayload();
                    if (payload instanceof Address) {
                        leaderAddress = (Address) payload;
                    } else if (payload instanceof Map) {
                        // Deserialize dari LinkedHashMap
                        Map<String, Object> map = (Map<String, Object>) payload;
                        String ip = (String) map.get("ip");
                        Integer port = (Integer) map.get("port");
                        if (ip != null && port != null) {
                            leaderAddress = new Address(ip, port);
                        }
                    }

                    if (leaderAddress == null) {
                        System.out.println("ERROR: Leader tidak diketahui. Cluster mungkin sedang election.");
                        return;
                    }

                    System.out.println("[Redirect] Berpindah ke Leader: " + leaderAddress);
                    connectToServer(leaderAddress);
                    redirectCount++;
                    continue;

                } else if ("LOG_RESPONSE".equals(responseType)) {
                    // Sukses mendapat log
                    List<Map<String, Object>> logEntries = (List<Map<String, Object>>) response.getPayload();

                    System.out.println("\n=== Log Entries dari Leader ===");
                    System.out.println("Total entries: " + logEntries.size());

                    if (logEntries.isEmpty()) {
                        System.out.println("(Log masih kosong)");
                    } else {
                        for (int i = 0; i < logEntries.size(); i++) {
                            Map<String, Object> entry = logEntries.get(i);
                            int term = (Integer) entry.get("term");
                            String command = (String) entry.get("command");
                            System.out.println("  [" + i + "] Term " + term + ": " + command);
                        }
                    }
                    System.out.println("================================\n");
                    return;

                } else {
                    System.out.println("ERROR: Unexpected response type: " + responseType);
                    return;
                }

            } catch (Exception e) {
                System.err.println("ERROR: Gagal berkomunikasi dengan server: " + e.getMessage());
                return;
            }
        }

        System.out.println("ERROR: Terlalu banyak redirect (" + maxRedirects + " kali)");
    }
}
