# TMC T4T Applet Implementation Summary

## Overview

Created a new `applet-tmc` variant implementing the **UNIS TMC 4.0.0 T4T Application** specification based on `UM_T4T.pdf` (51 pages). This is a secure NFC Type 4 Tag applet with lifecycle management, key-based security, and dynamic NDEF data generation.

## PDF Analysis (UM_T4T.pdf)

**Protocol features documented:**
- Lifecycle: PERSONAL (personalization) → NORMAL (usage)
- File system: KEY file (23-byte records), CC file, NDEF file, Config file (TLV format), Private files
- Keys: DACK (authentication 0x01), WRITEK (maintenance 0x02), READK (read 0x03) — 16-byte each
- Config TLV tags: 0x0040-0x0049 for READK index, SN, auth code, offsets/locations; optional local extension tags 0x004A-0x004D for Shareable applet URL data
- Commands: SELECT, CREATE FILE, VERIFY TK, WRITE KEY, PERSONAL END, READ DATA, WRITE DATA, GET CHALLENGE, EXTERNAL AUTHENTICATE, PUT DATA, GET VERSION
- Security: SM4-ECB (user ciphertext, key wrapping), SM4-CBC (dynamic ciphertext), CBC-MAC (ISO 9797 method 2 padding, 8-byte output)
- Dynamic NDEF read: Replace placeholders in URL, compute ciphertext/MAC on-the-fly, increment read counter

## TMC Applet Implementation

**File**: `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/NdefApplet.java`
**File**: `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/TmcDataSource.java` (Shareable applet-data source interface)
**File**: `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/UtilTLV.java` (TLV parser, copied from full variant)

### Architecture

- Package AID: `D2760000850101` (7 bytes)
- Applet instance AID: `D276000085010101` (8 bytes)
- Lifecycle state: persistent byte array (`PERSIST_LIFECYCLE`)
- Transient: selected file ID, 16-byte challenge
- Persistent: lifecycle, TK verified flag, 3-byte read counter, key file, config file, NDEF file

### Command Implementation

| Command | CLA | INS | P1 | Description |
|---------|-----|-----|-----|-------------|
| SELECT | 00 | A4 | 04=by name, 00=by FID | Select applet or file |
| CREATE FILE | 00/80 | E0 | 00=NDEF, 01=private, 02=KEY, 03=config, 06=TK | Create files (PERSONAL only) |
| VERIFY TK | 80 | 82 | P2=41 | Authenticate with transport key (8 bytes) |
| WRITE KEY | 80/84 | D4 | 00=update, 01..FF=add | Write/update key records (23 bytes) |
| PERSONAL END | 80 | F1 | — | Switch to NORMAL lifecycle |
| READ DATA | 00 | B0 | offset (P1P2) | Read file with dynamic NDEF processing |
| WRITE DATA | 00/04 | D6 | offset (P1P2) | Write NDEF/config files |
| GET CHALLENGE | 00 | 84 | — | Generate 16-byte random challenge |
| EXTERNAL AUTH | 00 | 82 | key index (P2) | Authenticate with DACK key (16 bytes) |
| PUT DATA | 80/84 | DA | 0000=full, else tag | Update config file |
| GET VERSION | 80 | CA | — | Return 3-byte version number |

### Key Features

**Lifecycle management:**
- PERSONAL (0x50): All files readable/writable, file creation allowed
- NORMAL (0x4E): Access controlled by file permissions, dynamic NDEF read

**Key file format (23 bytes per record):**
```
[0] keyType:    1=DACK, 2=WRITEK, 3=READK
[1] keyIndex:   0x00-0xFF
[2-3] RFU
[4] retryCount: high nibble=max, low nibble=current
[5] RFU
[6] postAuthState: security state after authentication
[7-22] keyData: 16 bytes (AES-128 key)
```

**Config file TLV format:**
```
TAG(2 bytes) + LEN(1 byte) + VALUE(LEN bytes)
```
| Tag | Length | Description |
|-----|--------|-------------|
| 0x0040 | 1 | READK key index |
| 0x0041 | 2 | KEY_ID location in URL |
| 0x0042 | 2 | User data offset in URL |
| 0x0043 | 2 | User data length (hex chars) |
| 0x0044 | 10 | Diversification factor (SN) |
| 0x0045 | 2 | SN location in URL |
| 0x0046 | 5 | Authorization code |
| 0x0047 | 2 | Dynamic ciphertext location |
| 0x0048 | 2 | MAC input data offset |
| 0x0049 | 2 | MAC location in URL |
| 0x004A | 5..16 | Optional extension: target applet instance AID for URL app data |
| 0x004B | 1 | Optional extension: Shareable interface parameter |
| 0x004C | 2 | Optional extension: app-data location in NDEF file |
| 0x004D | 2 | Optional extension: app-data field length in hex chars |

The app-data extension uses JavaCard Shareable Interface Objects. The target
applet must expose `org.openjavacard.ndef.tmc.TmcDataSource`; the TMC applet
calls `getTmcData(...)`, hex-encodes the returned bytes, and writes them into
the `__APPDATA__` URL field. JavaCard does not allow the TMC applet to directly
select another applet and read its private state.

**Dynamic NDEF read process (NORMAL mode):**
1. Copy NDEF template to APDU buffer
2. Replace KEY_ID hex at KEY_ID_LOCATION
3. Replace SN hex at SN_LOCATION
4. AES-ECB encrypt user data at USER_OFF → hex-encode back in-place
5. Optionally query `TmcDataSource` from the configured applet AID and hex-encode it at APPDATA_LOCATION
6. Generate COUNTER(3) + AUTH_CODE(5) + RANDOM(8) → AES-CBC encrypt → hex-encode at ENC_LOCATION
7. Compute CBC-MAC over the returned NDEF bytes from MAC_OFF to MAC_LOCATION with ISO 9797 method 2 padding → hex-encode 8 bytes at MAC_LOCATION
8. Increment 3-byte read counter
9. Return requested portion

**Crypto (AES-128 stand-in for SM4):**
- `Cipher.ALG_AES_BLOCK_128_ECB_NOPAD` → user ciphertext, key wrapping, auth
- `Cipher.ALG_AES_BLOCK_128_CBC_NOPAD` → dynamic ciphertext, CBC-MAC
- `KeyBuilder.TYPE_AES` / `LENGTH_AES_128` → 16-byte keys
- On SM4-capable hardware: switch to `ALG_SM4_ECB_NOPAD` / `ALG_SM4_CBC_NOPAD`

## Build System

**Project build.xml**: Added `build-tmc` target with ant-javacard

```
ant build-tmc
```

**Docker build**: `build-tmc-docker.sh` uses `cirne/javacard-great-again` image

```
./build-tmc-docker.sh
```

Build result: `tmc.cap` (8403 bytes, 0 errors, 0 warnings)

**Container SDK**: Java Card 2.2.2 Class File Converter v1.3, Java 8 with -source/-target 1.5

## Files Changed/Created

### New Files
| File | Description |
|------|-------------|
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/NdefApplet.java` | Main TMC applet implementation |
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/TmcDataSource.java` | Optional Shareable interface for URL app-data sources |
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/UtilTLV.java` | TLV utility class |
| `build-tmc-docker.sh` | Docker-based build script |
| `doc/tmc-personalization.md` | Personalization script example |

### Modified Files
| File | Change |
|------|--------|
| `build.xml` | Added `build-tmc` target, `build/classes/tmc` directory |
| `README.md` | Added TMC variant to table, TMC doc link |
| `doc/variants.md` | Added TMC variant description |
| `doc/protocol.md` | Added TMC-specific command documentation |

## Deployment

Using GlobalPlatformPro:

```bash
# Install
gp --install build-tmc-output/res/org/openjavacard/ndef/tmc/javacard/tmc.cap

# Personalization (see doc/tmc-personalization.md for full script)
# 1. Select applet:     00A4040008D276000085010101
# 2. Create KEY file:   00E0020008 00000A17FFFF0000
# 3. Write keys:        80D4010417 <key_record>
# 4. Create NDEF file:  00E0000008 E104030000000100
# 5. Write NDEF data:   00D60000 <data>
# 6. Create config:     00E0030008 E1050100FFFF0000
# 7. Write config TLV:  80DA0000 <tlv_data>
# 8. End personal:      80F1000000

# Normal read
# 1. Select applet:     00A4040008D276000085010101
# 2. Read NDEF:         00B0000200 (returns URL with ciphertext + MAC)
```
