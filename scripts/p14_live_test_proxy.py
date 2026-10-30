"""Disposable CI-only TCP bridge from emulator host alias to the isolated test Server.

No credentials, request parsing, public listener or production endpoint.
"""
import select
import socket
import socketserver

class Bridge(socketserver.BaseRequestHandler):
    def handle(self):
        with socket.create_connection(("100.84.55.11", 18144), timeout=15) as upstream:
            upstream.settimeout(None)
            self.request.settimeout(None)
            peers = [self.request, upstream]
            while True:
                ready, _, _ = select.select(peers, [], [], 180)
                if not ready:
                    return
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (upstream if source is self.request else self.request).sendall(data)

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

if __name__ == "__main__":
    with Server(("127.0.0.1", 18144), Bridge) as server:
        server.serve_forever()
