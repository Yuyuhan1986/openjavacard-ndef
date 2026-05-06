#!/bin/bash
# TMC T4T dynamic URL personalization wizard

set -euo pipefail

DEFAULT_READER="ACS ACR1281U 00 01"
DEFAULT_KEY="404142434445464748494A4B4C4D4E4F"
DEFAULT_CAP="./build/javacard/openjavacard-ndef-tmc.cap"
TMC_PACKAGE_AID="D2760000850101"
TMC_INSTANCE_AID="D276000085010101"
DEFAULT_DACK="11111111111111111111111111111111"
DEFAULT_WRITEK="33333333333333333333333333333333"
DEFAULT_READK="88888888888888888888888888888888"
DEFAULT_SN="22222222222222222222"
DEFAULT_AUTH="1122334455"
DEFAULT_FILE_SIZE_HEX="0300"
DEFAULT_USER_HEX="00000000000000000000000000000000"
DEFAULT_URL_TEMPLATE="https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&d=__USER__&c=__MAC__"
DEFAULT_MODE="full"

READER="$DEFAULT_READER"
CARD_KEY="$DEFAULT_KEY"
CAP_FILE="$DEFAULT_CAP"
DACK="$DEFAULT_DACK"
WRITEK="$DEFAULT_WRITEK"
READK="$DEFAULT_READK"
SN_HEX="$DEFAULT_SN"
AUTH_HEX="$DEFAULT_AUTH"
FILE_SIZE_HEX="$DEFAULT_FILE_SIZE_HEX"
USER_HEX="$DEFAULT_USER_HEX"
URL_TEMPLATE="$DEFAULT_URL_TEMPLATE"
PROFILE_PATH=""
SAVE_PROFILE=""
MODE="$DEFAULT_MODE"
NONINTERACTIVE=0
MODE_FROM_ARGS=0

PLAN_JSON=""
URL=""
NDEF_HEX=""
NDEF_BYTES=""
NLEN=""
RECORD_MODE=""
URL_BASE_OFFSET=""
KEY_LOC=""
SN_LOC=""
USER_LOC=""
USER_HEX_LEN=""
ENC_LOC=""
MAC_LOC=""
CONFIG_HEX=""
CONFIG_BYTES=""
APDU_FILE=""

usage() {
  cat <<'EOF'
Usage:
  ./tmc-personalize.sh [options]

Modes:
  default               Interactive full flow: install + personalize + test
  --personalize-only    Install + personalize, skip final readback test
  --test-only           Keep current applet, only run final readback test
  --read-only           Alias of --test-only

Profiles:
  --profile FILE        Load values from a shell-style profile file
  --save-profile FILE   Save current values after prompts / overrides

Noninteractive:
  --noninteractive      Do not prompt; require values from defaults/profile/flags

Overrides:
  --reader NAME
  --card-key HEX
  --cap FILE
  --dack HEX
  --writek HEX
  --readk HEX
  --sn HEX
  --auth HEX
  --file-size HEX
  --user-hex HEX
  --url-template TEXT
EOF
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing command: $1" >&2
    exit 1
  fi
}

read_with_default() {
  local prompt="$1"
  local def="$2"
  local value
  read -r -p "$prompt [$def]: " value
  if [ -z "$value" ]; then
    printf '%s\n' "$def"
  else
    printf '%s\n' "$value"
  fi
}

load_profile() {
  local path="$1"
  local mode_override="$MODE"
  if [ ! -f "$path" ]; then
    echo "profile not found: $path" >&2
    exit 1
  fi
  # shellcheck disable=SC1090
  . "$path"
  READER="${READER:-$DEFAULT_READER}"
  CARD_KEY="${CARD_KEY:-$DEFAULT_KEY}"
  CAP_FILE="${CAP_FILE:-$DEFAULT_CAP}"
  DACK="${DACK:-$DEFAULT_DACK}"
  WRITEK="${WRITEK:-$DEFAULT_WRITEK}"
  READK="${READK:-$DEFAULT_READK}"
  SN_HEX="${SN_HEX:-$DEFAULT_SN}"
  AUTH_HEX="${AUTH_HEX:-$DEFAULT_AUTH}"
  FILE_SIZE_HEX="${FILE_SIZE_HEX:-$DEFAULT_FILE_SIZE_HEX}"
  USER_HEX="${USER_HEX:-$DEFAULT_USER_HEX}"
  URL_TEMPLATE="${URL_TEMPLATE:-$DEFAULT_URL_TEMPLATE}"
  if [ "$MODE_FROM_ARGS" -eq 0 ]; then
    MODE="${MODE:-$DEFAULT_MODE}"
  else
    MODE="$mode_override"
  fi
}

save_profile() {
  local path="$1"
  cat > "$path" <<EOF
READER='$READER'
CARD_KEY='$CARD_KEY'
CAP_FILE='$CAP_FILE'
DACK='$DACK'
WRITEK='$WRITEK'
READK='$READK'
SN_HEX='$SN_HEX'
AUTH_HEX='$AUTH_HEX'
FILE_SIZE_HEX='$FILE_SIZE_HEX'
USER_HEX='$USER_HEX'
URL_TEMPLATE='$URL_TEMPLATE'
MODE='$MODE'
EOF
}

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --profile)
        PROFILE_PATH="${2:?missing value for --profile}"
        shift 2
        ;;
      --save-profile)
        SAVE_PROFILE="${2:?missing value for --save-profile}"
        shift 2
        ;;
      --personalize-only)
        MODE="personalize-only"
        MODE_FROM_ARGS=1
        shift
        ;;
      --test-only|--read-only)
        MODE="test-only"
        MODE_FROM_ARGS=1
        shift
        ;;
      --noninteractive)
        NONINTERACTIVE=1
        shift
        ;;
      --reader)
        READER="${2:?missing value for --reader}"
        shift 2
        ;;
      --card-key)
        CARD_KEY="${2:?missing value for --card-key}"
        shift 2
        ;;
      --cap)
        CAP_FILE="${2:?missing value for --cap}"
        shift 2
        ;;
      --dack)
        DACK="${2:?missing value for --dack}"
        shift 2
        ;;
      --writek)
        WRITEK="${2:?missing value for --writek}"
        shift 2
        ;;
      --readk)
        READK="${2:?missing value for --readk}"
        shift 2
        ;;
      --sn)
        SN_HEX="${2:?missing value for --sn}"
        shift 2
        ;;
      --auth)
        AUTH_HEX="${2:?missing value for --auth}"
        shift 2
        ;;
      --file-size)
        FILE_SIZE_HEX="${2:?missing value for --file-size}"
        shift 2
        ;;
      --user-hex)
        USER_HEX="${2:?missing value for --user-hex}"
        shift 2
        ;;
      --url-template)
        URL_TEMPLATE="${2:?missing value for --url-template}"
        shift 2
        ;;
      *)
        echo "unknown option: $1" >&2
        usage >&2
        exit 1
        ;;
    esac
  done
}

interactive_collect() {
  READER="$(read_with_default "Reader" "$READER")"
  CARD_KEY="$(read_with_default "Card GP key" "$CARD_KEY")"
  CAP_FILE="$(read_with_default "CAP file" "$CAP_FILE")"
  DACK="$(read_with_default "DACK key (16-byte hex)" "$DACK")"
  WRITEK="$(read_with_default "WRITEK key (16-byte hex)" "$WRITEK")"
  READK="$(read_with_default "READK key (16-byte hex)" "$READK")"
  SN_HEX="$(read_with_default "SN diversification factor (10-byte hex)" "$SN_HEX")"
  AUTH_HEX="$(read_with_default "Auth code (5-byte hex)" "$AUTH_HEX")"
  FILE_SIZE_HEX="$(read_with_default "NDEF file size hex" "$FILE_SIZE_HEX")"
  USER_HEX="$(read_with_default "User plaintext field hex (must be 16-byte aligned)" "$USER_HEX")"

  echo
  echo "Dynamic URL template rules:"
  echo "  use __KEYID__   for 2 hex chars"
  echo "  use __SN__      for 20 hex chars"
  echo "  use __ENC__     for 32 hex chars"
  echo "  use __USER__    for your user plaintext hex"
  echo "  use __MAC__     for 16 hex chars"
  echo

  if [ "$URL_TEMPLATE" = "$DEFAULT_URL_TEMPLATE" ]; then
    read -r -p "Use built-in URL template? [Y/n]: " use_default_template
    use_default_template="${use_default_template:-Y}"
    if [[ ! "$use_default_template" =~ ^[Yy]$ ]]; then
      echo "Enter URL template in one line:"
      read -r URL_TEMPLATE
    fi
  else
    read -r -p "Keep current URL template? [Y/n]: " keep_template
    keep_template="${keep_template:-Y}"
    if [[ ! "$keep_template" =~ ^[Yy]$ ]]; then
      echo "Enter URL template in one line:"
      read -r URL_TEMPLATE
    fi
  fi
}

compute_plan() {
  PLAN_JSON="$(python3 - "$URL_TEMPLATE" "$USER_HEX" "$SN_HEX" "$AUTH_HEX" <<'PY'
import json
import re
import sys

template = sys.argv[1]
user_hex = sys.argv[2].upper()
sn_hex = sys.argv[3].upper()
auth_hex = sys.argv[4].upper()

replacements = {
    "__KEYID__": "FF",
    "__SN__": "1" * 20,
    "__ENC__": "0" * 32,
    "__USER__": user_hex,
    "__MAC__": "1" * 16,
}

for token in replacements:
    if token not in template:
        raise SystemExit(f"missing token: {token}")
    if template.count(token) != 1:
        raise SystemExit(f"token must appear exactly once: {token}")

parts = []
offsets = {}
cursor = 0
for match in re.finditer(r"__KEYID__|__SN__|__ENC__|__USER__|__MAC__", template):
    literal = template[cursor:match.start()]
    parts.append(literal)
    rendered_len = sum(len(part) for part in parts)
    token = match.group(0)
    offsets[token] = rendered_len
    parts.append(replacements[token])
    cursor = match.end()
parts.append(template[cursor:])
url = "".join(parts)

uri_bytes = url.encode("ascii")
payload_len = len(uri_bytes) + 1
if payload_len <= 0xFF:
    record_mode = "short"
    payload = bytes([0xD1, 0x01, payload_len, 0x55, 0x00]) + uri_bytes
else:
    record_mode = "long"
    payload = bytes([0xC1, 0x01]) + payload_len.to_bytes(4, "big") + bytes([0x55, 0x00]) + uri_bytes

nlen = len(payload)
ndef_bytes = nlen.to_bytes(2, "big") + payload
ndef_hex = ndef_bytes.hex().upper()
url_base_offset = len(ndef_bytes) - len(uri_bytes)

key_loc = url_base_offset + offsets["__KEYID__"]
sn_loc = url_base_offset + offsets["__SN__"]
enc_loc = url_base_offset + offsets["__ENC__"]
user_loc = url_base_offset + offsets["__USER__"]
mac_loc = url_base_offset + offsets["__MAC__"]

if len(user_hex) % 2:
    raise SystemExit("user hex must have even length")
if (len(user_hex) // 2) % 16:
    raise SystemExit("user hex raw length must be a multiple of 16 bytes")
if len(sn_hex) != 20:
    raise SystemExit("SN must be 10 bytes / 20 hex chars")
if len(auth_hex) != 10:
    raise SystemExit("Auth code must be 5 bytes / 10 hex chars")

entries = [
    (0x0040, bytes.fromhex("00")),
    (0x0041, key_loc.to_bytes(2, "big")),
    (0x0042, user_loc.to_bytes(2, "big")),
    (0x0043, (len(user_hex)).to_bytes(2, "big")),
    (0x0044, bytes.fromhex(sn_hex)),
    (0x0045, sn_loc.to_bytes(2, "big")),
    (0x0046, bytes.fromhex(auth_hex)),
    (0x0047, enc_loc.to_bytes(2, "big")),
    (0x0048, (0).to_bytes(2, "big")),
    (0x0049, mac_loc.to_bytes(2, "big")),
]

config = bytearray()
for tag, value in entries:
    config += tag.to_bytes(2, "big")
    config.append(len(value))
    config += value

print(json.dumps({
    "url": url,
    "ndef_hex": ndef_hex,
    "ndef_bytes": len(ndef_bytes),
    "nlen": nlen,
    "record_mode": record_mode,
    "url_base_offset": url_base_offset,
    "key_loc": key_loc,
    "sn_loc": sn_loc,
    "user_loc": user_loc,
    "user_hex_len": len(user_hex),
    "enc_loc": enc_loc,
    "mac_loc": mac_loc,
    "config_hex": config.hex().upper(),
    "config_bytes": len(config),
}))
PY
)"

  eval "$(python3 - "$PLAN_JSON" <<'PY'
import json
import shlex
import sys

data = json.loads(sys.argv[1])
for key in [
    "url", "ndef_hex", "ndef_bytes", "nlen", "record_mode", "url_base_offset",
    "key_loc", "sn_loc",
    "user_loc", "user_hex_len", "enc_loc", "mac_loc",
    "config_hex", "config_bytes"
]:
    print(f"{key.upper()}={shlex.quote(str(data[key]))}")
PY
)"
}

print_plan() {
  echo
  echo "Template preview:"
  echo "$URL_TEMPLATE"
  echo
  echo "Computed personalization plan:"
  echo "  URL: $URL"
  echo "  Record mode: $RECORD_MODE"
  echo "  NLEN: $NLEN"
  echo "  NDEF bytes: $NDEF_BYTES"
  echo "  URL base offset in NDEF file: $URL_BASE_OFFSET"
  echo "  KEY_ID offset: $KEY_LOC"
  echo "  SN offset: $SN_LOC"
  echo "  USER offset: $USER_LOC"
  echo "  USER hex length: $USER_HEX_LEN"
  echo "  ENC offset: $ENC_LOC"
  echo "  MAC offset: $MAC_LOC"
  echo "  Config bytes: $CONFIG_BYTES"
  echo
}

validate_inputs() {
  if [ ! -f "$CAP_FILE" ] && [ "$MODE" != "test-only" ]; then
    echo "CAP not found: $CAP_FILE" >&2
    exit 1
  fi
  local file_size_dec=$((16#$FILE_SIZE_HEX))
  if [ "$file_size_dec" -lt "$NDEF_BYTES" ]; then
    echo "NDEF file size $FILE_SIZE_HEX too small for payload ($NDEF_BYTES bytes)" >&2
    exit 1
  fi
}

build_apdu_file() {
  local path="$1"
  python3 - "$path" "$MODE" "$NDEF_HEX" "$NDEF_BYTES" "$CONFIG_HEX" "$DACK" "$WRITEK" "$READK" "$FILE_SIZE_HEX" "$TMC_INSTANCE_AID" <<'PY'
import sys

path, mode, ndef_hex, ndef_bytes, config_hex, dack, writek, readk, file_size_hex, instance_aid = sys.argv[1:]
file_size_hex = file_size_hex.upper().zfill(4)
ndef_bytes = int(ndef_bytes)
instance_select = f"00A40400{len(instance_aid)//2:02X}{instance_aid}"

def chunk_apdus(offset, hexdata, block=128):
    apdus = []
    for i in range(0, len(hexdata), block * 2):
        chunk = hexdata[i:i + block * 2]
        chunk_off = offset + i // 2
        apdus.append(f"00D6{chunk_off:04X}{len(chunk)//2:02X}{chunk}")
    return apdus

apdus = []
if mode != "test-only":
    apdus.extend([
        instance_select,
        "00E002000800000A17FFFF0000",
        f"80D40104170100000033000F{dack}",
        f"80D401041702000000330000{writek}",
        f"80D401041703000000330000{readk}",
        f"00E0000008E104{file_size_hex}00000100",
        "00A4000C02E104",
    ])
    apdus.extend(chunk_apdus(0, ndef_hex))
    apdus.extend([
        "00E0030008E1050100FFFF0000",
        "00A4000C02E105",
    ])
    apdus.extend(chunk_apdus(0, config_hex))
    apdus.append("80F1000000")

if mode in ("full", "test-only"):
    apdus.extend([
        instance_select,
        "00A4000C02E103",
        "00B000000F",
        "00A4000C02E104",
        "00B0000002",
    ])
    for off in range(2, ndef_bytes, 128):
        le = min(128, ndef_bytes - off)
        apdus.append(f"00B0{off:04X}{le:02X}")

with open(path, "w", encoding="ascii") as fh:
    for apdu in apdus:
        fh.write(apdu + "\n")
PY
}

run_apdu_sequence() {
  local path="$1"
  local gp_args=(--reader "$READER" --key "$CARD_KEY")
  while IFS= read -r apdu; do
    [ -z "$apdu" ] && continue
    gp_args+=(-a "$apdu")
  done < "$path"
  echo
  echo "Sending APDUs with gp..."
  gp "${gp_args[@]}"
}

install_tmc() {
  gp --reader "$READER" --key "$CARD_KEY" --delete "$TMC_INSTANCE_AID" >/dev/null 2>&1 || true
  gp --reader "$READER" --key "$CARD_KEY" --delete "$TMC_PACKAGE_AID" >/dev/null 2>&1 || true
  gp --reader "$READER" --key "$CARD_KEY" --install "$CAP_FILE"
}

print_summary() {
  echo
  echo "==================================="
  echo " Operation complete"
  echo "==================================="
  echo "Mode:      $MODE"
  echo "Reader:    $READER"
  echo "CAP:       $CAP_FILE"
  echo "URL:       $URL"
  echo "READK:     $READK"
  echo "DACK:      $DACK"
  echo "WRITEK:    $WRITEK"
  echo "SN:        $SN_HEX"
  echo "AUTH:      $AUTH_HEX"
  echo "File size: 0x$FILE_SIZE_HEX"
  if [ -n "$SAVE_PROFILE" ]; then
    echo "Profile:   $SAVE_PROFILE"
  fi
}

main() {
  echo "==================================="
  echo " TMC T4T Dynamic URL Wizard"
  echo "==================================="

  require_cmd python3
  require_cmd gp

  parse_args "$@"
  if [ -n "$PROFILE_PATH" ]; then
    load_profile "$PROFILE_PATH"
  fi

  if [ "$NONINTERACTIVE" -eq 0 ]; then
    interactive_collect
  fi

  compute_plan
  print_plan
  validate_inputs

  if [ -n "$SAVE_PROFILE" ]; then
    save_profile "$SAVE_PROFILE"
  fi

  if [ "$NONINTERACTIVE" -eq 0 ]; then
    read -r -p "Proceed with mode '$MODE'? [Y/n]: " confirm
    confirm="${confirm:-Y}"
    if [[ ! "$confirm" =~ ^[Yy]$ ]]; then
      echo "aborted"
      exit 0
    fi
  fi

  if [ "$MODE" != "test-only" ]; then
    install_tmc
  fi

  APDU_FILE="$(mktemp)"
  trap 'rm -f "$APDU_FILE"' EXIT
  build_apdu_file "$APDU_FILE"
  run_apdu_sequence "$APDU_FILE"
  print_summary
}

main "$@"
