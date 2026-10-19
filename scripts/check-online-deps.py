#!/usr/bin/env python3
"""本地 -> 线上「数据库 / OCR」连通性自检。

前置：先在公司网络内，或用 Sangfor aTrust 连上 VPN（10.0.2.x 网段）。
用法：python scripts/check-online-deps.py
只用标准库，不需要 pip 安装任何东西。
"""

import socket
import sys
import time
import urllib.error
import urllib.request

DB_HOST = "10.0.2.161"
DB_PORT = 23306
OCR_URL = "http://10.0.2.85:8868/health"
TIMEOUT = 5


def tcp_probe(host, port):
    """返回 (是否连通, 耗时ms, 说明)"""
    start = time.time()
    sock = socket.socket()
    sock.settimeout(TIMEOUT)
    try:
        code = sock.connect_ex((host, port))
        cost = int((time.time() - start) * 1000)
        if code != 0:
            return False, cost, "connect_ex=%d" % code
        return True, cost, ""
    finally:
        sock.close()


def mysql_handshake(host, port):
    """读到 MySQL 服务端 greeting 的首字节 0x0a 才算真的是 MySQL 在听"""
    sock = socket.socket()
    sock.settimeout(TIMEOUT)
    try:
        sock.connect((host, port))
        data = sock.recv(5)
        if len(data) >= 5 and data[4] == 0x0A:
            return True, "协议版本 10，服务端已应答"
        if len(data) >= 5 and data[4] == 0xFF:
            return False, "服务端返回错误包（可能拒绝该来源 IP）"
        return False, "非 MySQL 应答：%r" % data[:5]
    except Exception as exc:  # noqa: BLE001
        return False, "%s: %s" % (type(exc).__name__, exc)
    finally:
        sock.close()


def http_probe(url):
    start = time.time()
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as resp:
            body = resp.read(300).decode("utf-8", "replace")
            cost = int((time.time() - start) * 1000)
            return True, cost, "HTTP %d %s" % (resp.status, body.strip()[:120])
    except urllib.error.HTTPError as exc:
        cost = int((time.time() - start) * 1000)
        return False, cost, "HTTP %d" % exc.code
    except Exception as exc:  # noqa: BLE001
        cost = int((time.time() - start) * 1000)
        return False, cost, "%s: %s" % (type(exc).__name__, exc)


def main():
    print("=" * 68)
    print("本地 -> 线上依赖连通性自检")
    print("=" * 68)

    ok_db, cost, detail = tcp_probe(DB_HOST, DB_PORT)
    print("[1] MySQL  %s:%d" % (DB_HOST, DB_PORT))
    print("    TCP   : %s  %dms  %s" % ("通" if ok_db else "不通", cost, detail))
    if ok_db:
        good, msg = mysql_handshake(DB_HOST, DB_PORT)
        print("    MySQL : %s  %s" % ("握手正常" if good else "握手失败", msg))
    else:
        good = False
        print("    提示  : 先连 VPN；若 VPN 已连仍不通，改用 SSH 隧道：")
        print("            ssh -N -L 23306:127.0.0.1:23306 <user>@%s" % DB_HOST)
        print("            然后本地连 127.0.0.1:23306")

    print()
    ok_ocr, cost, detail = http_probe(OCR_URL)
    print("[2] OCR    %s" % OCR_URL)
    print("    HTTP  : %s  %dms  %s" % ("通" if ok_ocr else "不通", cost, detail))
    if not ok_ocr:
        print("    提示  : 10.0.2.85 与 10.0.2.161 同属内网，同样依赖 VPN")

    print()
    print("-" * 68)
    if ok_db and good and ok_ocr:
        print("结果：数据库与 OCR 都可达，可以在本地后端用线上依赖调试了。")
        return 0
    print("结果：仍有不通项，按上面提示排查。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
