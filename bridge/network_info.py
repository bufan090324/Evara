from PySide6.QtNetwork import QNetworkInterface, QAbstractSocket
from core import lan


def classify(name, type_name):
    name = name.lower()
    if any(word in name for word in ("vpn", "tap", "tun", "wireguard", "tailscale", "zerotier", "oray", "clash")):
        return "VPN/隧道（按名称识别）"
    if type_name == "Virtual" or any(word in name for word in ("virtual", "vmware", "vbox", "vethernet", "hyper-v", "虚拟")):
        return "虚拟网卡"
    if type_name == "Wifi" or any(word in name for word in ("wlan", "wi-fi", "wireless", "无线")):
        return "无线"
    if type_name == "Ethernet":
        return "有线"
    return "类型不确定"


def interfaces():
    items = []
    for interface in QNetworkInterface.allInterfaces():
        flags = interface.flags()
        if flags & QNetworkInterface.InterfaceFlag.IsLoopBack or not flags & QNetworkInterface.InterfaceFlag.IsUp:
            continue
        kind = classify(interface.humanReadableName() + " " + interface.name(), interface.type().name)
        for entry in interface.addressEntries():
            ip = entry.ip().toString()
            if entry.ip().protocol() == QAbstractSocket.NetworkLayerProtocol.IPv4Protocol and lan(ip):
                items.append(dict(ip=ip, name=interface.humanReadableName(), kind=kind, prefix=entry.prefixLength()))
    return items


def recommendation(items):
    physical = [item for item in items if item["kind"] in {"有线", "无线"}]
    return physical[0]["ip"] if len(physical) == 1 else None
