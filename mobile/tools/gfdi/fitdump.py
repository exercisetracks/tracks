"""Minimal FIT record dumper - enough to compare two encoders field by field."""
import sys, struct, datetime

BASE = {0:("enum",1),1:("sint8",1),2:("uint8",1),3:("sint16",2),4:("uint16",2),
        5:("sint32",4),6:("uint32",4),7:("string",1),8:("float32",4),9:("float64",8),
        10:("uint8z",1),11:("uint16z",2),12:("uint32z",4),13:("byte",1),
        14:("sint64",8),15:("uint64",8),16:("uint64z",8)}
MESG = {0:"file_id",49:"file_creator",26:"workout",27:"workout_step",28:"schedule",
        31:"course",32:"course_point",19:"lap",20:"record",18:"session",21:"event",
        23:"device_info",34:"activity",12:"sport",7:"zones_target",258:"dive_settings",
        225:"set",285:"jump",317:"climb_pro",375:"?375",393:"?393"}
FILE_TYPE = {1:"device",2:"settings",3:"sport",4:"activity",5:"workout",6:"course",
             7:"schedules",9:"weight",10:"totals",11:"goals",14:"blood_pressure",
             15:"monitoring_a",20:"activity_summary",28:"monitoring_daily",32:"monitoring_b",
             34:"segment",35:"segment_list",40:"exd_configuration"}
EPOCH = datetime.datetime(1989,12,31,tzinfo=datetime.timezone.utc)

def parse(data):
    hdr_size = data[0]
    data_size = struct.unpack("<I", data[4:8])[0]
    pos = hdr_size
    end = hdr_size + data_size
    defs = {}
    out = []
    while pos < end:
        rh = data[pos]; pos += 1
        if rh & 0x40:  # definition
            local = rh & 0x0F
            _res = data[pos]; arch = data[pos+1]; pos += 2
            e = ">" if arch else "<"
            gnum = struct.unpack(e+"H", data[pos:pos+2])[0]; pos += 2
            nfields = data[pos]; pos += 1
            fields = []
            for _ in range(nfields):
                fdef, size, btype = data[pos], data[pos+1], data[pos+2]; pos += 3
                fields.append((fdef, size, btype & 0x1F))
            dev = []
            if rh & 0x20:
                ndev = data[pos]; pos += 1
                for _ in range(ndev):
                    dev.append((data[pos], data[pos+1], data[pos+2])); pos += 3
            defs[local] = (gnum, e, fields, dev)
            out.append(("DEF", local, gnum, fields))
        else:
            local = rh & 0x0F
            gnum, e, fields, dev = defs[local]
            vals = {}
            for fdef, size, btype in fields:
                raw = data[pos:pos+size]; pos += size
                vals[fdef] = decode(raw, btype, e, size)
            for _, size, _ in dev:
                pos += size
            out.append(("DATA", local, gnum, vals))
    return out

def decode(raw, btype, e, size):
    name, esize = BASE.get(btype, ("byte",1))
    if name == "string":
        return raw.split(b"\x00")[0].decode("utf-8","replace")
    fmt = {"enum":"B","uint8":"B","uint8z":"B","sint8":"b","uint16":"H","uint16z":"H",
           "sint16":"h","uint32":"I","uint32z":"I","sint32":"i","float32":"f",
           "float64":"d","byte":"B","uint64":"Q","uint64z":"Q","sint64":"q"}.get(name,"B")
    n = size // esize
    try:
        vals = struct.unpack(e + fmt*n, raw)
    except struct.error:
        return raw.hex()
    return vals[0] if n == 1 else list(vals)

def show(path):
    data = open(path,"rb").read()
    print(f"### {path}  ({len(data)} bytes)")
    for kind, local, gnum, payload in parse(data):
        mname = MESG.get(gnum, f"mesg_{gnum}")
        if kind == "DEF":
            print(f"  DEF  local{local} {mname:14} fields={[f[0] for f in payload]}")
        else:
            extra = []
            for k,v in payload.items():
                s = f"{k}={v}"
                if mname=="file_id" and k==0: s = f"0/type={FILE_TYPE.get(v,v)}"
                if k==253 or (mname in("file_id","schedule") and k==4):
                    try: s = f"{k}={v} ({(EPOCH+datetime.timedelta(seconds=v)).isoformat()})"
                    except Exception: pass
                if mname=="schedule" and k==6:
                    try: s = f"6/scheduled_time={v} ({(EPOCH+datetime.timedelta(seconds=v)).isoformat()})"
                    except Exception: pass
                extra.append(s)
            print(f"  DATA local{local} {mname:14} " + "  ".join(extra))

for p in sys.argv[1:]:
    show(p); print()
