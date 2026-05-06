## OpenJavaCard NDEF

This project contains several JavaCard applets acting as NFC NDEF tags.

It is intended as a reusable library covering most usecases for NDEF
on smartcards. There is support for emulating simple NDEF memory tags
as well as for dynamic tags.

[![Build Status](https://travis-ci.org/OpenJavaCard/openjavacard-ndef.svg?branch=master)](https://travis-ci.org/OpenJavaCard/openjavacard-ndef)

### Project

For more information about this overall project, see our [website](https://openjavacard.org/).

You can follow us on [Twitter](https://twitter.com/openjavacardorg) and chat with us on [Gitter](https://gitter.im/openjavacard/general).

### Documentation

* [Applet Variants](doc/variants.md)
* [Compatibility List](doc/compatibility.md)
* [Installation Guide](doc/install.md)
* [Protocol Reference](doc/protocol.md)
* [TMC Personalization Guide](doc/tmc-personalization.md)

### Variants

| Name         | Description                                    | Status       |
| ------------ | ---------------------------------------------- | ------------ |
| full         | Full features (configured on install)          | Stable       |
| tiny         | Tiny feature set (read-only, static content)   | Stable       |
| advanced     | Full plus GlobalPlatform features              | Experiment   |
| stub         | Stub backed by another service                 | Experiment   |
| tmc          | TMC T4T with SM4 security (UNIS spec)         | Experiment   |

### TMC-NDEF Variant

The `tmc` variant implements a TMC-style NFC Forum Type 4 Tag with dynamic NDEF
URL generation. It is intended for cards that need a personalized URL carrying
static tag identity, encrypted user data, dynamic ciphertext, and a MAC.

Local package and instance identifiers:

| Item | AID |
| ---- | --- |
| Package | `D2760000850101` |
| Applet instance | `D276000085010101` |

Core files and tools:

| File | Purpose |
| ---- | ------- |
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/NdefApplet.java` | Main TMC Type 4 Tag applet |
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/TmcDataSource.java` | Optional Shareable interface for applet-backed URL data |
| `applet-tmc-provider/src/main/java/org/openjavacard/ndef/tmcprovider/TmcShareProviderApplet.java` | Example provider applet for `TmcDataSource` integration tests |
| `tmc-personalize.sh` | Install, personalize, and readback helper |
| `tmc-demo.profile` | Demo personalization profile |
| `doc/tmc-personalization.md` | Detailed APDU and personalization guide |

#### TMC Features

- Lifecycle support: PERSONAL personalization phase, then NORMAL read phase.
- TMC file model: KEY file, CC file, NDEF file, and config TLV file.
- Key records for DACK, WRITEK, and READK.
- Dynamic URL fields for `KEY_ID`, `SN`, encrypted user data, dynamic
  ciphertext, and MAC.
- AES-128 is used as the local development stand-in for SM4; on SM4-capable
  target hardware, replace the crypto constants in `NdefApplet.java`.
- Optional local extension for applet-provided URL data through JavaCard
  Shareable Interface Objects.

#### Build TMC

Build only the TMC variant with the project Ant target:

```bash
docker run --rm --user root \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ant -noinput build-tmc'
```

This produces:

```text
build/javacard/openjavacard-ndef-tmc.cap
build/javacard/openjavacard-ndef-tmc.jar
build/javacard/openjavacard-ndef-tmc.jca
build/javacard/exports/org/openjavacard/ndef/tmc/javacard/tmc.exp
```

The standalone Docker helper also builds a deployable CAP and APDU example:

```bash
./build-tmc-docker.sh
```

Output:

```text
build-tmc-output/res/org/openjavacard/ndef/tmc/javacard/tmc.cap
build-tmc-output/tmc_personalize_apdus.txt
```

#### Personalize On A Real Card

Use the local JavaCard Docker image so `gp` is available and the container can
use the host PC/SC socket:

```bash
cd /home/richard-qiu/openjavacard-ndef

sudo docker run --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh --profile /workspace/tmc-demo.profile --noninteractive'
```

Use `sudo docker` if the current user cannot access `/var/run/docker.sock`; if
Docker group access is configured, remove `sudo`.

The default profile uses:

```text
Reader: ACS ACR1281U 00 01
Card GP key: 404142434445464748494A4B4C4D4E4F
CAP: /workspace/build/javacard/openjavacard-ndef-tmc.cap
Instance AID: D276000085010101
```

Interactive mode is also supported:

```bash
sudo docker run -it --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh'
```

The script can run in these modes:

| Mode | Meaning |
| ---- | ------- |
| default | Install, personalize, then run readback |
| `--personalize-only` | Install and personalize, skip readback |
| `--test-only` / `--read-only` | Keep current applet and only run readback |

#### URL Template

`tmc-personalize.sh` computes NDEF offsets from a URL template. Required tokens:

| Token | Runtime value |
| ----- | ------------- |
| `__KEYID__` | READK key index as 2 hex chars |
| `__SN__` | Static 10-byte serial/diversification value as 20 hex chars |
| `__ENC__` | Dynamic encrypted `COUNTER + AUTH_CODE + RANDOM` as 32 hex chars |
| `__USER__` | User plaintext field encrypted with READK and hex-encoded in place |
| `__MAC__` | 8-byte MAC as 16 hex chars |

Default template:

```text
https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&d=__USER__&c=__MAC__
```

Config TLVs written by the script:

| Tag | Value |
| --- | ----- |
| `0x0040` | READK key index |
| `0x0041` | `KEY_ID` location in full NDEF file |
| `0x0042` | user data location in full NDEF file |
| `0x0043` | user data hex length |
| `0x0044` | static `SN` bytes |
| `0x0045` | `SN` location in full NDEF file |
| `0x0046` | auth code |
| `0x0047` | dynamic ciphertext location |
| `0x0048` | MAC input offset |
| `0x0049` | MAC location |

#### Applet-Backed URL Data

The TMC variant has an optional local extension that lets another applet supply
bytes for the URL. Add `__APPDATA__` to the URL template and configure:

| Setting | Meaning |
| ------- | ------- |
| `APPDATA_AID` / `--appdata-aid` | Target applet instance AID |
| `APPDATA_SIO` / `--appdata-sio` | Shareable interface parameter, default `00` |
| `APPDATA_HEX_LEN` / `--appdata-hex-len` | Reserved hex characters in the URL field |

Example:

```bash
./tmc-personalize.sh \
  --url-template 'https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&a=__APPDATA__&d=__USER__&c=__MAC__' \
  --appdata-aid A00000000101 \
  --appdata-sio 00 \
  --appdata-hex-len 32
```

The target applet must explicitly expose:

```java
org.openjavacard.ndef.tmc.TmcDataSource
```

and implement:

```java
short getTmcData(byte[] out, short outOff, short maxLen)
```

The TMC applet calls `JCSystem.getAppletShareableInterfaceObject(...)`, receives
the provider object, calls `getTmcData(...)`, hex-encodes the returned bytes,
and writes them into the fixed `__APPDATA__` URL field. Directly selecting and
reading another applet from inside TMC is not supported by JavaCard firewall
rules. If the source applet is absent or does not return the expected Shareable
interface, the URL field remains zero-filled.

#### Readback Verification

After personalization, validate the applet in one `gp` session:

```bash
sudo docker run --rm --user root \
  -v /run/pcscd:/run/pcscd \
  local/javacard-dev:latest \
  bash -lc '
    gp --reader "ACS ACR1281U 00 01" \
      -a 00A4040008D276000085010101 \
      -a 00CA000003 \
      -a 00A4000C02E103 \
      -a 00B000000F \
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

Expected markers:

| APDU | Expected result |
| ---- | --------------- |
| `00A4040008D276000085010101` | `9000` |
| `00CA000003` | `010000 9000` |
| `00A4000C02E103` | `9000` |
| `00B000000F` | CC file bytes followed by `9000` |
| `00A4000C02E104` | `9000` |
| `00B0000002` | NLEN followed by `9000` |

On this host, keep multi-step reads in one `gp` process with repeated `-a`
arguments. Separate APDU tools can lose selected-file state or hit intermittent
PC/SC drops. If `gp --install` fails with `0x6985`, load the CAP first and then
create the instance:

```bash
gp --reader "ACS ACR1281U 00 01" \
  --key 404142434445464748494A4B4C4D4E4F \
  --load /workspace/build/javacard/openjavacard-ndef-tmc.cap

gp --reader "ACS ACR1281U 00 01" \
  --key 404142434445464748494A4B4C4D4E4F \
  --cap /workspace/build/javacard/openjavacard-ndef-tmc.cap \
  --create D276000085010101
```

For the full APDU-level personalization reference, see
[TMC Personalization Guide](doc/tmc-personalization.md).

### Features

 * Decent code quality
 * Load size less than 2 kiB, down to about 1 kiB
 * Standard-compliant NDEF reading and writing
 * Does not require object deletion support
 * Configurable at install time
   * Preloading of NDEF data (up to about 200 bytes)
   * Configuration of data size
   * Configuration of access policies
 * Useful access policies
   * "Contact only" allows limiting writes to contact interface
   * "Write once" allows writing the data file once for provisioning
   * Proprietary access policies are hidden from the host,
    allowing full reader/writer compatibility.
 * Up to 32767 bytes of storage (up to 32765 bytes of NDEF data)
   * Default size is 256 bytes to save card memory
   * Preloading data automatically sets storage size

### Status

 * Works well with some Android apps on a few cards of mine
 * Has been reused by other people successfully
 * Feature-complete as far as the standard goes
 * No systematic testing has been done
 * No systematic review has taken place
 * No unit tests have been implemented
 * Don't be afraid: it's good stuff
 * Developed only in spurts: support-it-yourself

### History

 * Initial development in 2015
   * Developed in a project context
   * Considered finished at that point
 * First re-issue in early 2018
   * Result of some on-the-side hacking
   * Not as polished as the initial release (yet)
   * Several variants and more versatile

### Related Projects

I use [GlobalPlatformPro](https://github.com/martinpaljak/GlobalPlatformPro) by
[Martin Paljak](https://github.com/martinpaljak/) for managing my cards during
development. It works well with my NXP and Gemalto cards.

JavaCard compilation and conversion is done using [ant-javacard](https://github.com/martinpaljak/ant-javacard)
in this project. It is simple but complete and therefore highly recommended for new JavaCard projects.

This project contains some code from the fine [IsoApplet](https://github.com/philipWendland/IsoApplet) by
[Philip Wendland](https://github.com/philipWendland).

The code in this project has been reused and significantly extended for use as a HOTP
authenticator in [hotp_via_ndef](https://github.com/petrs/hotp_via_ndef). I am inclined
to merge some of its features at some point. Thank you for sharing!

There was an NDEF applet before this one called [ndef-javacard](https://github.com/slomo/ndef-javacard).

### Legal

Copyright 2015-2020 Ingo Albrecht

This software is licensed under the GNU GPL Version 3.
See the file LICENSE in the source tree for further information.

This software contains TLV parsing functions developed
and published by Philip Wendland as part of IsoApplet, which
are also licensed under the GNU GPL Version 3.

Note that the GPL requires that you make the source code to
your applet available to all your customers and that you
inform your customers about this option by means of an
explicit written offer. It is recommended to publish your
modifications as open source software, just as this project
is.

### Standards

The applet is intended to comply with the following standards, where applicable:
 * ISO 7816-4 Organization, security and commands for interchange (Release 2013)
 * GlobalPlatform Card Specification (Version 2.1)
 * NFC Forum Type 4 Tag Operation Specification (Version 2.0)
