"""Real Uvicorn/HTTP smoke on a temporary localhost port; opt-in only."""
import os
import socket
import threading
import time
import httpx
import pytest
import uvicorn
from app.main import create_app

pytestmark = pytest.mark.skipif(os.environ.get("RUN_MODEL_SMOKE") != "1",
                                reason="Requires real model cache and local network")


def test_uvicorn_real_http_and_shutdown():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(create_app(), host="127.0.0.1", port=port,
                                          log_level="error", access_log=False))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [sock]}, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 100
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(0.1)
        assert server.started
        with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=15) as client:
            assert client.get("/health").json()["modelReady"] is True
            durations = []
            for index, comment in enumerate(["Chè thơm ngon.", "Chè rất tệ.", "Tôi nhận chè hôm qua."]):
                started = time.perf_counter()
                response = client.post("/analyze", json={"comment": comment, "rating": 3})
                durations.append((time.perf_counter() - started) * 1000)
                assert response.status_code == 200
                assert response.json()["processingStatus"] == "COMPLETED"
                assert response.json()["confidence"] is None
                print("http_sample", index, response.json()["sentimentLabel"])
            assert client.post("/analyze", json={"comment": None, "rating": 3}).json()["processingStatus"] == "SKIPPED"
            assert client.post("/analyze", json={"comment": "chè " * 400, "rating": 3}).json()["errorCode"] == "INPUT_TOO_LONG"
            assert client.post("/analyze", json={"comment": "x", "rating": 0}).status_code == 422
            print("CPU HTTP latency_ms (3 smoke samples, not benchmark):", [round(d, 1) for d in durations])
    finally:
        server.should_exit = True
        thread.join(timeout=15)
        sock.close()
        assert not thread.is_alive(), "Uvicorn failed to shut down"
    with socket.socket() as probe:
        assert probe.connect_ex(("127.0.0.1", port)) != 0
    print("Uvicorn stopped; temporary localhost port released")
