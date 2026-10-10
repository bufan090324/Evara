import asyncio
import base64
import hashlib
import json
import pathlib
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from websockets.datastructures import Headers
from phone_updates import PhoneUpdateCache, PairedUpdateGateway, transfer_proof, CHUNK

class PhoneUpdatesTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        cls.public=base64.b64encode(cls.key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.data=b'x'*CHUNK+b'last chunk'
        item={"version":"1.0.4","url":"https://source.invalid/phone.apk","size":len(self.data),"sha256":hashlib.sha256(self.data).hexdigest(),"notes":"synthetic"}
        payload=json.dumps({"schema":1,"product":"Evara","releases":{"android":{**item,"version_code":28},"windows-x64":item}}).encode()
        self.raw=json.dumps({"payload":base64.b64encode(payload).decode(),"signature":base64.b64encode(self.key.sign(payload,padding.PKCS1v15(),hashes.SHA256())).decode()}).encode()
        self.cache=PhoneUpdateCache(self.temp.name,self.public)
        self.cache.directory.mkdir();self.path=self.cache.directory/'phone.apk';self.path.write_bytes(self.data)
        self.cache.ready=self.cache.verified_file(self.raw,self.path)
        self.gateway=PairedUpdateGateway(self.cache,"synthetic-token")
        self.conn=SimpleNamespace(remote_address=("192.168.1.2",1234))

    async def call(self,path,headers=None,conn=None):
        return await self.gateway.process_request(conn or self.conn,SimpleNamespace(path=path,headers=Headers(headers or {})))

    async def auth(self,path,nonce=None,proof=None):
        if nonce is None:
            response=await self.call('/evara-update/challenge');nonce=json.loads(response.body)['nonce']
        return await self.call(path,{'X-Evara-Nonce':nonce,'X-Evara-Proof':proof or transfer_proof('synthetic-token',nonce,path)})

    async def test_signed_manifest_and_exact_chunks(self):
        response=await self.auth('/evara-update/manifest')
        self.assertEqual(200,response.status_code);self.assertEqual(self.raw,response.body)
        self.assertEqual("close",response.headers["Connection"])
        identifier=response.headers['X-Evara-Cache-Id']
        first=await self.auth(f'/evara-update/chunk/{identifier}/0')
        last=await self.auth(f'/evara-update/chunk/{identifier}/{CHUNK}')
        self.assertEqual(self.data,first.body+last.body)

    async def test_requires_authentication_and_rejects_bad_proof(self):
        self.assertEqual(401,(await self.call('/evara-update/manifest')).status_code)
        self.assertEqual(401,(await self.auth('/evara-update/manifest',proof='wrong')).status_code)
        self.assertEqual(401,(await self.auth('/evara-update/manifest',proof='非ASCII')).status_code)

    async def test_nonce_one_use_expiry_and_path_binding(self):
        nonce=json.loads((await self.call('/evara-update/challenge')).body)['nonce']
        wrong=await self.call('/evara-update/manifest',{'X-Evara-Nonce':nonce,'X-Evara-Proof':transfer_proof('synthetic-token',nonce,'/different')})
        self.assertEqual(401,wrong.status_code)
        self.assertEqual(401,(await self.auth('/evara-update/manifest',nonce=nonce)).status_code)
        nonce=json.loads((await self.call('/evara-update/challenge')).body)['nonce']
        self.gateway.nonces[nonce]=(self.conn.remote_address[0],0)
        self.assertEqual(401,(await self.auth('/evara-update/manifest',nonce=nonce)).status_code)

    async def test_peer_binding_public_peers_rejected_control_path_unchanged(self):
        nonce=json.loads((await self.call('/evara-update/challenge')).body)['nonce']
        other=SimpleNamespace(remote_address=('192.168.1.3',4321))
        response=await self.call('/evara-update/manifest',{'X-Evara-Nonce':nonce,'X-Evara-Proof':transfer_proof('synthetic-token',nonce,'/evara-update/manifest')},other)
        self.assertEqual(401,response.status_code)
        self.assertEqual(403,(await self.call('/evara-update/challenge',conn=SimpleNamespace(remote_address=('8.8.8.8',1)))).status_code)
        self.assertIsNone(await self.call('/bridge'))

    async def test_challenge_queue_is_bounded(self):
        for _ in range(8):self.assertEqual(200,(await self.call('/evara-update/challenge')).status_code)
        self.assertEqual(429,(await self.call('/evara-update/challenge')).status_code)

    async def test_cache_clear_old_id_bad_offsets_and_unknown_path(self):
        identifier=self.cache.ready['id']
        self.assertEqual(409,(await self.auth(f'/evara-update/chunk/{identifier}/1')).status_code)
        self.assertEqual(404,(await self.auth('/evara-update/../../key.pem')).status_code)
        self.assertEqual(409,(await self.auth('/evara-update/chunk/'+'a'*32+'/0')).status_code)
        self.cache.clear()
        self.assertEqual(409,(await self.auth(f'/evara-update/chunk/{identifier}/0')).status_code)

    async def test_concurrent_chunks_are_bounded(self):
        self.gateway.active_chunks=2
        self.assertEqual(429,(await self.auth('/evara-update/chunk/'+self.cache.ready['id']+'/0')).status_code)

    def test_untrusted_manifest_and_damaged_cache_rejected(self):
        with self.assertRaises(ValueError):self.cache.verified_file(b'{}',self.path)
        self.path.write_bytes(b'z'*len(self.data))
        with self.assertRaises(ValueError):self.cache.verified_file(self.raw,self.path)

    async def test_prepare_reuses_matching_cache(self):
        requests=[]
        def handle(request):
            requests.append(str(request.url))
            return httpx.Response(200,content=self.raw)
        with patch('phone_updates.update_client',side_effect=lambda *a,**k:httpx.AsyncClient(transport=httpx.MockTransport(handle))):
            result=await self.cache.prepare('https://source.invalid/update.json',True,lambda *_:None)
        self.assertTrue(result['reused']);self.assertEqual(1,len(requests))

    async def test_prepare_checks_download_hash_and_cancel_cleans_partial(self):
        self.path.unlink()
        def handle(request):return httpx.Response(200,content=self.raw if request.url.path.endswith('json') else b'z'*len(self.data))
        with patch('phone_updates.update_client',side_effect=lambda *a,**k:httpx.AsyncClient(transport=httpx.MockTransport(handle))):
            with self.assertRaises(ValueError):await self.cache.prepare('https://source.invalid/update.json',False,lambda *_:None)
        self.assertIsNone(self.cache.ready);self.assertFalse((self.cache.directory/'download.part').exists())
        def valid(request):return httpx.Response(200,content=self.raw if request.url.path.endswith('json') else self.data)
        def cancel(*_):raise asyncio.CancelledError()
        with patch('phone_updates.update_client',side_effect=lambda *a,**k:httpx.AsyncClient(transport=httpx.MockTransport(valid))):
            with self.assertRaises(asyncio.CancelledError):await self.cache.prepare('https://source.invalid/update.json',False,cancel)
        self.assertIsNone(self.cache.ready);self.assertFalse((self.cache.directory/'download.part').exists())
