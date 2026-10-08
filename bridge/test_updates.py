import asyncio
import base64
import hashlib
import io
import json
import pathlib
import tempfile
import unittest
import zipfile
import httpx
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from unittest.mock import patch
from updates import parse_manifest, https_url, version, extract_package, stream_url, UpdateManager


class UpdateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        cls.public=base64.b64encode(cls.key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    def payload(self):
        item={"version":"1.0.1","url":"https://example.invalid/package.zip","size":12,"sha256":"a"*64,"notes":"合成更新说明"}
        return {"schema":1,"product":"Evara","releases":{"windows-x64":item,"android":{**item,"version_code":20}}}
    def sign(self,data):
        payload=json.dumps(data).encode()
        return json.dumps({"payload":base64.b64encode(payload).decode(),"signature":base64.b64encode(self.key.sign(payload,padding.PKCS1v15(),hashes.SHA256())).decode()}).encode()
    def test_signed_manifest_valid(self):
        data=parse_manifest(self.sign(self.payload()),self.public)
        self.assertEqual("1.0.1",data["android"]["version"])
    def test_tampered_or_unsigned_rejected(self):
        raw=json.loads(self.sign(self.payload()));raw["payload"]=base64.b64encode(b'{"schema":1}').decode()
        for value in [json.dumps(raw).encode(),b'{}',b'x'*65537]:
            with self.assertRaises(ValueError):parse_manifest(value,self.public)
    def test_wrong_product_invalid_url_size_hash_and_version_rejected(self):
        for field,value in [("url","http://example.com/a"),("size",0),("size",999999999),("sha256","bad"),("version","1.0.1-beta")]:
            data=self.payload();data["releases"]["android"][field]=value
            with self.assertRaises(ValueError):parse_manifest(self.sign(data),self.public)
        data=self.payload();data["product"]="wrong"
        with self.assertRaises(ValueError):parse_manifest(self.sign(data),self.public)
    def test_https_and_downgrade_version_order(self):
        self.assertLess(version("0.3.1"),version("1.0.0"))
        for url in ["http://localhost/a","file:///a","https://user:pass@example.com/a","https://x/a\n"]:
            with self.assertRaises(ValueError):https_url(url)
    def zip(self, entries):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,"w") as archive:
            for name,value in entries:archive.writestr(name,value)
        return stream.getvalue()
    def test_safe_portable_extract(self):
        with tempfile.TemporaryDirectory() as d:
            package=pathlib.Path(d)/"package.zip";package.write_bytes(self.zip([("Evara/Evara.exe",b"MZsynthetic"),("Evara/_internal/test.txt",b"runtime")]))
            result=pathlib.Path(extract_package(package,pathlib.Path(d)/"updates"))
            self.assertTrue(result.is_file());self.assertTrue((result.parent/"_internal/test.txt").is_file())
    def test_zip_traversal_and_missing_executable_rejected(self):
        for name in ["../outside.txt","/outside.txt","Evara/../../outside.txt","Evara\\outside.txt","C:/outside.txt","Other/Evara.exe"]:
            with tempfile.TemporaryDirectory() as d:
                package=pathlib.Path(d)/"package.zip";package.write_bytes(self.zip([(name,b"MZ")]))
                with self.assertRaises(ValueError):extract_package(package,pathlib.Path(d)/"updates")


class UpdateNetworkTests(unittest.IsolatedAsyncioTestCase):
    async def test_verified_download_extracts_complete_portable_directory(self):
        stream=io.BytesIO()
        with zipfile.ZipFile(stream,"w") as archive:
            archive.writestr("Evara/Evara.exe",b"MZsynthetic-test")
            archive.writestr("Evara/_internal/runtime.txt",b"synthetic-runtime")
        binary=stream.getvalue()
        with tempfile.TemporaryDirectory() as d:
            manager=UpdateManager(d);manager.candidate={"url":"https://example.invalid/a","size":len(binary),"sha256":hashlib.sha256(binary).hexdigest(),"version":"1.0.1"}
            async def response(*args):return httpx.Response(200,content=binary)
            progress=[]
            with patch("updates.stream_url",response):result=await manager.download(lambda a,b:progress.append((a,b)))
            self.assertTrue(pathlib.Path(result["executable"]).is_file());self.assertTrue(progress)
            self.assertFalse(list(manager.directory.glob("*.part")))
    async def test_https_redirects_only(self):
        seen=[]
        def handle(request):
            seen.append(request)
            self.assertNotIn("authorization",request.headers)
            return httpx.Response(302,headers={"Location":"http://example.invalid/unsafe"})
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            with self.assertRaises(ValueError):await stream_url(client,"https://example.invalid/feed",65536)
        self.assertEqual(1,len(seen))
    async def test_redirected_download_and_size_limit(self):
        def handle(request):
            if request.url.host=="example.invalid":return httpx.Response(302,headers={"Location":"https://cdn.invalid/asset"})
            return httpx.Response(200,content=b"signed-data")
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            result=await stream_url(client,"https://example.invalid/feed",20)
            self.assertEqual(b"signed-data",await result.aread());await result.aclose()
            with self.assertRaises(ValueError):await stream_url(client,"https://cdn.invalid/asset",2)
    async def test_download_hash_mismatch_never_extracted(self):
        with tempfile.TemporaryDirectory() as d:
            manager=UpdateManager(d);manager.candidate={"url":"https://example.invalid/a","size":3,"sha256":"0"*64,"version":"1.0.1"}
            async def response(*args):return httpx.Response(200,content=b"abc")
            with patch("updates.stream_url",response),patch("updates.extract_package") as extract:
                with self.assertRaises(ValueError):await manager.download(lambda a,b:None)
                extract.assert_not_called()
            self.assertFalse(list(manager.directory.glob("*.part")))
    async def test_cancel_removes_partial_and_never_extracts(self):
        class Wait(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield b"a"
                await asyncio.Event().wait()
        with tempfile.TemporaryDirectory() as d:
            manager=UpdateManager(d);manager.candidate={"url":"https://example.invalid/a","size":3,"sha256":"0"*64,"version":"1.0.1"}
            async def response(*args):return httpx.Response(200,stream=Wait())
            with patch("updates.stream_url",response),patch("updates.extract_package") as extract:
                task=asyncio.create_task(manager.download(lambda a,b:None));await asyncio.sleep(.05);task.cancel()
                with self.assertRaises(asyncio.CancelledError):await task
                extract.assert_not_called()
            self.assertFalse(list(manager.directory.glob("*.part")))
