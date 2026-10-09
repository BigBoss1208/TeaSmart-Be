"""Contract tests use explicit doubles; these are not model evaluation."""
import pytest
from fastapi.testclient import TestClient
from app.main import create_app
from app.sentiment import ModelFailure

class ContractDouble:
    def __init__(self, label="POSITIVE", ready=True, failure=None, too_long=False):
        self.label, self.ready, self.failure, self.too_long = label, ready, failure, too_long
        self.calls = 0
    async def start(self):
        pass
    def close(self):
        self.ready = False
    def is_ready(self):
        return self.ready
    async def analyze(self, comment):
        self.calls += 1
        if not self.ready:
            raise ModelFailure("MODEL_NOT_READY")
        if self.failure:
            raise ModelFailure(self.failure)
        if self.too_long:
            return {"errorCode": "INPUT_TOO_LONG"}
        return {"sentimentLabel": self.label, "errorCode": None}

@pytest.mark.parametrize("label", ["POSITIVE", "NEUTRAL", "NEGATIVE"])
def test_output_contract(label):
    with TestClient(create_app(ContractDouble(label))) as client:
        response = client.post("/analyze", json={"comment": "Chè bình thường.", "rating": 3})
        assert response.status_code == 200
        body = response.json()
        assert body["sentimentLabel"] == label
        assert body["confidence"] is None
        assert body["processingStatus"] == "COMPLETED"
        assert body["analysisMethod"] == "MODEL"
        assert "runId" not in body and "inputHash" not in body

@pytest.mark.parametrize("comment", [None, "", "   ", "\n\t"])
def test_empty_does_not_call_model(comment):
    runner = ContractDouble(ready=False)
    with TestClient(create_app(runner)) as client:
        body = client.post("/analyze", json={"comment": comment, "rating": 3}).json()
        assert body["processingStatus"] == "SKIPPED"
        assert body["sentimentLabel"] is None and body["confidence"] is None
        assert body["needsReview"] is None
        assert runner.calls == 0

@pytest.mark.parametrize("body", [
    {"rating": 0}, {"rating": 6}, {"rating": None}, {"rating": "5"}, {"rating": True}, {},
    {"rating": 5, "comment": 123}, {"rating": 5, "comment": "a" * 2001},
    {"rating": 5, "userId": 1}, {"rating": 5, "image": "data"},
    {"rating": 5, "jwt": "not-accepted"},
])
def test_invalid_request(body):
    with TestClient(create_app(ContractDouble())) as client:
        response = client.post("/analyze", json=body)
        assert response.status_code == 422
        assert response.json() == {"errorCode": "INVALID_REQUEST"}

def test_malformed_body_and_limits():
    with TestClient(create_app(ContractDouble())) as client:
        assert client.post("/analyze", content="{", headers={"content-type": "application/json"}).status_code == 422
        assert client.post("/analyze", content="x").status_code == 415
        assert client.post("/analyze", content=" " * 16385, headers={"content-type": "application/json"}).status_code == 413
        assert client.get("/analyze").status_code == 405

def test_token_limit_is_not_completed():
    with TestClient(create_app(ContractDouble(too_long=True))) as client:
        body = client.post("/analyze", json={"rating": 5, "comment": "Chè " * 300}).json()
        assert body["processingStatus"] == "SKIPPED"
        assert body["errorCode"] == "INPUT_TOO_LONG"
        assert body["sentimentLabel"] is None and body["needsReview"] is None

@pytest.mark.parametrize("code,status", [
    ("MODEL_NOT_READY", 503), ("MODEL_BUSY", 503), ("MODEL_TIMEOUT", 504), ("MODEL_OUTPUT_INVALID", 502)])
def test_sanitized_failure(code, status):
    with TestClient(create_app(ContractDouble(failure=code))) as client:
        response = client.post("/analyze", json={"rating": 5, "comment": "Chè thơm."})
        assert response.status_code == status
        assert response.json() == {"errorCode": code}

def test_invalid_output():
    with TestClient(create_app(ContractDouble(label="INVALID"))) as client:
        assert client.post("/analyze", json={"rating": 3, "comment": "Chè."}).status_code == 502

@pytest.mark.parametrize("ready,status", [(True, 200), (False, 503)])
def test_health_reflects_readiness(ready, status):
    with TestClient(create_app(ContractDouble(ready=ready))) as client:
        response = client.get("/health")
        assert response.status_code == status and response.json()["modelReady"] is ready

def test_negative_not_automatically_flagged():
    with TestClient(create_app(ContractDouble(label="NEGATIVE"))) as client:
        body = client.post("/analyze", json={"rating": 1, "comment": "Chè không ngon."}).json()
        assert body["needsReview"] is False and body["moderationFlags"] == []
