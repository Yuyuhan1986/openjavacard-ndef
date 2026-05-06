## APDU Protocol

This applet implements only the exact minimum of APDU commands that the NDEF specification prescribes.
All variants implement the same subset, limited only in implemented features.

The TMC variant extends the protocol with proprietary commands for personalization and security.
These are documented separately below.

##### **SELECT (CLA=00 INS=A4 P1=00 P2=0C CDATA=fid)**

    P1=00 means "SELECT BY FILEID"
    P2=0C means "SELECT FIRST OR ONLY"
       (Other selection modes are not supported.)
    
    Command eturns SW=9000 when successful.

   Select a file in the applet.

   In exception to ISO7816 no FCI (file control information) will be returned.
   This is permitted by NDEF specification requirement RQ_T4T_NDA_034.

   There are two files on the card:

     0xE103 - NDEF capabilities
     0xE104 - NDEF data

##### **READ BINARY (CLA=00 INS=B0 P12=offset RDATA=output)**

    P12 specifies the offset into the file and must be valid.
    
    Command returns SW=9000 when successful.

   Read data from the selected file.

   Length of RDATA is variable and depends on available resources, the protocol in use as well as the file size.
   As much data as possible will be returned.

##### **UPDATE BINARY (CLA=00 INS=D6 P12=offset CDATA=data)**

    P12 specifies the offset into the file and must be valid.
    
    Command returns SW=9000 when successful.

   Update data in the selected file.

   Allowable length of data depends on the build-time parameter NDEF_WRITE_SIZE (default is 128 bytes).

### TMC-specific Commands

The following commands are only available in the TMC variant. They implement the
UNIS TMC 4.0.0 T4T security specification.

##### **CREATE FILE (INS=E0)**

Create files during PERSONAL phase.

    P1=00   NDEF file
    P1=01   Private file
    P1=02   KEY file
    P1=03   Config file
    P1=06   Update transport key

    Data format (P1=00/01/02/03):
      FID(2) + Size(2) + ACR(1) + ACW(1) + SM(1) + RFU(1)

    Data format for KEY file Size:
      First byte = number of key records
      Second byte = record length (always 0x17 = 23)

##### **VERIFY TK (INS=82 CLA=80 P2=41)**

Verify transport key during personalization. Requires prior GET CHALLENGE.

    Data: 8 bytes of authentication data (first half of encrypted challenge)

##### **WRITE KEY (INS=D4)**

Write or update key records in the KEY file.

    P1=00      Update by key type+index
    P1=01..FF  Add key record at specific position

    Plaintext (PERSONAL, CLA=80):  Data = 23-byte key record
    Secure (NORMAL, CLA=84):        Data = ciphertext(16) + MAC(8)

##### **PERSONAL END (INS=F1)**

Switch lifecycle from PERSONAL to NORMAL.

##### **GET CHALLENGE (INS=84)**

Get 16-byte random number for authentication protocols.

##### **EXTERNAL AUTHENTICATE (INS=82 CLA=00)**

Authenticate host using DACK key. Requires prior GET CHALLENGE.

    P2 = key index
    Data = 16 bytes of encrypted challenge

##### **PUT DATA (INS=DA)**

Update configuration file data.

    P1P2 = 0000: Full update
    P1P2 = other: Tag-specific update

    PERSONAL (CLA=80): Plaintext TLV data
    NORMAL (CLA=84):   Encrypted TLV data + MAC(8)

##### **GET VERSION (INS=CA)**

Return 3-byte version number (major.minor.patch).
