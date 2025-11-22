package com.labbrother;

import java.net.URL;
import java.util.Scanner;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.labbrother.model.Address;
import com.labbrother.model.Message;
import com.labbrother.network.RpcService;

public class Client {

    public static void main(String[] args) {
        // Validasi argumen: java Client <server_ip> <server_port>
        if (args.length < 2) {
            System.out.println("Usage: java Client <server_ip> <server_port>");
            System.exit(1);
        }

        String serverIp = args[0];
        int serverPort = Integer.parseInt(args[1]);

        try {
            // 1. Setup Koneksi ke Server (Proxy)
            // Kita seolah-olah punya objek RpcService lokal, padahal fungsinya ada di server
            URL serverUrl = new URL("http://" + serverIp + ":" + serverPort + "/");
            JsonRpcHttpClient client = new JsonRpcHttpClient(serverUrl);
            
            // Proxy ini adalah "Jembatan Ajaib"
            RpcService serverProxy = com.googlecode.jsonrpc4j.ProxyUtil.createClientProxy(
                Client.class.getClassLoader(), 
                RpcService.class, 
                client
            );

            System.out.println("Terhubung ke Server di " + serverIp + ":" + serverPort);
            System.out.println("Ketik perintah (contoh: ping, set key val, get key, append key val)");
            System.out.println("Ketik 'exit' untuk keluar.");

            // 2. Loop Baca Input Terminal
            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("> ");
                String line = scanner.nextLine();

                if (line.equalsIgnoreCase("exit")) {
                    break;
                }
                
                if (line.trim().isEmpty()) continue;

                // 3. Parsing Perintah
                // Format: <command> [key] [value]
                String[] parts = line.split(" ");
                String command = parts[0];
                
                // Siapkan Pesan
                Message request = new Message();
                request.setType("CLIENT_REQUEST");
                request.setSender(new Address("client", 0)); // Identitas client dummy
                
                // Masukkan perintah asli ke dalam payload
                // Format payload string: "command key value"
                request.setPayload(line); 

                try {
                    // 4. KIRIM KE SERVER!
                    // Kita panggil fungsi execute() milik server lewat proxy
                    Message response = serverProxy.execute(request);
                    
                    // 5. Tampilkan Jawaban
                    System.out.println("[Server Balas]: " + response.getStatus() + " - " + response.getPayload());
                    
                    // Cek jika disuruh Redirect (Nanti berguna kalau ada banyak node)
                    if ("REDIRECT".equals(response.getStatus())) {
                        System.out.println("TODO: Harusnya pindah koneksi ke Leader baru...");
                    }

                } catch (Exception e) {
                    System.err.println("Gagal kirim request: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}