package com.mocyx.basic_client;

import com.mocyx.basic_client.protocol.tcpip.Packet;

public class MessageEvent {
    public Packet packet;
    public String text;
    public boolean is_in;

    public MessageEvent(Packet packet, String text, boolean is_in) {
        this.packet = packet;
        this.text = text;
        this.is_in = is_in;
    }
}
