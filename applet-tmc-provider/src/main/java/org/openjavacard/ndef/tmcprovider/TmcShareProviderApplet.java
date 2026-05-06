/*
 * openjavacard-ndef: JavaCard applet implementing an NDEF type 4 tag
 * Copyright (C) 2015-2024 Ingo Albrecht <copyright@promovicz.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */
package org.openjavacard.ndef.tmcprovider;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Shareable;
import javacard.framework.Util;
import org.openjavacard.ndef.tmc.TmcDataSource;

/**
 * Test applet that exposes fixed bytes to the TMC applet through a Shareable
 * interface. It also supports GET DATA 00CA000000 for direct installation
 * smoke tests.
 */
public final class TmcShareProviderApplet extends Applet implements TmcDataSource {

    private static final byte INS_GET_DATA = (byte) 0xCA;
    private static final short SHARE_DATA_LEN = (short) 18;

    private final byte[] shareData;

    private TmcShareProviderApplet() {
        shareData = new byte[SHARE_DATA_LEN];
        shareData[0] = (byte) 'S';
        shareData[1] = (byte) 'H';
        shareData[2] = (byte) 'A';
        shareData[3] = (byte) 'R';
        shareData[4] = (byte) 'E';
        shareData[5] = (byte) 'D';
        shareData[6] = (byte) 'A';
        shareData[7] = (byte) 'T';
        shareData[8] = (byte) 'A';
        shareData[9] = (byte) '-';
        shareData[10] = (byte) '2';
        shareData[11] = (byte) '0';
        shareData[12] = (byte) '2';
        shareData[13] = (byte) '6';
        shareData[14] = (byte) '0';
        shareData[15] = (byte) '5';
        shareData[16] = (byte) '0';
        shareData[17] = (byte) '6';
    }

    public static void install(byte[] buf, short off, byte len) {
        short pos = off;
        byte aidLen = buf[pos++];
        short aidOff = pos;

        new TmcShareProviderApplet().register(buf, aidOff, aidLen);
    }

    public Shareable getShareableInterfaceObject(javacard.framework.AID clientAID,
                                                 byte parameter) {
        if (parameter != (byte) 0x00) {
            return null;
        }
        return this;
    }

    public short getTmcData(byte[] out, short outOff, short maxLen) {
        short len = (short) shareData.length;
        if (len > maxLen) {
            len = maxLen;
        }
        Util.arrayCopyNonAtomic(shareData, (short) 0, out, outOff, len);
        return len;
    }

    public void process(APDU apdu) {
        byte[] buf = apdu.getBuffer();

        if (selectingApplet()) {
            return;
        }

        if (buf[ISO7816.OFFSET_CLA] != (byte) 0x00 ||
                buf[ISO7816.OFFSET_INS] != INS_GET_DATA) {
            ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }

        apdu.setOutgoing();
        apdu.setOutgoingLength((short) shareData.length);
        apdu.sendBytesLong(shareData, (short) 0, (short) shareData.length);
    }
}
