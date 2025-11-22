package com.labbrother;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.labbrother.model.Address;
import com.labbrother.network.RpcService;
import com.labbrother.raft.RaftNode;
import com.sun.net.httpserver.HttpServer;

public class Server {
    private static final Logger LOGGER = Logger.getLogger(Server.class.getName());

    public static void main(String[] args) {
        // 1. Validasi Input Argumen
        if (args.length < 2) {
            System.out.println("Usage: java Server <my_ip> <my_port> [contact_ip] [contact_port]");
            System.exit(1);
        }

        // 2. Parse Alamat Diri Sendiri
        String myIp = args[0];
        int myPort = Integer.parseInt(args[1]);
        Address myAddress = new Address(myIp, myPort);

        // 3. Parse Alamat Contact Node (untuk Join Cluster)
        List<Address> initialPeers = new ArrayList<>();
        if (args.length >= 4) {
            String contactIp = args[2];
            int contactPort = Integer.parseInt(args[3]);
            initialPeers.add(new Address(contactIp, contactPort));
            System.out.println("Starting Node... Will try to join cluster via " + contactIp + ":" + contactPort);
        } else {
            System.out.println("Starting Node as Initial Leader (No contact node provided).");
        }

        try {
            // 4. Inisialisasi RaftNode
            RaftNode raftNode = new RaftNode(myAddress, initialPeers);
            raftNode.start();

            // 5. Setup JSON-RPC Server
            // Karena pom.xml sudah lengkap, JsonRpcServer aman digunakan
            JsonRpcServer jsonRpcServer = new JsonRpcServer(raftNode, RpcService.class);

            // Setup HTTP Server
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(myIp, myPort), 0);

            httpServer.createContext("/", exchange -> {
                try {
                    // A. Set Header Response JSON
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    
                    // B. Kirim Status Code 200 (OK)
                    exchange.sendResponseHeaders(200, 0);

                    // C. Handle Request dengan try-with-resources
                    try (InputStream is = exchange.getRequestBody();
                         OutputStream os = exchange.getResponseBody()) {
                        // D. Handle Request (Input -> Process -> Output)
                        jsonRpcServer.handleRequest(is, os);
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "IO error handling request", e);
                    try {
                        exchange.sendResponseHeaders(500, 0);
                    } catch (IOException ex) {
                        LOGGER.log(Level.SEVERE, "Error sending error response", ex);
                    }
                    exchange.close();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.SEVERE, "Runtime error handling request", e);
                    try {
                        exchange.sendResponseHeaders(500, 0);
                    } catch (IOException ex) {
                        LOGGER.log(Level.SEVERE, "Error sending error response", ex);
                    }
                    exchange.close();
                }
            });

            // Setup Thread Pool
            httpServer.setExecutor(Executors.newCachedThreadPool());
            
            // 6. Nyalakan Server
            httpServer.start();
            System.out.println("Server Raft berjalan di http://" + myIp + ":" + myPort);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Gagal menyalakan server", e);
            System.err.println("Gagal menyalakan server: " + e.getMessage());
        }
    }
}