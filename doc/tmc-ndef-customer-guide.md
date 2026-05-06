# TMC-NDEF 客户使用指南

本文面向需要部署、个人化和验证 TMC-NDEF 智能卡应用的客户或现场工程师。它说明如何构建 TMC-NDEF applet、如何使用交互式脚本完成个人化、如何配置动态 URL，以及如何让 URL 携带来自智能卡上另一个 applet 的数据。

## 1. 目标和范围

TMC-NDEF 是一个 JavaCard NFC Forum Type 4 Tag applet。读卡器或手机读取 NDEF 时，卡片返回一个动态 URL。该 URL 可以包含：

- 固定的卡片身份字段，例如 `KEY_ID` 和 `SN`。
- 加密的用户字段 `USER`。
- 每次会话变化的动态密文 `ENC`。
- 对返回 URL 内容计算的 `MAC`。
- 可选的 `APPDATA` 字段：由卡片上另一个 applet 通过 JavaCard Shareable 接口提供。

本项目中的 TMC-NDEF 使用 AES-128 作为本地开发环境中的 SM4 替代算法。若部署到支持 SM4 的目标平台，需要在 `NdefApplet.java` 中替换对应 cipher 常量。

## 2. 关键 AID

| 项目 | AID |
| ---- | --- |
| TMC package AID | `D2760000850101` |
| TMC applet instance AID | `D276000085010101` |

注意：选择 applet 时必须使用 instance AID `D276000085010101`。不要用 package AID `D2760000850101` 做 SELECT。

## 3. 关键文件

| 文件 | 说明 |
| ---- | ---- |
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/NdefApplet.java` | TMC-NDEF 主 applet |
| `applet-tmc/src/main/java/org/openjavacard/ndef/tmc/TmcDataSource.java` | 供其它 applet 实现的数据源 Shareable 接口 |
| `applet-tmc-provider/src/main/java/org/openjavacard/ndef/tmcprovider/TmcShareProviderApplet.java` | 示例数据源 applet |
| `tmc-personalize.sh` | 安装、个人化、读回验证脚本 |
| `tmc-demo.profile` | 演示 profile |
| `build/javacard/openjavacard-ndef-tmc.cap` | 默认构建产物 |
| `doc/tmc-personalization.md` | APDU 级个人化参考 |

## 4. 环境准备

需要：

- 一张可加载 JavaCard applet 的智能卡。
- 一个 PC/SC 读卡器。
- Docker。
- 本项目使用的 JavaCard Docker 镜像：`local/javacard-dev:latest`。
- GlobalPlatformPro，容器内已提供 `gp`。

检查读卡器和卡片：

```bash
pcsc_scan
```

本机验证过的读卡器名称：

```text
ACS ACR1281U 00 01
```

如果 Docker 普通用户无权限访问 `/var/run/docker.sock`，命令前使用 `sudo`。如果已经把当前用户加入 `docker` 组，可以去掉 `sudo`。

## 5. 构建 TMC-NDEF

推荐用项目 Ant target 构建：

```bash
sudo docker run --rm --user root \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ant -noinput build-tmc'
```

生成文件：

```text
build/javacard/openjavacard-ndef-tmc.cap
build/javacard/openjavacard-ndef-tmc.jar
build/javacard/openjavacard-ndef-tmc.jca
build/javacard/exports/org/openjavacard/ndef/tmc/javacard/tmc.exp
```

也可以使用 standalone 构建脚本：

```bash
./build-tmc-docker.sh
```

该脚本输出：

```text
build-tmc-output/res/org/openjavacard/ndef/tmc/javacard/tmc.cap
build-tmc-output/tmc_personalize_apdus.txt
```

## 6. 个人化脚本概览

`tmc-personalize.sh` 是推荐的个人化入口。它会：

- 安装 TMC CAP。
- 创建 KEY file、NDEF file、config file。
- 写入 DACK、WRITEK、READK。
- 根据 URL 模板自动计算动态字段 offset。
- 写入 NDEF 模板和 config TLV。
- 结束 PERSONAL phase，进入 NORMAL phase。
- 可选执行读回验证。

查看帮助：

```bash
./tmc-personalize.sh --help
```

常用模式：

| 模式 | 说明 |
| ---- | ---- |
| 默认 | 安装、个人化并执行读回测试 |
| `--personalize-only` | 只安装和个人化，不做最终读回 |
| `--test-only` | 不重装，只对当前卡上 applet 做读回验证 |
| `--read-only` | `--test-only` 的别名 |
| `--noninteractive` | 非交互模式，所有参数来自默认值、profile 或命令行 |

## 7. 使用交互式脚本个人化

交互式模式适合首次调试或现场配置。运行：

```bash
sudo docker run -it --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh'
```

脚本会依次询问：

- 读卡器名称。
- 卡片 GlobalPlatform key。
- CAP 文件路径。
- DACK、WRITEK、READK。
- SN。
- Auth code。
- NDEF 文件大小。
- USER 明文字段。
- 可选的 APPDATA 数据源 applet AID。
- URL 模板。

默认值适合本地 demo：

```text
Reader: ACS ACR1281U 00 01
Card GP key: 404142434445464748494A4B4C4D4E4F
CAP: ./build/javacard/openjavacard-ndef-tmc.cap
DACK: 11111111111111111111111111111111
WRITEK: 33333333333333333333333333333333
READK: 88888888888888888888888888888888
SN: 22222222222222222222
AUTH: 1122334455
File size: 0300
```

脚本会打印计算结果，例如：

```text
URL base offset in NDEF file
KEY_ID offset
SN offset
USER offset
ENC offset
MAC offset
Config bytes
```

确认无误后输入确认，脚本会开始安装和个人化。

## 8. 使用 profile 非交互个人化

如果参数固定，建议使用 profile。示例：

```bash
sudo docker run --rm \
  --user root \
  -v /run/pcscd:/run/pcscd \
  -v "$PWD":/workspace \
  local/javacard-dev:latest \
  bash -lc 'cd /workspace && ./tmc-personalize.sh --profile /workspace/tmc-demo.profile --noninteractive'
```

典型 profile：

```bash
READER='ACS ACR1281U 00 01'
CARD_KEY='404142434445464748494A4B4C4D4E4F'
CAP_FILE='/workspace/build/javacard/openjavacard-ndef-tmc.cap'
DACK='11111111111111111111111111111111'
WRITEK='33333333333333333333333333333333'
READK='88888888888888888888888888888888'
SN_HEX='22222222222222222222'
AUTH_HEX='1122334455'
FILE_SIZE_HEX='0300'
USER_HEX='00000000000000000000000000000000'
URL_TEMPLATE='https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&d=__USER__&c=__MAC__'
APPDATA_AID=''
APPDATA_SIO='00'
APPDATA_HEX_LEN='0'
MODE='full'
```

保存交互式输入为 profile：

```bash
./tmc-personalize.sh --save-profile customer.profile
```

## 9. 动态 URL 模板

URL 模板必须包含以下 token，且每个 token 只能出现一次：

| Token | 长度 | 说明 |
| ----- | ---- | ---- |
| `__KEYID__` | 2 hex chars | READK key index |
| `__SN__` | 20 hex chars | 10-byte SN |
| `__ENC__` | 32 hex chars | 动态密文 |
| `__USER__` | 取决于 `USER_HEX` | 加密后的用户字段 |
| `__MAC__` | 16 hex chars | 8-byte MAC |

默认模板：

```text
https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&d=__USER__&c=__MAC__
```

读卡时，TMC-NDEF 会生成类似：

```text
https://example.com/V01/00/00/22222222222222222222?e=<dynamic>&d=<encrypted-user>&c=<mac>
```

动态行为：

- `SN` 固定。
- `ENC` 在不同 session 中变化。
- `MAC` 在不同 session 中变化。
- 同一次选中文件 session 内，动态 NDEF 会被缓存，重复读取同一 offset 返回一致数据。

## 10. URL 读取另一个 applet 的数据

### 10.1 原理

JavaCard applet 不能随意 SELECT 另一个 applet 并读取其私有数据。正确方式是目标 applet 主动暴露 Shareable Interface Object。

本项目定义接口：

```java
package org.openjavacard.ndef.tmc;

import javacard.framework.Shareable;

public interface TmcDataSource extends Shareable {
    short getTmcData(byte[] out, short outOff, short maxLen);
}
```

目标 applet 需要：

- `implements TmcDataSource`。
- 在 `getShareableInterfaceObject(...)` 中返回 `this` 或其它实现对象。
- 在 `getTmcData(...)` 中写入要放进 URL 的 bytes。

TMC-NDEF 在构造 URL 时会：

1. 根据 `APPDATA_AID` 找到目标 applet。
2. 调用 `JCSystem.getAppletShareableInterfaceObject(aid, APPDATA_SIO)`。
3. 判断返回对象是否是 `TmcDataSource`。
4. 调用 `getTmcData(...)` 获取 bytes。
5. 把 bytes hex 编码后写入 `__APPDATA__` 预留位置。
6. 如果目标 applet 不存在或不返回接口，则该字段保持 `00...00`。

### 10.2 目标 applet 示例

本项目提供示例：

```text
applet-tmc-provider/src/main/java/org/openjavacard/ndef/tmcprovider/TmcShareProviderApplet.java
```

核心逻辑：

```java
public final class TmcShareProviderApplet extends Applet implements TmcDataSource {
    public Shareable getShareableInterfaceObject(AID clientAID, byte parameter) {
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
}
```

### 10.3 配置 URL 模板

在 URL 中增加 `__APPDATA__`：

```text
https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&a=__APPDATA__&d=__USER__&c=__MAC__
```

设置 profile：

```bash
APPDATA_AID='A00000000101'
APPDATA_SIO='00'
APPDATA_HEX_LEN='32'
```

或命令行：

```bash
./tmc-personalize.sh \
  --url-template 'https://example.com/V01/00/__KEYID__/__SN__?e=__ENC__&a=__APPDATA__&d=__USER__&c=__MAC__' \
  --appdata-aid A00000000101 \
  --appdata-sio 00 \
  --appdata-hex-len 32
```

`APPDATA_HEX_LEN` 是 URL 中预留的 hex 字符数，必须是偶数。本地实现限制最大 128 hex chars，即最多 64 bytes 原始数据。

### 10.4 APPDATA 的安全边界

- TMC-NDEF 只读取目标 applet 明确暴露的数据。
- 目标 applet 可以根据 `clientAID` 或 `parameter` 决定是否授权。
- TMC-NDEF 不绕过 JavaCard firewall。
- APPDATA 会以 hex 形式放进 URL，不直接放二进制。
- 如果 APPDATA 位于 MAC 之前，MAC 会覆盖实际返回的 APPDATA 值。

## 11. 最终读回验证

个人化后，使用一个 `gp` 进程连续发送 APDU，避免 selected-file 状态丢失：

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

期望结果：

| 步骤 | 期望 |
| ---- | ---- |
| SELECT `D276000085010101` | `9000` |
| GET VERSION `00CA000003` | `010000 9000` |
| SELECT CC file `E103` | `9000` |
| READ CC file | CC file bytes + `9000` |
| SELECT NDEF file `E104` | `9000` |
| READ NLEN | 非零 NLEN + `9000` |
| 重复读取同一动态 block | 同一 session 内应一致 |

## 12. 常见问题

### 12.1 Docker 无权限

错误：

```text
permission denied while trying to connect to the docker API
```

处理：

```bash
sudo docker ...
```

或把用户加入 docker 组后重新登录：

```bash
sudo usermod -aG docker <user>
```

### 12.2 找不到卡

错误：

```text
SCARD_E_NO_SMARTCARD
```

处理：

- 用 `pcsc_scan` 确认卡是否插入。
- 确认 reader 名称是否正确。
- 接触式卡重新插拔或压紧。
- 本机已验证 reader slot 是 `ACS ACR1281U 00 01`。

### 12.3 协议不匹配

错误：

```text
SCARD_E_PROTO_MISMATCH
```

处理：

- 断开后重新插卡。
- 重新运行同一 `gp` 命令。
- 避免多个工具同时占用 PC/SC。

### 12.4 `gp --install` 返回 `0x6985`

可能是卡片加载策略或已有 package 状态导致。使用 load/create recovery：

```bash
gp --reader "ACS ACR1281U 00 01" \
  --key 404142434445464748494A4B4C4D4E4F \
  --load /workspace/build/javacard/openjavacard-ndef-tmc.cap

gp --reader "ACS ACR1281U 00 01" \
  --key 404142434445464748494A4B4C4D4E4F \
  --cap /workspace/build/javacard/openjavacard-ndef-tmc.cap \
  --create D276000085010101
```

然后只做个人化：

```bash
./tmc-personalize.sh --profile /workspace/tmc-demo.profile --noninteractive --personalize-only
```

### 12.5 APPDATA 一直是 0

检查：

- `APPDATA_AID` 是否是目标 applet instance AID。
- 目标 applet 是否已安装且 selectable。
- `APPDATA_SIO` 是否和目标 applet 的 `getShareableInterfaceObject(...)` 参数一致。
- 目标 applet 是否实现 `org.openjavacard.ndef.tmc.TmcDataSource`。
- `APPDATA_HEX_LEN` 是否足够容纳返回数据。

## 13. 交付建议

客户量产或试点时建议固定以下文件：

- 已验证的 CAP 文件。
- 客户专用 profile。
- 读卡器名称。
- GP key 管理策略。
- URL 模板。
- APPDATA provider applet AID 和协议。
- 后端服务对 `KEY_ID`、`SN`、`ENC`、`USER`、`APPDATA`、`MAC` 的解析规则。

正式发卡前至少验证：

- 新卡安装。
- 已安装卡重装。
- `--test-only` 读回。
- 多次 tap/读卡时 `ENC` 和 `MAC` 变化。
- 同一 selected-file session 内动态缓存一致。
- APPDATA provider 不存在时的降级行为。
- APPDATA provider 存在时 URL 是否携带预期数据。
