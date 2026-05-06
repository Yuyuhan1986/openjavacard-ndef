#!/bin/bash
# Build TMC variant using the javacard-great-again Docker container
set -e

PROJECT_DIR="/root/openjavacard-ndef"
OUTPUT_DIR="${PROJECT_DIR}/build-tmc-output"
CLASSES_DIR="${OUTPUT_DIR}/classes"
RES_DIR="${OUTPUT_DIR}/res"

SRC_DIR="${PROJECT_DIR}/applet-tmc/src/main/java"
PACKAGE_NAME="org.openjavacard.ndef.tmc"
PACKAGE_AID="0xD2:0x76:0x00:0x00:0x85:0x01:0x01:0x01"
APPLET_CLASS="org.openjavacard.ndef.tmc.NdefApplet"
APPLET_AID="0xD2:0x76:0x00:0x00:0x85:0x01:0x01"
VERSION="1.0"

rm -rf "${OUTPUT_DIR}"
mkdir -p "${CLASSES_DIR}" "${RES_DIR}"

echo "=== Step 1: Compile with javac ==="
docker run --rm \
  -e JAVA_HOME=/usr/local/openjdk-8 \
  -v "${PROJECT_DIR}:${PROJECT_DIR}" \
  cirne/javacard-great-again \
  /usr/local/openjdk-8/bin/javac \
    -source 1.5 -target 1.5 \
    -cp "/opt/javacard/lib/api.jar" \
    -d "${CLASSES_DIR}" \
    "${SRC_DIR}/org/openjavacard/ndef/tmc/NdefApplet.java" \
    "${SRC_DIR}/org/openjavacard/ndef/tmc/UtilTLV.java"

echo ""
echo "=== Step 2: Convert to CAP ==="
docker run --rm \
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
echo ""
echo "To deploy with GlobalPlatformPro:"
echo "  gp --install ${RES_DIR}/org/openjavacard/ndef/tmc/javacard/tmc.cap"
