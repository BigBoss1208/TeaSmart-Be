import json,sys
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from rag_app.retrieval import VectorStore, embed, chunks, MODEL
from rag_app.main import create_app, private_safe, validate_output
from rag_app.provider import LlmProvider, RagFailure
SOURCES=Path(__file__).resolve().parents[1]/"rag_app/knowledge/sources.json"
class FakeProvider:
    configured=True
    def __init__(self): self.seen=None
    def generate(self,question,history,passages):
        self.seen=(question,history,passages)
        p=passages[0]
        return {"paragraphs":[{"text":"Tham khảo tài liệu đã truy xuất.","sourceId":p["id"],"quote":p["text"][:50]}]}
@pytest.fixture
def store(tmp_path):
    s=VectorStore(tmp_path/"index.sqlite3",SOURCES);s.rebuild();return s
def test_vectors_are_normalized_deterministic_and_accent_insensitive():
    assert embed("chè Tân Cương")==embed("che tan cuong")
    assert abs(sum(v*v for v in embed("pha chè").values())-1)<1e-9
    assert embed("")=={}
def test_chunking_and_rebuild_are_bounded_and_repeatable(store):
    assert all(len(x)<=450 for x in chunks("chè "*400))
    first=store.search("bảo quản trà khô ẩm oxy");store.rebuild();assert store.search("bảo quản trà khô ẩm oxy")==first
@pytest.mark.parametrize("query,source",[("pha trà nước thời gian hãm đắng","brewing"),("bảo quản trà khô ẩm oxy","tea-storage"),("Tân Cương La Bằng Trại Cài","tn-regions"),("thu hái làm héo diệt men","tn-culture")])
def test_retrieval_returns_real_source(store,query,source):
    assert source in [r["sourceId"] for r in store.search(query)]
    assert all(r["url"].startswith("https://") and r["accessedAt"]==next(d["accessedAt"] for d in json.loads(SOURCES.read_text(encoding="utf-8")) if d["id"]==r["sourceId"]) for r in store.search(query))
def test_service_roundtrip_with_explicit_fake_provider(store):
    provider=FakeProvider()
    with TestClient(create_app(provider,store)) as client:
        r=client.post("/answer",json={"question":"Pha trà thế nào?","history":["Tôi thích trà nhẹ"]})
        assert r.status_code==200 and r.json()["llmUsed"] is True
        assert r.json()["sources"] and r.json()["embeddingMethod"]==MODEL
        assert "Tôi thích trà nhẹ" in provider.seen[1]
def test_missing_configuration_is_503_not_fake_success(store,monkeypatch):
    for key in ["RAG_LLM_URL","RAG_LLM_MODEL","RAG_LLM_API_KEY"]:monkeypatch.delenv(key,raising=False)
    with TestClient(create_app(LlmProvider(),store)) as client:
        assert client.get("/health").json()["llmConfigured"] is False
        assert client.post("/answer",json={"question":"pha trà"}).status_code==503
@pytest.mark.parametrize("body",[{"question":""},{"question":"a"*1001},{"question":"chè","userId":1},{"question":"chè","history":["x"]*7},{"question":3}])
def test_invalid_contract(store,body):
    with TestClient(create_app(FakeProvider(),store)) as client: assert client.post("/answer",json=body).status_code==400
def test_sensitive_input_is_rejected_or_redacted():
    with pytest.raises(RagFailure):private_safe("Bearer fixture-token")
    assert "fixture@example.invalid" not in private_safe("Email fixture@example.invalid và 0912345678")
    assert "0912345678" not in private_safe("liên hệ 0912345678")
def test_untrusted_output_cannot_invent_sources_or_business_figures(store):
    passages=store.search("pha trà")
    for result in [{"paragraphs":[{"text":"abc","sourceId":"invented","quote":"source invented"}]},{"paragraphs":[{"text":"Giá là 500000 đồng","sourceId":passages[0]["id"],"quote":passages[0]["text"][:50]}]},{"answer":"wrong schema"}]:
        with pytest.raises(RagFailure):validate_output(result,passages)
def test_unrelated_query_returns_no_evidence_without_llm(store):
    with TestClient(create_app(FakeProvider(),store)) as client:
        r=client.post("/answer",json={"question":"zxqvwxjkk"})
        assert r.status_code==200 and not r.json()["llmUsed"] and not r.json()["sources"]
def test_body_size_is_bounded(store):
    with TestClient(create_app(FakeProvider(),store)) as client: assert client.post("/answer",content="x"*17000).status_code==413


def test_unverified_legacy_note_is_not_indexed(store):
    import sqlite3
    with sqlite3.connect(store.path) as db:
        assert db.execute("SELECT COUNT(*) FROM chunks WHERE source_id='plant-health'").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM documents").fetchone()[0] == 11

def test_invalid_source_is_rejected_before_replacing_index(tmp_path):
    path=tmp_path/"invalid.json"
    path.write_text(json.dumps([{"id":"x","title":"x","url":"http://invalid","publisher":"x","accessedAt":"2026-11-16","text":"x"}]),encoding="utf-8")
    with pytest.raises(ValueError): VectorStore(tmp_path/"i.db",path).rebuild()


def test_thai_nguyen_brewing_retrieves_local_practice_not_region_or_processing(store):
    hits=store.search("Cách pha chè Thái Nguyên như thế nào?")
    assert "tn-brewing-practice" in [p["sourceId"] for p in hits]
    assert not {"tn-regions-other","green-processing","green-types"}.intersection(p["sourceId"] for p in hits)

def test_empty_generation_is_distinct_from_empty_retrieval(store):
    passages=store.search("Cách pha chè Thái Nguyên")
    result=validate_output({"paragraphs":[]},passages)
    assert result["evidenceStatus"]=="MODEL_ABSTAINED" and result["llmUsed"] is True
    assert result["sources"]==[] and result["method"]=="RAG_NO_EVIDENCE"

def test_supported_partial_answer_cites_only_its_actual_source(store):
    passages=store.search("Cách pha chè Thái Nguyên")
    p=next(p for p in passages if p["sourceId"]=="tn-brewing-practice")
    result=validate_output({"paragraphs":[{"text":"Bạn có thể làm nóng trà cụ và tráng ấm trước khi pha. Nguồn không đưa một công thức nhiệt độ chung.","sourceId":p["id"],"quote":"làm nóng trà cụ và tráng ấm bằng nước sôi"}]},passages)
    assert result["method"]=="RAG_LLM_V1" and result["evidenceStatus"]=="CITED_EVIDENCE"
    assert [s["id"] for s in result["sources"]]==[p["id"]]

def test_unrelated_passage_cannot_be_cited_with_a_brewing_quote(store):
    p=store.search("Tân Cương La Bằng Trại Cài")[0]
    with pytest.raises(RagFailure):validate_output({"paragraphs":[{"text":"Pha chè ở 80 độ.","sourceId":p["id"],"quote":"làm nóng trà cụ và tráng ấm"}]},[p])
