"""Sign an Evara release manifest. Keep the private key outside the repository."""
import argparse
import base64
import hashlib
import json
import pathlib
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding


def create_manifest(windows, apk, base_url, version, version_code, private_key, output, notes):
    if not base_url.startswith("https://"): raise ValueError("HTTPS release base URL required")
    def item(path):
        path = pathlib.Path(path)
        return {"version": version, "url": base_url.rstrip("/") + "/" + path.name,
                "size": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "notes": notes}
    data={"schema":1,"product":"Evara","releases":{"windows-x64":item(windows),"android":{**item(apk),"version_code":version_code}}}
    payload=json.dumps(data,ensure_ascii=False,separators=(",",":")).encode("utf-8")
    key=serialization.load_pem_private_key(pathlib.Path(private_key).read_bytes(),password=None)
    signature=key.sign(payload,padding.PKCS1v15(),hashes.SHA256())
    pathlib.Path(output).write_text(json.dumps({"payload":base64.b64encode(payload).decode(),"signature":base64.b64encode(signature).decode()},indent=2),encoding="utf-8")
    return data


if __name__=="__main__":
    parser=argparse.ArgumentParser()
    for flag in ["windows","apk","base-url","version","private-key","output","notes"]:parser.add_argument("--"+flag,required=True)
    parser.add_argument("--version-code",type=int,required=True)
    args=parser.parse_args()
    create_manifest(args.windows,args.apk,args.base_url,args.version,args.version_code,args.private_key,args.output,args.notes)
