"""Original compatible console entry point; shared WSS core."""
import argparse
import asyncio
import base64
import json
import pathlib
import uuid
from core import Bridge, BridgeService, initialize, lan, proof

async def console(bridge, save_screenshots):
    print('命令：status / ui / shot / home / back / task / cancel / sleep YYYY-MM-DD / extract YYYY-MM-DD')
    print('其他命令可输入 JSON，例如 {"method":"click_node","params":{"snapshot_id":"...","node_id":"0/1"}}；exit 退出。')
    names = dict(status="device_status", ui="get_ui_tree", shot="get_screenshot", home="go_home", back="go_back", task="get_task_status", cancel="cancel_task")
    while True:
        try:
            line = (await asyncio.to_thread(input, "Evara> ")).strip()
            if not line:
                continue
            if line == "exit":
                return
            if line.startswith("{"):
                o = json.loads(line)
                method, params = o["method"], o.get("params", {})
            elif line.startswith("sleep ") or line.startswith("extract "):
                command, date = line.split(maxsplit=1)
                method, params = ("start_sleep_task" if command == "sleep" else "extract_current_sleep"), dict(date=date)
            else:
                method, params = names[line], {}
            result = await bridge.request(method, params)
            data = result.get("data", {})
            if "base64" in data:
                picture = data.pop("base64")
                data["image_bytes"] = len(base64.b64decode(picture))
                if save_screenshots:
                    save_screenshots.mkdir(parents=True, exist_ok=True)
                    destination = save_screenshots / (uuid.uuid4().hex + ".jpg")
                    destination.write_bytes(base64.b64decode(picture))
                    data["exported_to"] = str(destination)
                else:
                    data["image_storage"] = "未保存；仅在内存中接收"
            print(json.dumps(result, ensure_ascii=False, indent=2))
        except EOFError:
            return
        except (ConnectionError, TimeoutError, ValueError, KeyError) as error:
            print(type(error).__name__, str(error))


async def run(folder, export):
    service = BridgeService(lambda kind, detail: print(detail, flush=True))
    await service.start(folder)
    try:
        await console(service.bridge, export)
    finally:
        await service.stop()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["init", "serve"])
    parser.add_argument("--dir", type=pathlib.Path, default=pathlib.Path(__file__).parent / "private")
    parser.add_argument("--ip")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--name", default="我的 Windows 电脑")
    parser.add_argument("--save-screenshots", type=pathlib.Path, help="用户主动开启截图导出，默认仅在内存接收")
    args = parser.parse_args()
    if args.command == "init":
        p = initialize(args.dir, args.ip or "", args.port, args.name)
        print("配对资料已生成，请妥善保管；证书 SHA-256：", p["certificate_sha256"])
    else:
        try:
            asyncio.run(run(args.dir, args.save_screenshots))
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
