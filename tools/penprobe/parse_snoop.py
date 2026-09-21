#!/usr/bin/env python3
"""解析 btsnoop cfa，提取笔的 ATT 写/通知序列。"""
import struct, sys, datetime

def parse(path):
    d = open(path, "rb").read()
    assert d[:8] == b"btsnoop\0", d[:8]
    off = 16
    records = []
    while off + 24 <= len(d):
        olen, ilen, flags, drops, ts = struct.unpack_from(">IIIIq", d, off)
        off += 24
        pkt = d[off:off+ilen]
        off += ilen
        records.append((flags, ts, pkt))
    return records

def att_dump(records, label):
    print("=" * 70)
    print(label, "records=%d" % len(records))
    # HCI ACL: handle(12b)+pb(2b)+bc(2b) BE u16; then dlen u16; L2CAP len u16 cid u16
    for flags, ts, pkt in records:
        if len(pkt) < 9: continue
        if (pkt[0] & 0x3F) != 0x02:  # HCI ACL only (type byte if datalink=1002)
            if (pkt[0] & 0x3F) != 0x02: continue
        hf = struct.unpack_from(">H", pkt, 1)[0]
        handle = hf & 0x0FFF
        dlen = struct.unpack_from(">H", pkt, 3)[0]
        l2 = pkt[5:5+dlen]
        if len(l2) < 4: continue
        cid = struct.unpack_from(">H", l2, 2)[0]
        if cid != 0x0004: continue  # ATT
        att = l2[4:]
        if not att: continue
        op = att[0]
        # direction: btsnoop flags bit0: 0=sent(host->ctrl), 1=received
        dirn = "H>P" if (flags & 1) == 0 else "P>H"
        t = datetime.datetime(0, 1, 1) + datetime.timedelta(microseconds=ts - 0x00dcddb30f2f8000)
        tstr = t.strftime("%H:%M:%S.%f")[:-3]
        if op == 0x52:  # write cmd/req
            h = struct.unpack_from("<H", att, 1)[0]
            val = att[3:]
            print("%s %s WRITE handle=0x%04x val=%s" % (tstr, dirn, h, val.hex()))
        elif op == 0x12:  # write response
            print("%s %s WRITE_RSP" % (tstr, dirn))
        elif op == 0x1B:  # handle notification
            h = struct.unpack_from("<H", att, 1)[0]
            val = att[3:]
            print("%s %s NOTIFY handle=0x%04x val=%s" % (tstr, dirn, h, val.hex()))
        elif op == 0x10:  # read by group req
            print("%s %s READ_GROUP" % (tstr, dirn))
        elif op in (0x0A, 0x0B):  # read req/rsp
            if op == 0x0A:
                h = struct.unpack_from("<H", att, 1)[0]
                print("%s %s READ_REQ handle=0x%04x" % (tstr, dirn, h))
            else:
                print("%s %s READ_RSP val=%s" % (tstr, dirn, att[1:].hex()))

parse("/tmp/penprobe/btsnoop_hci_20260921_170801.cfa")
r1 = parse("/tmp/penprobe/btsnoop_hci_20260921_170801.cfa")
r2 = parse("/tmp/penprobe/btsnoop_hci_20260921_180407.cfa")
att_dump(r1, "SESSION 170801 (17:08-18:04: 探测帧+睡眠期)")
att_dump(r2, "SESSION 180407 (18:04-18:06: BT重启→唤醒瞬间)")
