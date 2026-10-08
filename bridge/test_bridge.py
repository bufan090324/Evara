import asyncio
import contextlib
import json
import pathlib
import ssl
import tempfile
import unittest
from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
import datetime as dt
import ipaddress
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed
from bridge import Bridge, initialize, lan, proof


class BasicTests(unittest.TestCase):
    def test_lan(self):
        for ip in ("10.0.0.1", "192.168.1.1", "172.16.0.1"):
            self.assertTrue(lan(ip))
        for ip in ("127.0.0.1", "169.254.1.1", "8.8.8.8", "example.com"):
            self.assertFalse(lan(ip))

    def test_certificate(self):
        with tempfile.TemporaryDirectory() as folder:
            p = pathlib.Path(folder)
            pairing = initialize(p, "192.168.1.5", 8765, "test")
            cert = x509.load_pem_x509_certificate((p / "cert.pem").read_bytes())
            self.assertEqual(pairing["certificate_sha256"], cert.fingerprint(hashes.SHA256()).hex())
            self.assertEqual(1, pairing["protocol"])
            self.assertGreaterEqual(len(pairing["token"]), 32)
            with self.assertRaises(ValueError):
                initialize(p, "192.168.1.5", 8765, "test")

    def test_proof(self):
        self.assertNotEqual(proof("correct", "abc"), proof("wrong", "abc"))
        self.assertNotEqual(proof("correct", "abc"), proof("correct", "def"))


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.bridge = Bridge("test-token", allow_loopback_for_tests=True)
        self.server = await serve(self.bridge.handler, "127.0.0.1", 0, max_size=2200000, max_queue=8)
        self.url = f"ws://127.0.0.1:{self.server.sockets[0].getsockname()[1]}/bridge"

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()

    async def phone(self, token="test-token"):
        ws = await connect(self.url)
        challenge = json.loads(await ws.recv())
        await ws.send(json.dumps(dict(type="auth", protocol=1, proof=proof(token, challenge["nonce"]))))
        await ws.recv()
        return ws

    async def test_auth_success_and_correlated_response(self):
        ws = await self.phone()
        request = asyncio.create_task(self.bridge.request("device_status"))
        message = json.loads(await ws.recv())
        self.assertEqual("device_status", message["method"])
        await ws.send(json.dumps(dict(type="response", protocol=1, id=message["id"], ok=True, data={"connected": True})))
        self.assertTrue((await request)["data"]["connected"])
        await ws.close()

    async def test_wrong_credential(self):
        with self.assertRaises(ConnectionClosed):
            await self.phone("incorrect")
        self.assertIsNone(self.bridge.phone)

    async def test_auth_timeout(self):
        ws = await connect(self.url)
        await ws.recv()
        with self.assertRaises(ConnectionClosed):
            await asyncio.wait_for(ws.recv(), 12)
        self.assertIsNone(self.bridge.phone)

    async def test_disconnect_invalidates_pending(self):
        ws = await self.phone()
        r = asyncio.create_task(self.bridge.request("go_home"))
        await ws.recv()
        await ws.close()
        with self.assertRaises(ConnectionError):
            await r
        self.assertFalse(self.bridge.pending)

    async def test_timeout_late_reply_and_new_request(self):
        ws = await self.phone()
        old = asyncio.create_task(self.bridge.request("get_ui_tree", timeout_ms=500))
        m = json.loads(await asyncio.wait_for(ws.recv(), 2))
        with self.assertRaises(TimeoutError):
            await old
        new = asyncio.create_task(self.bridge.request("device_status"))
        n = json.loads(await ws.recv())
        await ws.send(json.dumps(dict(type="response", protocol=1, id=m["id"], ok=True, data={"old": True})))
        await ws.send(json.dumps(dict(type="response", protocol=1, id=n["id"], ok=True, data={"new": True})))
        self.assertTrue((await new)["data"]["new"])
        await ws.close()

    async def test_cancel_query_not_blocked_by_long_task(self):
        ws = await self.phone()
        first = asyncio.create_task(self.bridge.request("start_sleep_task", {"date": "2026-10-06"}))
        m = json.loads(await ws.recv())
        await ws.send(json.dumps(dict(type="response", protocol=1, id=m["id"], ok=True, data={"task_id": "task-1"})))
        self.assertEqual("task-1", (await first)["data"]["task_id"])
        cancel = asyncio.create_task(self.bridge.request("cancel_task"))
        m = json.loads(await ws.recv())
        await ws.send(json.dumps(dict(type="response", protocol=1, id=m["id"], ok=True, data={"state": "cancelled"})))
        self.assertEqual("cancelled", (await cancel)["data"]["state"])
        await ws.close()

    async def test_cancelled_request_late_response_ignored(self):
        ws = await self.phone()
        r = asyncio.create_task(self.bridge.request("get_ui_tree"))
        m = json.loads(await ws.recv())
        r.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await r
        await ws.send(json.dumps(dict(type="response", protocol=1, id=m["id"], ok=True)))
        await asyncio.sleep(0.01)
        self.assertFalse(self.bridge.pending)
        await ws.close()

    async def test_pending_bound(self):
        ws = await self.phone()
        requests = [asyncio.create_task(self.bridge.request("device_status")) for _ in range(8)]
        for _ in requests:
            await ws.recv()
        with self.assertRaises(ValueError):
            await self.bridge.request("go_home")
        await ws.close()
        for request in requests:
            with self.assertRaises(ConnectionError):
                await request

    async def test_request_limits(self):
        ws = await self.phone()
        with self.assertRaises(ValueError):
            await self.bridge.request("x", timeout_ms=1)
        with self.assertRaises(ValueError):
            await self.bridge.request("x", {"x": "a" * 65536})
        await ws.close()

    async def test_second_phone_rejected(self):
        ws = await self.phone()
        second = await connect(self.url)
        with self.assertRaises(ConnectionClosed):
            await second.recv()
        await ws.close()


class WssTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_wss_certificate_and_auth(self):
        with tempfile.TemporaryDirectory() as folder:
            p = pathlib.Path(folder)
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-only")])
            now = dt.datetime.now(dt.timezone.utc)
            cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                    .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(minutes=1))
                    .not_valid_after(now + dt.timedelta(days=1))
                    .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
                    .sign(key, hashes.SHA256()))
            (p / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
            (p / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            server_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server_tls.load_cert_chain(p / "cert.pem", p / "key.pem")
            bridge = Bridge("test-token", allow_loopback_for_tests=True)
            async with serve(bridge.handler, "127.0.0.1", 0, ssl=server_tls) as server:
                url = f"wss://127.0.0.1:{server.sockets[0].getsockname()[1]}/bridge"
                with self.assertRaises(ssl.SSLCertVerificationError):
                    await asyncio.open_connection("127.0.0.1", server.sockets[0].getsockname()[1], ssl=ssl.create_default_context())
                client_tls = ssl.create_default_context(cafile=str(p / "cert.pem"))
                async with connect(url, ssl=client_tls) as ws:
                    challenge = json.loads(await ws.recv())
                    await ws.send(json.dumps(dict(type="auth", protocol=1, proof=proof("test-token", challenge["nonce"]))))
                    self.assertEqual("auth_ok", json.loads(await ws.recv())["type"])


if __name__ == "__main__":
    unittest.main()
