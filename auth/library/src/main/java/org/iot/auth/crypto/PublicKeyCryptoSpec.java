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

package org.iot.auth.crypto;


/**
 * A class for public key cryptography specifications
 * @author Hokeun Kim
 */
public class PublicKeyCryptoSpec extends CryptoSpec {
    private String signAlgorithm;
    //private String publicCipherAlgorithm = "RSA/ECB/PKCS1PADDING";
    //private int keySize = 256; // 2048 bits
    private String diffieHellman;

    public String getSignAlgorithm() {
        return signAlgorithm;
    }
    public String getDiffieHellman() {
        return diffieHellman;
    }
    //private int diffieHellmanKeySize = 48;  // 384 bits

    public PublicKeyCryptoSpec(String signAlgorithm, String diffieHellman) {
        this.signAlgorithm = signAlgorithm;
        this.diffieHellman = diffieHellman;
    }

    public static PublicKeyCryptoSpec fromSpecString(String cryptoSpecString) {
        String[] stringArray = cryptoSpecString.split(":");
        String jsSignAlgorithm = stringArray[0];

        String signAlgorithm = null;
        if (jsSignAlgorithm.toUpperCase().equals("RSA-SHA256")) {
            signAlgorithm = "SHA256withRSA";
        }
        String diffieHellman = null;
        if (stringArray.length > 1) {
            String jsDiffieHellman = stringArray[1];
            if (jsDiffieHellman.toUpperCase().contains("DH")) {
                diffieHellman = "DH";
            }
        }

        return new PublicKeyCryptoSpec(signAlgorithm, diffieHellman);
    }

    public String toSpecString() {
        String ret = signAlgorithm;
        if (diffieHellman != null) {
            ret += ":DH-secp384r1";
        }
        return ret;
    }
}
