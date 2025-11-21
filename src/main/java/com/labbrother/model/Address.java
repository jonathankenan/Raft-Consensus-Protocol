package com.labbrother.model;

import java.util.Objects;

public class Address {

    private String ip;
    private int port;

    // 1. Constructor Kosong (Wajib untuk Library JSON)
    public Address() {
        // Kosong
    }

    // 2. Constructor Utama
    public Address(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    // 3. Getters (Untuk mengambil nilai)
    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    // 4. Setters (Untuk mengubah nilai)
    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setPort(int port) {
        this.port = port;
    }

    // 5. toString (Agar output print rapi)
    @Override
    public String toString() {
        return ip + ":" + port;
    }

    // 6. equals (Untuk membandingkan alamat)
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Address address = (Address) o;
        return port == address.port && Objects.equals(ip, address.ip);
    }

    // 7. hashCode (Pasangan wajib equals)
    @Override
    public int hashCode() {
        return Objects.hash(ip, port);
    }
}