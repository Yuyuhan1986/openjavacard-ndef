#!/bin/bash
# Build TMC variant using the javacard-great-again Docker container
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${PROJECT_DIR}/build-tmc-output"
CLASSES_DIR="${OUTPUT_DIR}/classes"
RES_DIR="${OUTPUT_DIR}/res"
DOCKER_UID="${SUDO_UID:-$(id -u)}"
DOCKER_GID="${SUDO_GID:-$(id -g)}"
DOCKER_USER="${DOCKER_UID}:${DOCKER_GID}"

SRC_DIR="${PROJECT_DIR}/applet-tmc/src/main/java"
PACKAGE_NAME="org.openjavacard.ndef.tmc"
PACKAGE_AID="0xD2:0x76:0x00:0x00:0x85:0x01:0x01"
APPLET_CLASS="org.openjavacard.ndef.tmc.NdefApplet"
APPLET_AID="0xD2:0x76:0x00:0x00:0x85:0x01:0x01:0x01"
VERSION="1.0"

rm -rf "${OUTPUT_DIR}"
mkdir -p "${CLASSES_DIR}" "${RES_DIR}"
if [ "$(id -u)" = "0" ]; then
  chown -R "${DOCKER_USER}" "${OUTPUT_DIR}"
fi

echo "=== Step 1: Compile with javac ==="
docker run --rm \
  --user "${DOCKER_USER}" \
  -e JAVA_HOME=/usr/local/openjdk-8 \
  -v "${PROJECT_DIR}:${PROJECT_DIR}" \
  cirne/javacard-great-again \
  /usr/local/openjdk-8/bin/javac \
    -source 1.5 -target 1.5 \
    -cp "/opt/javacard/lib/api.jar" \
    -d "${CLASSES_DIR}" \
    "${SRC_DIR}/org/openjavacard/ndef/tmc/NdefApplet.java" \
    "${SRC_DIR}/org/openjavacard/ndef/tmc/TmcDataSource.java" \
    "${SRC_DIR}/org/openjavacard/ndef/tmc/UtilTLV.java"

echo ""
echo "=== Step 2: Convert to CAP ==="
docker run --rm \
  --user "${DOCKER_USER}" \
  -e JAVA_HOME=/usr/local/openjdk-8 \
  -v "${PROJECT_DIR}:${PROJECT_DIR}" \
  cirne/javacard-great-again \
  converter \
    -classdir "${CLASSES_DIR}" \
    -exportpath "/opt/javacard/api_export_files" \
    -d "${RES_DIR}" \
    -out CAP \
    -applet "${APPLET_AID}" "${APPLET_CLASS}" \
    -v \
    "${PACKAGE_NAME}" "${PACKAGE_AID}" "${VERSION}"

echo ""
echo "=== Build complete ==="
find "${RES_DIR}" -name "*.cap" -exec ls -la {} \;

cat > "${OUTPUT_DIR}/tmc_personalize_apdus.txt" <<'EOF'
00A4040008D276000085010101
00E002000800000A17FFFF0000
80D40104170100000033000F11111111111111111111111111111111
80D40104170200000033000033333333333333333333333333333333
80D40104170300000033000088888888888888888888888888888888
00E0000008E104030000000100
00A4000C02E104
00D60000800090D1018C550068747470733A2F2F6578616D706C652E636F6D2F5630312F30302F46462F31313131313131313131313131313131313131313F653D303030303030303030303030303030303030303030303030303030303030303026643D303030303030303030303030303030303030303030303030303030303030303026
00D6008012633D31313131313131313131313131313131
00E0030008E1050100FFFF0000
00A4000C02E105
00D600003C004001000041020022004202005F004302002000440A2222222222222222222200450200250046051122334455004702003C00480200000049020082
80F1000000
EOF

echo ""
echo "To deploy with GlobalPlatformPro:"
echo "  gp --install ${RES_DIR}/org/openjavacard/ndef/tmc/javacard/tmc.cap"
