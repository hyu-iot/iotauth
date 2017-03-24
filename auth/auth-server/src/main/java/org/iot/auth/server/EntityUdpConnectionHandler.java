/*
 * Copyright (c) 2016, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * IOTAUTH_COPYRIGHT_VERSION_1
 */

package org.iot.auth.server;

import org.iot.auth.AuthServer;
import org.iot.auth.io.Buffer;
import org.iot.auth.util.ExceptionToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by hokeunkim on 9/24/16.
 */
public class EntityUdpConnectionHandler extends EntityConnectionHandler {
    /**
     * Constructor for the entity connection handler, to process a connected entity
     * @param server Auth server that this handler is for
     * @param timeout A timeout for the connection with the entity
     */
    public EntityUdpConnectionHandler(AuthServer server, DatagramSocket entitySocket, InetAddress socketAddress,
                                      int socketPort, long timeout, Map<String, Buffer> responseMap, Buffer sessionKeyRequest, Buffer authNonce) {
        super(server);
        this.datagramSocket = entitySocket;
        this.socketAddress = socketAddress;
        this.socketPort = socketPort;
        this.timeOut = timeout;
        this.responseMap = responseMap;
        this.sessionKeyRequest = sessionKeyRequest;
        this.authNonce = authNonce;
        this.isOpen = true;
    }

    /**
     * Run method from the parent class, Thread
     */
    public void run() {
        // Process session key request
        try {
            handleSessionKeyReq(sessionKeyRequest.getRawBytes(), authNonce);
        }
        catch (Exception e) {
            getLogger().error("Exception occurred while handling Auth service!\n {}",
                    ExceptionToString.convertExceptionToStackTrace(e));
            close();
            return;
        }
        close();
    }
    /**
     * Close TCP connection with the entity.
     */
    protected void close() {
        isOpen = false;
    }

    protected Logger getLogger() {
        return logger;
    }

    protected String getRemoteAddress() {
        return socketAddress.toString() + ":" + socketPort;
    }


    protected void writeToSocket(byte[] bytes) throws IOException {
        String addressKey = socketAddress + ":" + socketPort;
        responseMap.put(addressKey, new Buffer(bytes));
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                responseMap.remove(addressKey);
            }
        }, timeOut);
        DatagramPacket packetToSend = new DatagramPacket(bytes, bytes.length, socketAddress, socketPort);
        datagramSocket.send(packetToSend);
    }

    private boolean isOpen() {
        return isOpen;
    }

    private static final Logger logger = LoggerFactory.getLogger(EntityUdpConnectionHandler.class);
    private DatagramSocket datagramSocket;
    private InetAddress socketAddress;
    private int socketPort;
    private long timeOut;
    private boolean isOpen;
    private Map<String, Buffer> responseMap;
    Buffer sessionKeyRequest;
    Buffer authNonce;
}
