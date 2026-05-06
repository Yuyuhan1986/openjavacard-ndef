TMC T4T Personalization Script Example
=========================================

This script follows the format from the UNIS TMC 4.0.0 specification
(Annex 1). It creates a secure NDEF tag with key management, config
file, and dynamic ciphertext generation.

Prerequisites:
  - GlobalPlatformPro or similar GP tool
  - TMC-capable JavaCard (or development card with AES support)

Interactive wizard
-------------------

The repository now includes an interactive wizard:

```bash
./tmc-personalize.sh
```

Use it inside the `local/javacard-dev:latest` container with host PC/SC mounted:

```bash
docker run -it --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh'
```

On this local host, if Docker socket access is not configured for the current
user, use `sudo docker`:

```bash
cd /home/richard-qiu/openjavacard-ndef

sudo docker run -it --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh'
```

For noninteractive personalization with the demo profile:

```bash
cd /home/richard-qiu/openjavacard-ndef

sudo docker run --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh --profile /workspace/tmc-demo.profile --noninteractive'
```

If `docker` can be used without `sudo`, remove `sudo` from the commands. If
Docker reports permission denied on `/var/run/docker.sock`, use `sudo` or add
the user to the `docker` group and re-login.

What it does:

- asks for reader, CAP path, card GP key, and TMC keys
- lets the operator use the built-in dynamic URL template or enter a custom one
- computes all TMC config offsets automatically from placeholder tokens
- builds a correct NDEF payload with NLEN
- chunks long `UPDATE BINARY` writes to respect the applet write limit
- personalizes the card in one `gp` APDU session
- runs a readback test at the end

Template tokens expected by the wizard:

- `__KEYID__`
- `__SN__`
- `__ENC__`
- `__USER__`
- `__MAC__`

Install the applet
-------------------

# Select ISD
RESET
apduf 00A4040000
assert x 9000

# Delete existing T4T package (if present)
apduf 80E40000094F07D2760000850101
assert x 9000  # may fail if not installed, that's OK

# Install TMC T4T applet
apduf 80E60C001B 05A011223344 06A01122334401 07D2760000850101010002C9000000
assert x 9000

Create KEY file
----------------

RESET
apduf 00A4040008D276000085010101
assert 9000

# KEY file: FID=0000, size=0A17 (10 records x 23 bytes),
#           ACR=FF (no read), ACW=FF (no write), SM=00
apduf 00E0020008 00000A17FFFF0000
assert 9000

Write keys (plaintext, PERSONAL phase)
----------------------------------------

# DACK_0 (index 00): key type=01, index=00, max retry=3, post-auth state=0F
# Key data: 11111111111111111111111111111111
apduf 80D4010417 0100000033000F 11111111111111111111111111111111
assert 9000

# WRITEK_0 (index 00): key type=02, index=00, max retry=3, post-auth state=00
# Key data: 33333333333333333333333333333333
apduf 80D4010417 02000000330000 33333333333333333333333333333333
assert 9000

# READK_0 (index 00): key type=03, index=00, max retry=3, post-auth state=00
# Key data: 88888888888888888888888888888888
apduf 80D4010417 03000000330000 88888888888888888888888888888888
assert 9000

Create NDEF file
-----------------

# NDEF file: FID=E104, size=0x0300, ACR=00, ACW=00, SM=01 (plaintext+MAC)
apduf 00E0000008 E104030000000100
assert 9000

Write NDEF data
----------------

# NDEF message containing URI record with placeholders for KEY_ID, SN,
# dynamic ciphertext (?e=), user ciphertext (&d=), and MAC (&c=).
# URL template:
#   https://example.com/V01/00/FF/11111111111111111111
#   ?e=00000000000000000000000000000000
#   &d=00000000000000000000000000000000000000000000000000000000
#   &c=1111111111111111
apduf 00D60000DF
00DDD101D9550068747470733A2F2F6578616D706C652E636F6D2F5630312F30
302F46462F31313131313131313131313131313131313131313F653D30303030
3030303030303030303030303030303030303030303030303030303026643D3030
3030303030303030303030303030303030303030303030303030303030303030303
0303030303030303030303030303030303030303030303030303030303030303030
303030303030303030303030303030303030303030303026633D31313131313131
3131313131313131
assert 9000

Create config file
-------------------

# Config file: FID=E105, size=0x0100, ACR=FF, ACW=FF, SM=00
apduf 00E0030008 E1050100FFFF0000
assert 9000

Write config data (TLV format)
-------------------------------

# Tag 0x0040: READK key index = 00 (1 byte)
# Offsets below are relative to the full NDEF file bytes, not the URL substring.
# They include the 2-byte NLEN and the NDEF URI record header.
# For the current short URI example, the URL base offset is 0x0007.
# Tag 0x0041: KEY_ID location in NDEF file = 0x0022 (2 bytes)
# Tag 0x0042: User data offset in NDEF file = 0x005F (2 bytes)
# Tag 0x0043: User data length = 0x0020 (32 hex chars = 16 bytes raw, 1 block) (2 bytes)
# Tag 0x0044: SN (diversification factor) = 10 bytes
# Tag 0x0045: SN location in NDEF file = 0x0025 (2 bytes)
# Tag 0x0046: Auth code = 5 bytes
# Tag 0x0047: Dynamic ciphertext location in NDEF file = 0x003C (2 bytes)
# Tag 0x0048: MAC input data offset = 0x0000 (2 bytes)
# Tag 0x0049: MAC location in NDEF file = 0x0082 (2 bytes)

apduf 80DA0000
3C   # total length
00400100                # READK_ID = 00
0041020022              # KEY_ID location
004202005F              # USER_OFF
0043020020              # USER_LEN
00440A 22222222222222222222  # SN
0045020025              # SN location
004605 1122334455       # Auth code
004702003C              # ENC location
0048020000              # MAC_OFF
0049020082              # MAC location
assert 9000

End personalization
--------------------

apduf 80F1000000
assert 9000

=== NORMAL phase operations ===

Read NDEF (will now return dynamically generated data)
-------------------------------------------------------

RESET
apduf 00A4040008D276000085010101
assert 9000

# Select CC file
apduf 00A4000C02E103
assert 9000

# Read CC
apduf 00B000000F
assert 9000

# Select NDEF file
apduf 00A4000C02E104
assert 9000

# Read NDEF length
apduf 00B0000002
assert 9000

# Read NDEF data (returns URL with ciphertext and MAC)
apduf 00B0000200
assert 9000

Validated Local Workflow
-------------------------

The following flow was validated on this host on May 6, 2026 with:

- Reader: `ACS ACR1281U 00 01`
- Card ATR: `3B 85 80 01 80 73 C8 21 10 0E`
- Card family identified by `pcsc_scan`: `NXP P71 / JCOP4`
- Docker image: `local/javacard-dev:latest`

Important local findings:

- Use the applet instance AID `D276000085010101` for `SELECT`.
- Do not use the package AID `D2760000850101` as the applet select AID.
- On this host, one `gp` invocation with repeated `-a` APDUs is more stable than
  `opensc-tool` for multi-step stateful validation.
- If `gp --install` fails with `0x6985`, the reliable recovery path on this card is:
  1. `gp --load /workspace/build/javacard/openjavacard-ndef-tmc.cap`
  2. `gp --cap /workspace/build/javacard/openjavacard-ndef-tmc.cap --create D276000085010101`

Final verification results:

- `SELECT D276000085010101` -> `9000`
- `GET VERSION (00CA000003)` -> `01 00 00 9000`
- `SELECT E103` -> `9000`
- `READ E103` -> `00 0F 20 00 80 00 80 04 06 E1 04 03 00 00 00 9000`
- `SELECT E104` -> `9000`
- `READ NLEN` -> `00 90 9000`

Validated dynamic field offsets in the full NDEF file:

- `KEY_ID`: `0x0022`
- `SN`: `0x0025`
- `ENC`: `0x003C`
- `USER`: `0x005F`
- `MAC`: `0x0082`

Validated dynamic behavior:

- `SN` remains fixed as configured.
- `ENC` changes across separate sessions.
- `MAC` changes across separate sessions.
- Re-reading the same dynamic block within one selected-file session returns the
  same bytes, confirming the applet cache is working as intended.

Recommended final verification command:

```bash
docker run --rm --user root \
  -v /run/pcscd:/run/pcscd \
  local/javacard-dev:latest \
  bash -lc '
    gp --reader "ACS ACR1281U 00 01" \
      -a 00A4040008D276000085010101 \
      -a 00CA000003 \
      -a 00A4000C02E104 \
      -a 00B0000002 \
      -a 00B0000210 \
      -a 00B0001210 \
      -a 00B0002210 \
      -a 00B0003210 \
      -a 00B0004210 \
      -a 00B0005210 \
      -a 00B0006210 \
      -a 00B0007210 \
      -a 00B0008210 \
      -a 00B0003210
  '
```
