import socket
import ssl

hosts = [
    ("BE72", "47.104.22.0", 64725),
    ("NAS", "47.104.22.0", 64186),
    ("BE50", "47.104.22.0", 64979),
]

for name, host, port in hosts:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(3)
    try:
        s.connect((host, port))
        # peek banner
        banner = b""
        try:
            s.settimeout(2)
            banner = s.recv(1024)
        except Exception:
            pass
        print(f"{name} ({host}:{port}) -> OPEN! Banner: {banner[:80]}")
    except Exception as e:
        print(f"{name} ({host}:{port}) -> FAILED: {e}")
    finally:
        s.close()
