/*
 * openjavacard-ndef: JavaCard applet implementing an NDEF type 4 tag
 * Copyright (C) 2015-2024 Ingo Albrecht <copyright@promovicz.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */
package org.openjavacard.ndef.tmc;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.KeyBuilder;
import javacard.security.RandomData;
import javacardx.crypto.Cipher;

/**
 * TMC T4T applet implementing a secure NDEF type 4 tag.
 *
 * Based on:
 *   NFC Forum Type 4 Tag Operation Specification v2.0
 *   UNIS TMC 4.0.0 T4T Application User Manual v1.0.5
 *
 * Features:
 *   - Lifecycle PERSONAL -> NORMAL
 *   - KEY file, NDEF file, config file
 *   - SM4-based security (using AES-128 as dev stand-in)
 *   - Dynamic NDEF read with ciphertext and MAC generation
 *
 * Crypto note:
 *   This implementation uses AES-128 as a stand-in for SM4
 *   (same 128-bit key and 16-byte block size). On SM4-capable
 *   hardware (UNIS TMC), replace algorithm constants:
 *     Cipher.ALG_AES_BLOCK_128_ECB_NOPAD -> Cipher.ALG_SM4_ECB_NOPAD
 *     Cipher.ALG_AES_BLOCK_128_CBC_NOPAD -> Cipher.ALG_SM4_CBC_NOPAD
 */
public final class NdefApplet extends Applet {

    /* ---- ISO7816 standard instructions ---- */
    private static final byte INS_SELECT        = ISO7816.INS_SELECT;
    private static final byte INS_READ_BINARY   = (byte) 0xB0;
    private static final byte INS_UPDATE_BINARY = (byte) 0xD6;

    /* ---- TMC-specific instructions ---- */
    private static final byte INS_CREATE_FILE    = (byte) 0xE0;
    private static final byte INS_WRITE_KEY      = (byte) 0xD4;
    private static final byte INS_PERSONAL_END   = (byte) 0xF1;
    private static final byte INS_GET_CHALLENGE  = (byte) 0x84;
    private static final byte INS_AUTH_82        = (byte) 0x82;  /* EXTERNAL AUTH (CLA=00) or VERIFY TK (CLA=80) */
    private static final byte INS_PUT_DATA       = (byte) 0xDA;
    private static final byte INS_GET_VERSION    = (byte) 0xCA;

    /* ---- File IDs ---- */
    private static final short FILEID_NONE              = (short) 0x0000;
    private static final short FILEID_NDEF_CAPABILITIES = (short) 0xE103;

    /* ---- CREATE FILE P1 ---- */
    private static final byte CREATE_NDEF   = (byte) 0x00;
    private static final byte CREATE_PRIVATE = (byte) 0x01;
    private static final byte CREATE_KEY    = (byte) 0x02;
    private static final byte CREATE_CONFIG = (byte) 0x03;
    private static final byte CREATE_TK     = (byte) 0x06;

    /* ---- File access ---- */
    private static final byte FILE_ACCESS_OPEN = (byte) 0x00;
    private static final byte FILE_ACCESS_NONE = (byte) 0xFF;

    /* ---- Lifecycle ---- */
    private static final byte LIFECYCLE_PERSONAL = (byte) 0x50;
    private static final byte LIFECYCLE_NORMAL   = (byte) 0x4E;

    /* ---- Key types ---- */
    private static final byte KEY_TYPE_DACK   = (byte) 0x01;
    private static final byte KEY_TYPE_WRITEK = (byte) 0x02;
    private static final byte KEY_TYPE_READK  = (byte) 0x03;

    private static final byte KEY_RECORD_LEN = 23;
    private static final byte KEY_DATA_LEN   = 16;

    /* ---- Config file TLV tags (2-byte) ---- */
    private static final short TAG_READK_ID   = (short) 0x0040;
    private static final short TAG_KEY_ID_LOC = (short) 0x0041;
    private static final short TAG_USER_OFF   = (short) 0x0042;
    private static final short TAG_USER_LEN   = (short) 0x0043;
    private static final short TAG_SN         = (short) 0x0044;
    private static final short TAG_SN_LOC     = (short) 0x0045;
    private static final short TAG_AUTH_CODE  = (short) 0x0046;
    private static final short TAG_ENC_LOC    = (short) 0x0047;
    private static final short TAG_MAC_OFF    = (short) 0x0048;
    private static final short TAG_MAC_LOC    = (short) 0x0049;

    /* ---- NDEF CC ---- */
    private static final byte NDEF_MAPPING_VERSION = (byte) 0x20;
    private static final byte CC_TAG_NDEF_FILE_CONTROL = 0x04;
    private static final byte CC_LEN_NDEF_FILE_CONTROL = 6;

    /* ---- Crypto (AES-128 stand-in for SM4) ---- */
    private static final byte CRYPTO_ECB_NOPAD = Cipher.ALG_AES_BLOCK_128_ECB_NOPAD;
    private static final byte CRYPTO_CBC_NOPAD = Cipher.ALG_AES_BLOCK_128_CBC_NOPAD;

    /* ---- Config ---- */
    private static final short NDEF_MAX_READ   = 128;
    private static final short NDEF_MAX_WRITE  = 128;
    private static final short DEFAULT_NDEF_SIZE = 256;
    private static final byte  VERSION_MAJOR   = (byte) 0x01;
    private static final byte  VERSION_MINOR   = (byte) 0x00;
    private static final byte  VERSION_PATCH   = (byte) 0x00;
    private static final byte  CHALLENGE_LEN   = 16;
    private static final byte  MAC_LEN         = 8;
    private static final byte  COUNTER_LEN     = 3;
    private static final byte  AUTH_CODE_LEN   = 5;
    private static final byte  RANDOM_LEN      = 8;
    private static final byte  DYN_ENC_LEN     = 16;

    /* ---- Install parameter tags ---- */
    private static final byte AD_TAG_NDEF_DATA_INITIAL = (byte) 0x80;
    private static final byte AD_TAG_NDEF_DATA_ACCESS  = (byte) 0x81;
    private static final byte AD_TAG_NDEF_DATA_SIZE    = (byte) 0x82;

    /* ---- Transient variable indices ---- */
    private static final byte VAR_SELECTED_FILE = (byte) 0;
    private static final short NUM_TRANSIENT_SHORT = (short) 1;
    private static final byte VAR_DYNAMIC_VALID = (byte) 0;
    private static final short NUM_TRANSIENT_BYTE = (short) 1;

    /* ---- Persistent state layout ---- */
    private static final byte PERSIST_LIFECYCLE = (byte) 0;
    private static final byte PERSIST_TK_VERIFIED = (byte) 1;
    private static final byte PERSIST_NUM_BYTES = (byte) 2;

    /* ================================================================
     *  Instance state
     * ================================================================ */

    /* Transient */
    private short[] transShorts;
    private byte[] transBytes;
    private byte[] challenge;

    /* Persistent state array: lifecycle(1) + tkVerified(1) */
    private byte[] persistent;

    /* Read counter: 3 bytes big-endian, persistent */
    private byte[] counter;

    /* File buffers */
    private byte[] capsFile;
    private byte[] ndefFile;
    private byte[] dynamicNdefFile;
    private byte[] keyFile;
    private byte[] configFile;

    /* File metadata */
    private short ndefFileId;
    private short keyFileId;
    private short configFileId;
    private byte  ndefSm;

    /* Crypto */
    private Cipher ecbCipher;
    private Cipher cbcCipher;
    private AESKey aesKey;
    private RandomData rng;

    /* ================================================================
     *  Installer
     * ================================================================ */
    public static void install(byte[] buf, short off, byte len) {
        short pos = off;
        byte lnAID = buf[pos++];
        short offAID = pos; pos += lnAID;
        byte lnCI  = buf[pos++]; pos += lnCI;  /* skip control info */
        byte lnAD  = buf[pos++];
        short offAD = pos; pos += lnAD;
        NdefApplet app = new NdefApplet(buf, offAD, lnAD);
        app.register(buf, offAID, lnAID);
    }

    /* ================================================================
     *  Constructor
     * ================================================================ */
    private NdefApplet(byte[] buf, short off, byte len) {
        /* Transient */
        transShorts = JCSystem.makeTransientShortArray(
                NUM_TRANSIENT_SHORT, JCSystem.CLEAR_ON_DESELECT);
        transBytes = JCSystem.makeTransientByteArray(
                NUM_TRANSIENT_BYTE, JCSystem.CLEAR_ON_DESELECT);
        challenge = JCSystem.makeTransientByteArray(
                (short) CHALLENGE_LEN, JCSystem.CLEAR_ON_DESELECT);

        /* Crypto primitives */
        ecbCipher = Cipher.getInstance(CRYPTO_ECB_NOPAD, false);
        cbcCipher = Cipher.getInstance(CRYPTO_CBC_NOPAD, false);
        aesKey = (AESKey) KeyBuilder.buildKey(
                KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_128, false);
        rng = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);

        /* Persistent state */
        persistent = new byte[PERSIST_NUM_BYTES];
        persistent[PERSIST_LIFECYCLE] = LIFECYCLE_PERSONAL;
        persistent[PERSIST_TK_VERIFIED] = (byte) 0;

        counter = new byte[COUNTER_LEN];

        /* Install parameters */
        short initSize = DEFAULT_NDEF_SIZE;
        byte  initRA = FILE_ACCESS_OPEN;
        byte  initWA = FILE_ACCESS_OPEN;
        byte[] initBuf = null;
        short  initOff = 0;
        short  initLen = 0;

        if (len > 0) {
            if (!UtilTLV.isTLVconsistent(buf, off, len))
                ISOException.throwIt(ISO7816.SW_DATA_INVALID);

            short tag = UtilTLV.findTag(buf, off, len, AD_TAG_NDEF_DATA_INITIAL);
            if (tag >= 0) {
                initBuf = buf;
                initLen = UtilTLV.decodeLengthField(buf, (short) (tag + 1));
                initOff = (short) (tag + 1 + UtilTLV.getLengthFieldLength(initLen));
                initWA = FILE_ACCESS_NONE;
                initSize = (short) (2 + initLen);
            }
            tag = UtilTLV.findTag(buf, off, len, AD_TAG_NDEF_DATA_ACCESS);
            if (tag >= 0) {
                short al = UtilTLV.decodeLengthField(buf, (short) (tag + 1));
                if (al != 2) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                initRA = buf[(short) (tag + 2)];
                initWA = buf[(short) (tag + 3)];
            }
            tag = UtilTLV.findTag(buf, off, len, AD_TAG_NDEF_DATA_SIZE);
            if (tag >= 0) {
                short sl = UtilTLV.decodeLengthField(buf, (short) (tag + 1));
                if (sl != 2) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                initSize = Util.getShort(buf, (short) (tag + 2));
                if (initSize < 0) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
            }
        }

        ndefFileId = 0;
        keyFileId = 0;
        configFileId = 0;
        ndefSm = 0;

        capsFile = makeInitCaps();
        ndefFile = makeInitData(initSize, initBuf, initOff, initLen);
        dynamicNdefFile = makeDynamicData(initSize);
    }

    /* ================================================================
     *  Capability container helpers
     * ================================================================ */
    private byte[] makeInitCaps() {
        byte[] c = new byte[15];
        Util.setShort(c, (short) 0, (short) 15);
        c[2] = NDEF_MAPPING_VERSION;
        Util.setShort(c, (short) 3, NDEF_MAX_READ);
        Util.setShort(c, (short) 5, NDEF_MAX_WRITE);
        return c;
    }

    private void rebuildCaps() {
        if (ndefFileId == 0 || ndefFile == null) return;
        short ds = (short) ndefFile.length;
        short cl = (short) (7 + 2 + CC_LEN_NDEF_FILE_CONTROL);
        capsFile = new byte[cl];
        short pos = 0;
        pos = Util.setShort(capsFile, pos, cl);
        capsFile[pos++] = NDEF_MAPPING_VERSION;
        pos = Util.setShort(capsFile, pos, NDEF_MAX_READ);
        pos = Util.setShort(capsFile, pos, NDEF_MAX_WRITE);
        capsFile[pos++] = CC_TAG_NDEF_FILE_CONTROL;
        capsFile[pos++] = CC_LEN_NDEF_FILE_CONTROL;
        pos = Util.setShort(capsFile, pos, ndefFileId);
        pos = Util.setShort(capsFile, pos, ds);
        capsFile[pos++] = FILE_ACCESS_OPEN;  /* read access exposed in CC */
        capsFile[pos++] = FILE_ACCESS_OPEN;  /* write access exposed in CC */
    }

    /* ================================================================
     *  Data file allocation
     * ================================================================ */
    private byte[] makeInitData(short size, byte[] init, short initOff, short initLen) {
        byte[] d = new byte[size];
        if (init != null && initLen > 0) {
            Util.setShort(d, (short) 0, initLen);
            Util.arrayCopyNonAtomic(init, initOff, d, (short) 2, initLen);
        }
        return d;
    }

    private byte[] makeDynamicData(short size) {
        try {
            return JCSystem.makeTransientByteArray(size, JCSystem.CLEAR_ON_DESELECT);
        } catch (Exception e) {
            return new byte[size];
        }
    }

    /* ================================================================
     *  APDU dispatch
     *
     *  Dispatched purely by INS; handlers check CLA where needed.
     *  This handles the TMC spec's mixed CLA conventions.
     * ================================================================ */
    public void process(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte ins = buf[ISO7816.OFFSET_INS];

        if (selectingApplet()) {
            transShorts[VAR_SELECTED_FILE] = FILEID_NONE;
            invalidateDynamicNdef();
            return;
        }

        if (ins == INS_SELECT)
            processSelect(apdu);
        else if (ins == INS_READ_BINARY)
            processReadBinary(apdu);
        else if (ins == INS_UPDATE_BINARY)
            processUpdateBinary(apdu);
        else if (ins == INS_GET_CHALLENGE)
            processGetChallenge(apdu);
        else if (ins == INS_AUTH_82) {
            /* INS 0x82: EXTERNAL AUTH (CLA=00) or VERIFY TK (CLA=80, P2=41) */
            byte cla = buf[ISO7816.OFFSET_CLA];
            if (cla == (byte) 0x00)
                processExternalAuth(apdu);
            else if (cla == (byte) 0x80)
                processVerifyTK(apdu);
            else
                ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        } else if (ins == INS_CREATE_FILE)
            processCreateFile(apdu);
        else if (ins == INS_WRITE_KEY)
            processWriteKey(apdu);
        else if (ins == INS_PERSONAL_END)
            processPersonalEnd(apdu);
        else if (ins == INS_PUT_DATA)
            processPutData(apdu);
        else if (ins == INS_GET_VERSION)
            processGetVersion(apdu);
        else
            ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
    }

    /* ================================================================
     *  SELECT
     * ================================================================ */
    private void processSelect(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short lc = apdu.setIncomingAndReceive();

        /* By name: select AID, just reset file selection */
        if (p1 == (byte) 0x04 && p2 == (byte) 0x00) {
            transShorts[VAR_SELECTED_FILE] = FILEID_NONE;
            invalidateDynamicNdef();
            return;
        }
        /* By file ID */
        if (p1 == (byte) 0x00 && p2 == (byte) 0x0C) {
            if (lc != 2) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            short fid = Util.getShort(buf, ISO7816.OFFSET_CDATA);
            if (fid == FILEID_NDEF_CAPABILITIES ||
                (fid == ndefFileId && ndefFile != null) ||
                (fid == keyFileId && keyFile != null) ||
                (fid == configFileId && configFile != null)) {
                transShorts[VAR_SELECTED_FILE] = fid;
                if (fid == ndefFileId) invalidateDynamicNdef();
            } else {
                ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
            }
            return;
        }
        ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
    }

    /* ================================================================
     *  CREATE FILE (PERSONAL only)
     * ================================================================ */
    private void processCreateFile(APDU apdu) {
        if (persistent[PERSIST_LIFECYCLE] != LIFECYCLE_PERSONAL)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buf = apdu.getBuffer();
        byte p1 = buf[ISO7816.OFFSET_P1];
        byte p2 = buf[ISO7816.OFFSET_P2];
        short lc = apdu.setIncomingAndReceive();

        switch (p1) {
            case CREATE_NDEF: {
                if (lc != 8) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
                short fid = Util.getShort(buf, (short) (ISO7816.OFFSET_CDATA));
                short sz  = Util.getShort(buf, (short) (ISO7816.OFFSET_CDATA + 2));
                if (ndefFileId != 0) ISOException.throwIt((short) 0x6A84);
                if (ndefFile == null || ndefFile.length != sz) {
                    byte[] prev = ndefFile;
                    ndefFile = new byte[sz];
                    dynamicNdefFile = makeDynamicData(sz);
                    if (prev != null) {
                        short copy = (short) (prev.length < sz ? prev.length : sz);
                        Util.arrayCopyNonAtomic(prev, (short) 0, ndefFile, (short) 0, copy);
                    }
                }
                ndefFileId = fid;
                ndefSm = buf[(short) (ISO7816.OFFSET_CDATA + 6)];
                invalidateDynamicNdef();
                rebuildCaps();
                break;
            }
            case CREATE_PRIVATE: {
                /* accepted but not stored in this implementation */
                if (lc != 8) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
                break;
            }
            case CREATE_KEY: {
                if (lc != 8) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
                if (keyFile != null) ISOException.throwIt((short) 0x6A84);
                short fid = Util.getShort(buf, (short) (ISO7816.OFFSET_CDATA));
                byte cnt = buf[(short) (ISO7816.OFFSET_CDATA + 2)];
                byte rlen = buf[(short) (ISO7816.OFFSET_CDATA + 3)];
                if (rlen != KEY_RECORD_LEN) ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                keyFileId = fid;
                keyFile = new byte[(short) (cnt * KEY_RECORD_LEN)];
                invalidateDynamicNdef();
                break;
            }
            case CREATE_CONFIG: {
                if (lc != 8) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
                if (configFile != null) ISOException.throwIt((short) 0x6A84);
                short fid = Util.getShort(buf, (short) (ISO7816.OFFSET_CDATA));
                short sz = Util.getShort(buf, (short) (ISO7816.OFFSET_CDATA + 2));
                configFileId = fid;
                configFile = new byte[sz];
                invalidateDynamicNdef();
                break;
            }
            case CREATE_TK: {
                /* Update transport key: requires VERIFY TK first */
                if (!tkVerified()) ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
                if (p2 != (byte) 0x01) ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
                if (lc != 16) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
                clearTkVerified();
                break;
            }
            default:
                ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        }
    }

    /* ================================================================
     *  VERIFY TK
     *
     *  Authenticate with transport key to enable TK update.
     *  Host encrypts 16-byte challenge with TK (SM4-ECB)
     *  and sends first 8 bytes.
     * ================================================================ */
    private void processVerifyTK(APDU apdu) {
        if (persistent[PERSIST_LIFECYCLE] != LIFECYCLE_PERSONAL)
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        byte[] buf = apdu.getBuffer();
        byte p2 = buf[ISO7816.OFFSET_P2];
        if (p2 != (byte) 0x41) ISOException.throwIt(ISO7816.SW_INCORRECT_P1P2);
        short lc = apdu.setIncomingAndReceive();
        if (lc != 8) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        if (!challengeIssued()) ISOException.throwIt((short) 0x6984);

        /* Default TK: all zeros (matches spec default) */
        byte[] tk = new byte[KEY_DATA_LEN];
        byte[] expected = new byte[KEY_DATA_LEN];
        try {
            aesKey.setKey(tk, (short) 0);
            ecbCipher.init(aesKey, Cipher.MODE_ENCRYPT);
            ecbCipher.doFinal(challenge, (short) 0, (short) KEY_DATA_LEN, expected, (short) 0);
        } catch (Exception e) {
            ISOException.throwIt((short) 0x6982);
        }

        for (byte i = 0; i < 8; i++) {
            if (buf[(short) (ISO7816.OFFSET_CDATA + i)] != expected[i])
                ISOException.throwIt((short) 0x6982);
        }
        persistent[PERSIST_TK_VERIFIED] = (byte) 1;
        /* Invalidate challenge (single-use) */
        clearChallenge();
    }

    /* ================================================================
     *  WRITE KEY
     * ================================================================ */
    private void processWriteKey(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte cla = buf[ISO7816.OFFSET_CLA];
        byte p1 = buf[ISO7816.OFFSET_P1];
        short lc = apdu.setIncomingAndReceive();

        if (keyFile == null) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        boolean isPersonal = (persistent[PERSIST_LIFECYCLE] == LIFECYCLE_PERSONAL);
        boolean isSecure = (cla == (byte) 0x84);

        if (!isPersonal && !isSecure)
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);

        if (!isPersonal && isSecure) {
            /* NORMAL + secure: decrypt with WRITEK, then write */
            if (lc < KEY_DATA_LEN) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
            byte[] wk = getDefaultKey(KEY_TYPE_WRITEK);
            byte[] plain = new byte[KEY_DATA_LEN];
            try {
                aesKey.setKey(wk, (short) 0);
                ecbCipher.init(aesKey, Cipher.MODE_DECRYPT);
                ecbCipher.doFinal(buf, ISO7816.OFFSET_CDATA,
                        (short) KEY_DATA_LEN, plain, (short) 0);
            } catch (Exception e) {
                ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
            }
            /* Write plaintext key record (first KEY_RECORD_LEN bytes) */
            if (p1 == (byte) 0x00) {
                updateKeyRecord(plain, (short) 0);
            } else {
                addKeyRecord(plain, (short) 0, p1);
            }
            invalidateDynamicNdef();
            return;
        }

        /* Personal plaintext write */
        if (lc != KEY_RECORD_LEN) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        if (p1 == (byte) 0x00) {
            updateKeyRecord(buf, ISO7816.OFFSET_CDATA);
        } else {
            addKeyRecord(buf, ISO7816.OFFSET_CDATA, p1);
        }
        invalidateDynamicNdef();
    }

    private void updateKeyRecord(byte[] buf, short off) {
        byte type = buf[off];
        byte idx  = buf[(short) (off + 1)];
        short pos = findKey(type, idx);
        if (pos < 0) ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
        Util.arrayCopyNonAtomic(buf, off, keyFile, pos, KEY_RECORD_LEN);
    }

    private void addKeyRecord(byte[] buf, short off, byte recordNum) {
        short pos = (short) (recordNum * KEY_RECORD_LEN);
        if (pos < 0 || (short) (pos + KEY_RECORD_LEN) > keyFile.length)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);
        Util.arrayCopyNonAtomic(buf, off, keyFile, pos, KEY_RECORD_LEN);
    }

    private short findKey(byte type, byte index) {
        for (short i = 0; (short) (i + KEY_RECORD_LEN) <= keyFile.length; i += KEY_RECORD_LEN) {
            if (keyFile[i] == type && keyFile[(short) (i + 1)] == index) return i;
        }
        return -1;
    }

    private byte[] getKeyData(byte type, byte index) {
        short off = findKey(type, index);
        if (off < 0) return null;
        byte[] d = new byte[KEY_DATA_LEN];
        Util.arrayCopyNonAtomic(keyFile, (short) (off + 7), d, (short) 0, KEY_DATA_LEN);
        return d;
    }

    private byte[] getDefaultKey(byte type) {
        byte[] k = getKeyData(type, (byte) 0);
        if (k == null) k = getKeyData(type, (byte) 1);
        if (k == null) { k = new byte[KEY_DATA_LEN]; }
        return k;
    }

    /* ================================================================
     *  PERSONAL END
     * ================================================================ */
    private void processPersonalEnd(APDU apdu) {
        if (ndefFile == null) ISOException.throwIt((short) 0x6985);
        persistent[PERSIST_LIFECYCLE] = LIFECYCLE_NORMAL;
        invalidateDynamicNdef();
    }

    /* ================================================================
     *  READ BINARY
     * ================================================================ */
    private void processReadBinary(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short fid  = transShorts[VAR_SELECTED_FILE];
        short off  = Util.getShort(buf, ISO7816.OFFSET_P1);

        byte[] file = resolveReadFile(fid);
        if (off < 0 || off >= file.length)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);

        short le = apdu.setOutgoingNoChaining();
        if (le > NDEF_MAX_READ) le = NDEF_MAX_READ;
        short lim = (short) (off + le);
        if (lim < 0) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        if (lim > file.length) le = (short) (file.length - off);

        if (fid == FILEID_NDEF_CAPABILITIES) {
            /* Patch CC before sending */
            Util.arrayCopyNonAtomic(file, (short) 0, buf, (short) 0, (short) file.length);
            apdu.setOutgoingLength(le);
            apdu.sendBytesLong(buf, off, le);
        } else if (fid == ndefFileId &&
                   persistent[PERSIST_LIFECYCLE] == LIFECYCLE_NORMAL &&
                   (off >= 2 || le > 2)) {
            /* Dynamic NDEF processing for data portion */
            ndefReadDynamic(apdu, buf, file, off, le);
        } else {
            apdu.setOutgoingLength(le);
            apdu.sendBytesLong(file, off, le);
        }
    }

    private byte[] resolveReadFile(short fid) {
        if (fid == FILEID_NDEF_CAPABILITIES) return capsFile;
        if (fid == ndefFileId) return ndefFile;
        if (fid == configFileId && persistent[PERSIST_LIFECYCLE] == LIFECYCLE_PERSONAL)
            return configFile;
        if (fid == keyFileId && persistent[PERSIST_LIFECYCLE] == LIFECYCLE_PERSONAL)
            return keyFile;
        ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        return null;
    }

    /* ================================================================
     *  UPDATE BINARY
     * ================================================================ */
    private void processUpdateBinary(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        short fid = transShorts[VAR_SELECTED_FILE];
        short off = Util.getShort(buf, ISO7816.OFFSET_P1);
        short lc  = apdu.setIncomingAndReceive();

        byte[] file = null;
        if (fid == ndefFileId) file = ndefFile;
        else if (fid == configFileId && persistent[PERSIST_LIFECYCLE] == LIFECYCLE_PERSONAL)
            file = configFile;
        else ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);

        if (off < 0 || off >= file.length)
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);
        if (lc > NDEF_MAX_WRITE) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        short lim = (short) (off + lc);
        if (lim < 0 || lim > file.length) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);

        Util.arrayCopy(buf, ISO7816.OFFSET_CDATA, file, off, lc);
        if (fid == ndefFileId || fid == configFileId) invalidateDynamicNdef();
    }

    /* ================================================================
     *  GET CHALLENGE
     * ================================================================ */
    private void processGetChallenge(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        rng.generateData(challenge, (short) 0, CHALLENGE_LEN);
        apdu.setOutgoing();
        apdu.setOutgoingLength(CHALLENGE_LEN);
        Util.arrayCopyNonAtomic(challenge, (short) 0, buf, (short) 0, CHALLENGE_LEN);
        apdu.sendBytes((short) 0, CHALLENGE_LEN);
    }

    /* ================================================================
     *  EXTERNAL AUTHENTICATE
     *
     *  Authenticate host using DACK key.
     *  P2 = key index.
     *  Host encrypts challenge with DACK (SM4-ECB), sends 16 bytes.
     * ================================================================ */
    private void processExternalAuth(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte p2 = buf[ISO7816.OFFSET_P2];
        short lc = apdu.setIncomingAndReceive();
        if (lc != 16) ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        if (!challengeIssued()) ISOException.throwIt((short) 0x6984);

        byte[] dack = getKeyData(KEY_TYPE_DACK, p2);
        if (dack == null) ISOException.throwIt((short) 0x6A88);

        byte[] expected = new byte[KEY_DATA_LEN];
        try {
            aesKey.setKey(dack, (short) 0);
            ecbCipher.init(aesKey, Cipher.MODE_ENCRYPT);
            ecbCipher.doFinal(challenge, (short) 0, (short) KEY_DATA_LEN, expected, (short) 0);
        } catch (Exception e) {
            ISOException.throwIt((short) 0x6982);
        }

        for (byte i = 0; i < 16; i++) {
            if (buf[(short) (ISO7816.OFFSET_CDATA + i)] != expected[i])
                ISOException.throwIt((short) 0x6982);
        }
        clearChallenge();
    }

    /* ================================================================
     *  PUT DATA (update config file)
     * ================================================================ */
    private void processPutData(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        byte cla = buf[ISO7816.OFFSET_CLA];
        byte p1  = buf[ISO7816.OFFSET_P1];
        byte p2  = buf[ISO7816.OFFSET_P2];
        short lc = apdu.setIncomingAndReceive();

        if (configFile == null) ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);

        boolean isPersonal = (persistent[PERSIST_LIFECYCLE] == LIFECYCLE_PERSONAL);

        if (p1 == (byte) 0x00 && p2 == (byte) 0x00) {
            /* Full config update */
            if (!isPersonal) {
                /* NORMAL: requires secure messaging (CLA=84) */
                if (cla != (byte) 0x84)
                    ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
                if (lc < MAC_LEN + 1)
                    ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
                /* Data layout: ciphertext(encrypted TLV data) + MAC(8 bytes) */
                short encLen = (short) (lc - MAC_LEN);
                byte[] wk = getDefaultKey(KEY_TYPE_WRITEK);
                if (wk == null) ISOException.throwIt((short) 0x6A88);

                /* Verify MAC over the ciphertext */
                if (!verifyMac(wk, buf, ISO7816.OFFSET_CDATA, encLen,
                               buf, (short) (ISO7816.OFFSET_CDATA + encLen)))
                    ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);

                /* Decrypt ciphertext */
                byte[] dec = new byte[encLen];
                try {
                    aesKey.setKey(wk, (short) 0);
                    ecbCipher.init(aesKey, Cipher.MODE_DECRYPT);
                    ecbCipher.doFinal(buf, ISO7816.OFFSET_CDATA, encLen, dec, (short) 0);
                } catch (Exception e) {
                    ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
                }
                /* Write decrypted data */
                short writeLen = (short) (encLen > configFile.length ? configFile.length : (int) encLen);
                Util.arrayCopyNonAtomic(dec, (short) 0, configFile, (short) 0, writeLen);
            } else {
                /* PERSONAL: plaintext write */
                short writeLen = (short) (lc > configFile.length ? configFile.length : (int) lc);
                Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, configFile, (short) 0, writeLen);
            }
        } else {
            /* Tag-specific update: P1P2 = tag value */
            short writeLen = (short) (lc > configFile.length ? configFile.length : (int) lc);
            Util.arrayCopyNonAtomic(buf, ISO7816.OFFSET_CDATA, configFile, (short) 0, writeLen);
        }
        invalidateDynamicNdef();
    }

    /* ================================================================
     *  GET VERSION
     * ================================================================ */
    private void processGetVersion(APDU apdu) {
        byte[] buf = apdu.getBuffer();
        buf[0] = VERSION_MAJOR;
        buf[1] = VERSION_MINOR;
        buf[2] = VERSION_PATCH;
        apdu.setOutgoing();
        apdu.setOutgoingLength((short) 3);
        apdu.sendBytes((short) 0, (short) 3);
    }

    /* ================================================================
     *  Dynamic NDEF read
     *
     *  Builds one cached dynamic NDEF response per selected-file session:
     *    - Replace KEY_ID at KEY_ID_LOCATION (hex)
     *    - Replace SN at SN_LOCATION (hex)
     *    - Encrypt user data at USER_OFF (ECB) -> in-place (hex)
     *    - Generate dynamic ciphertext at ENC_LOCATION (CBC) -> in-place (hex)
     *    - Compute MAC at MAC_LOCATION (CBC-MAC) -> in-place (hex)
     *
     *  TMC config offsets are absolute offsets in the NDEF file, including
     *  the two-byte NLEN field. They are not URL-relative offsets.
     * ================================================================ */
    private void ndefReadDynamic(APDU apdu, byte[] buf, byte[] file, short reqOff, short reqLe) {
        short dataLen = Util.getShort(file, (short) 0);
        short maxDataLen = (short) (file.length - 2);
        if (dataLen < 0 || dataLen > maxDataLen) {
            dataLen = maxDataLen;
        }
        short fullLen = (short) (dataLen + 2);

        if (dynamicNdefFile == null || dynamicNdefFile.length != file.length) {
            dynamicNdefFile = makeDynamicData((short) file.length);
            invalidateDynamicNdef();
        }
        if (!isDynamicNdefValid()) {
            buildDynamicNdef(file, dataLen, fullLen);
        }

        if (reqOff >= fullLen) {
            apdu.setOutgoingLength((short) 0);
            apdu.sendBytesLong(buf, (short) 0, (short) 0);
            return;
        }

        short outLen = reqLe;
        if ((short) (reqOff + outLen) > fullLen) {
            outLen = (short) (fullLen - reqOff);
        }
        apdu.setOutgoingLength(outLen);
        apdu.sendBytesLong(dynamicNdefFile, reqOff, outLen);
    }

    private void buildDynamicNdef(byte[] file, short dataLen, short fullLen) {
        Util.arrayCopyNonAtomic(file, (short) 0, dynamicNdefFile, (short) 0, fullLen);

        /* Parse config */
        if (configFile != null) {
            byte readkId  = (byte) configGetShort(TAG_READK_ID, (short) 0);
            short keyLoc  = configGetShort(TAG_KEY_ID_LOC, (short) -1);
            short userOff = configGetShort(TAG_USER_OFF, (short) -1);
            short userLen = configGetShort(TAG_USER_LEN, (short) 0);
            byte[] sn     = configGetBytes(TAG_SN, (byte) 10);
            short snLoc   = configGetShort(TAG_SN_LOC, (short) -1);
            byte[] auth   = configGetBytes(TAG_AUTH_CODE, AUTH_CODE_LEN);
            short encLoc  = configGetShort(TAG_ENC_LOC, (short) -1);
            short macOff  = configGetShort(TAG_MAC_OFF, (short) 0);
            short macLoc  = configGetShort(TAG_MAC_LOC, (short) -1);

            byte[] readk = getKeyData(KEY_TYPE_READK, readkId);
            if (readk == null) readk = getDefaultKey(KEY_TYPE_READK);

            /* Replace KEY_ID hex at keyLoc */
            if (isRangeInFile(keyLoc, (short) 2, fullLen)) {
                byteToHex(readkId, dynamicNdefFile, keyLoc);
            }
            /* Replace SN hex at snLoc */
            if (snLoc >= 0 && sn != null) {
                short snHexLen = (short) (sn.length * 2);
                if (isRangeInFile(snLoc, snHexLen, fullLen)) {
                    bytesToHex(sn, (short) 0, (short) sn.length, dynamicNdefFile, snLoc);
                }
            }
            /* User ciphertext: hex-decode at userOff, ECB encrypt, hex-encode back */
            if (userOff >= 0 && userLen > 0 && readk != null && userLen % 2 == 0) {
                short rawLen = (short) (userLen / 2);
                if (rawLen % 16 == 0 && isRangeInFile(userOff, userLen, fullLen)) {
                    byte[] raw = new byte[rawLen];
                    hexDecode(dynamicNdefFile, userOff, userLen, raw, (short) 0);
                    aesCipher(raw, (short) 0, rawLen, readk, Cipher.MODE_ENCRYPT, ecbCipher);
                    hexEncode(raw, (short) 0, rawLen, dynamicNdefFile, userOff);
                }
            }
            /* Dynamic ciphertext: COUNTER+AUTH_CODE+RANDOM -> CBC encrypt */
            if (encLoc >= 0 && readk != null &&
                    isRangeInFile(encLoc, (short) (DYN_ENC_LEN * 2), fullLen)) {
                incrementCounter();
                byte[] inp = new byte[DYN_ENC_LEN];
                inp[0] = counter[0]; inp[1] = counter[1]; inp[2] = counter[2];
                if (auth != null)
                    Util.arrayCopyNonAtomic(auth, (short) 0, inp, COUNTER_LEN,
                            (short) (auth.length < AUTH_CODE_LEN ? auth.length : AUTH_CODE_LEN));
                rng.generateData(inp, (short) (COUNTER_LEN + AUTH_CODE_LEN), RANDOM_LEN);

                byte[] encOut = new byte[DYN_ENC_LEN];
                aesCbcEncrypt(inp, (short) 0, DYN_ENC_LEN, readk, encOut, (short) 0);
                hexEncode(encOut, (short) 0, DYN_ENC_LEN, dynamicNdefFile, encLoc);

                /* MAC: over returned NDEF bytes from MAC_OFF up to MAC_LOCATION. */
                if (macLoc >= 0 && macOff >= 0 && macLoc >= macOff &&
                        isRangeInFile(macLoc, (short) (MAC_LEN * 2), fullLen)) {
                    short macInputLen = (short) (macLoc - macOff);
                    byte[] macResult = computeMac(readk, dynamicNdefFile, macOff, macInputLen);
                    if (macResult != null) {
                        hexEncode(macResult, (short) 0, MAC_LEN, dynamicNdefFile, macLoc);
                    }
                }
            }
        }

        transBytes[VAR_DYNAMIC_VALID] = (byte) 1;
    }

    private boolean isDynamicNdefValid() {
        return transBytes[VAR_DYNAMIC_VALID] != (byte) 0;
    }

    private void invalidateDynamicNdef() {
        transBytes[VAR_DYNAMIC_VALID] = (byte) 0;
    }

    private static boolean isRangeInFile(short off, short len, short fullLen) {
        if (off < 0 || len < 0 || fullLen < 0) return false;
        if (off > fullLen) return false;
        return len <= (short) (fullLen - off);
    }

    /* ================================================================
     *  AES/SM4 crypto helpers
     * ================================================================ */
    private void aesCipher(byte[] data, short off, short len, byte[] key,
                           byte mode, Cipher cipher) {
        try {
            aesKey.setKey(key, (short) 0);
            cipher.init(aesKey, mode);
            cipher.doFinal(data, off, len, data, off);
        } catch (Exception e) {
            /* silently skip */
        }
    }

    private void aesCbcEncrypt(byte[] in, short inOff, short inLen,
                                byte[] key, byte[] out, short outOff) {
        try {
            aesKey.setKey(key, (short) 0);
            byte[] iv = new byte[16];
            cbcCipher.init(aesKey, Cipher.MODE_ENCRYPT, iv, (short) 0, (short) 16);
            cbcCipher.doFinal(in, inOff, inLen, out, outOff);
        } catch (Exception e) {
            /* silently skip */
        }
    }

    /* ================================================================
     *  CBC-MAC computation (ISO 9797 method 2 padding)
     *
     *  AES/SM4-CBC with IV=0, pad with 80 00...
     *  Returns 16-byte block; caller takes first MAC_LEN bytes.
     * ================================================================ */
    private byte[] computeMac(byte[] key, byte[] input, short off, short len) {
        short rem = (short) (len % 16);
        short padLen = (rem == 0) ? (short) (len + 16) : (short) (len + 16 - rem);
        byte[] padded = new byte[padLen];
        Util.arrayCopyNonAtomic(input, off, padded, (short) 0, len);
        padded[len] = (byte) 0x80;

        byte[] iv = new byte[16];
        byte[] block = new byte[16];
        try {
            aesKey.setKey(key, (short) 0);
            cbcCipher.init(aesKey, Cipher.MODE_ENCRYPT, iv, (short) 0, (short) 16);
            for (short pos = 0; pos < padLen; pos += 16) {
                if ((short) (pos + 16) < padLen)
                    cbcCipher.update(padded, pos, (short) 16, block, (short) 0);
                else
                    cbcCipher.doFinal(padded, pos, (short) 16, block, (short) 0);
            }
        } catch (Exception e) {
            return null;
        }
        return block;
    }

    private boolean verifyMac(byte[] key, byte[] data, short off, short len,
                              byte[] macBuf, short macOff) {
        byte[] mac = computeMac(key, data, off, len);
        if (mac == null) return false;
        for (byte i = 0; i < MAC_LEN; i++)
            if (mac[i] != macBuf[(short) (macOff + i)]) return false;
        return true;
    }

    /* ================================================================
     *  Counter
     * ================================================================ */
    private void incrementCounter() {
        for (byte i = (byte) (COUNTER_LEN - 1); i >= 0; i--) {
            counter[i]++;
            if (counter[i] != 0) break;
        }
    }

    /* ================================================================
     *  Challenge helpers
     * ================================================================ */
    private boolean challengeIssued() {
        for (byte i = 0; i < CHALLENGE_LEN; i++)
            if (challenge[i] != 0) return true;
        return false;
    }

    private void clearChallenge() {
        Util.arrayFillNonAtomic(challenge, (short) 0, (short) CHALLENGE_LEN, (byte) 0);
    }

    private boolean tkVerified() {
        return persistent[PERSIST_TK_VERIFIED] != (byte) 0;
    }

    private void clearTkVerified() {
        persistent[PERSIST_TK_VERIFIED] = (byte) 0;
    }

    /* ================================================================
     *  Config file TLV parsing
     *
     *  Format per TMC spec: TAG(2) + LEN(1) + VALUE(len)
     *  Total per-entry overhead: 3 bytes
     * ================================================================ */
    private short configGetShort(short tag, short fallback) {
        if (configFile == null) return fallback;
        byte[] cf = configFile;
        short pos = 0;
        while ((short) (pos + 3) <= cf.length) {
            short t = Util.getShort(cf, pos);
            byte vl = cf[(short) (pos + 2)];
            if (t == tag) {
                if (vl == 2) return Util.getShort(cf, (short) (pos + 3));
                if (vl == 1) return (short) (cf[(short) (pos + 3)] & 0xFF);
                return fallback;
            }
            pos = (short) (pos + 3 + (vl & 0xFF));
        }
        return fallback;
    }

    private byte[] configGetBytes(short tag, byte expectedLen) {
        if (configFile == null) return null;
        byte[] cf = configFile;
        short pos = 0;
        while ((short) (pos + 3) <= cf.length) {
            short t = Util.getShort(cf, pos);
            byte vl = cf[(short) (pos + 2)];
            if (t == tag) {
                byte[] res = new byte[expectedLen];
                byte copyLen = (vl < expectedLen) ? vl : expectedLen;
                Util.arrayCopyNonAtomic(cf, (short) (pos + 3), res, (short) 0, (short) copyLen);
                return res;
            }
            pos = (short) (pos + 3 + (vl & 0xFF));
        }
        return null;
    }

    /* ================================================================
     *  Hex encode/decode
     * ================================================================ */
    private static void byteToHex(byte v, byte[] dst, short off) {
        byte hi = (byte) ((v >> 4) & 0x0F);
        byte lo = (byte) (v & 0x0F);
        dst[off]     = (byte) (hi < 10 ? '0' + hi : 'A' + hi - 10);
        dst[(short) (off + 1)] = (byte) (lo < 10 ? '0' + lo : 'A' + lo - 10);
    }

    private static void hexEncode(byte[] src, short srcOff, short srcLen,
                                  byte[] dst, short dstOff) {
        for (short i = 0; i < srcLen; i++) {
            byte b = src[(short) (srcOff + i)];
            byte hi = (byte) ((b >> 4) & 0x0F);
            byte lo = (byte) (b & 0x0F);
            dst[(short) (dstOff + i * 2)]     = (byte) (hi < 10 ? '0' + hi : 'A' + hi - 10);
            dst[(short) (dstOff + i * 2 + 1)] = (byte) (lo < 10 ? '0' + lo : 'A' + lo - 10);
        }
    }

    private static void bytesToHex(byte[] src, short srcOff, short srcLen,
                                   byte[] dst, short dstOff) {
        hexEncode(src, srcOff, srcLen, dst, dstOff);
    }

    private static void hexDecode(byte[] src, short srcOff, short srcLen,
                                  byte[] dst, short dstOff) {
        for (short i = 0; i < srcLen; i += 2) {
            byte hb = src[(short) (srcOff + i)];
            byte lb = src[(short) (srcOff + i + 1)];
            byte h = (byte) (hb >= 'a' ? hb - 'a' + 10 : hb >= 'A' ? hb - 'A' + 10 : hb - '0');
            byte l = (byte) (lb >= 'a' ? lb - 'a' + 10 : lb >= 'A' ? lb - 'A' + 10 : lb - '0');
            dst[(short) (dstOff + i / 2)] = (byte) ((h << 4) | l);
        }
    }
}
