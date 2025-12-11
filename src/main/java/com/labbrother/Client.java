package com.labbrother;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.labbrother.model.Address;
import com.labbrother.model.Message;
import com.labbrother.network.RpcService;

public class Client {

    private static Address currentServer;
    private static RpcService serverProxy;

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
            // Kita seolah-olah punya objek RpcService lokal, padahal fungsinya ada di server
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
            System.out.println("  exit                    - Keluar");
            System.out.println("=========================================\n");

            // 2. Loop Baca Input Terminal
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();

                if (line.equalsIgnoreCase("exit")) {
                    System.out.println("Goodbye!");
                    break;
                }
                
                if (line.trim().isEmpty()) continue;

                // Perintah khusus: logs
                if (line.trim().equalsIgnoreCase("logs")) {
                    handleRequestLog();
                    continue;
                }

                // 3. Parsing Perintah
                // Format: <command> [key] [value]
                handleExecuteCommand(line);
            }
            
            scanner.close();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Koneksi ke server tertentu dan buat proxy RPC. Proxy ini adalah "Jembatan Ajaib"
     */
    private static void connectToServer(Address serverAddress) throws Exception {
        URL serverUrl = new URL("http://" + serverAddress.getIp() + ":" + serverAddress.getPort() + "/");
        JsonRpcHttpClient client = new JsonRpcHttpClient(serverUrl);
        client.setConnectionTimeoutMillis(2000);
        client.setReadTimeoutMillis(5000);
        
        serverProxy = com.googlecode.jsonrpc4j.ProxyUtil.createClientProxy(
            Client.class.getClassLoader(), 
            RpcService.class, 
            client
        );
        
        currentServer = serverAddress;
    }

    /**
     * Handle eksekusi command (set, get, append, del, strln, ping)
     */
    private static void handleExecuteCommand(String commandLine) {

        // Siapkan Pesan
        Message request = new Message();
        request.setType("CLIENT_REQUEST");
        request.setSender(new Address("client", 0)); // Identitas client dummy

        // Masukkan perintah asli ke dalam payload
        // Format payload string: "command key value"
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
                
                // Cek jika disuruh Redirect (Nanti berguna kalau ada banyak node)
                if ("REDIRECT".equals(status)) {
                    // Server bukan Leader, redirect ke Leader
                    Address leaderAddress = (Address) response.getPayload();
                    
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
                    
                } else if ("OK".equals(status)) {
                    // Sukses
                    Object payload = response.getPayload();
                    
                    // Format output sesuai spesifikasi
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
                    System.out.println("ERROR: Status tidak diketahui: " + status);
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
                    Address leaderAddress = (Address) response.getPayload();
                    
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