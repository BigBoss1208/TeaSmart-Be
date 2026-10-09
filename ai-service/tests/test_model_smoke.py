"""Opt-in, real local CPU inference. No quality benchmark is claimed."""
import os
import pytest
from fastapi.testclient import TestClient
from app.main import create_app

pytestmark = pytest.mark.skipif(os.environ.get("RUN_MODEL_SMOKE") != "1",
                                reason="Explicit real-model smoke requires downloaded cache")

def test_real_model_and_preprocessing():
    with TestClient(create_app()) as client:
        assert client.get("/health").status_code == 200
        samples = [
            ("positive", "Chè rất ngon, thơm và chất lượng tuyệt vời.", 5),
            ("neutral", "Tôi đã nhận được gói chè hôm qua.", 3),
            ("negative", "Chè quá tệ, rất thất vọng, không ngon chút nào.", 1),
            ("no_diacritics", "Che rat ngon, giao hang nhanh.", 5),
            ("emoji", "Chè thơm 😊", 5),
            ("negation", "Chè không ngon.", 1),
            ("short", "Ổn.", 3),
        ]
        for name, comment, rating in samples:
            response = client.post("/analyze", json={"comment": comment, "rating": rating})
            assert response.status_code == 200, (name, response.json())
            body = response.json()
            assert body["processingStatus"] == "COMPLETED", (name, body)
            assert body["sentimentLabel"] in ("POSITIVE", "NEGATIVE", "NEUTRAL")
            assert body["confidence"] is None
            print(name, body["sentimentLabel"])
        response = client.post("/analyze", json={"comment": "chè " * 400, "rating": 3})
        assert response.status_code == 200
        assert response.json()["errorCode"] == "INPUT_TOO_LONG"
        assert response.json()["processingStatus"] == "SKIPPED"
