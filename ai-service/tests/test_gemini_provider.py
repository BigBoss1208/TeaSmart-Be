"""Offline transport contract tests. No Google request or real API key is used."""
import json
import httpx
import pytest
from rag_app.provider import LlmProvider, RagFailure

@pytest.fixture
def gemini(monkeypatch):
    for name,value in {"RAG_LLM_PROVIDER":"gemini","RAG_LLM_URL":"https://generativelanguage.googleapis.com/v1beta/openai/chat/completions","RAG_LLM_MODEL":"gemini-3.5-flash-lite","RAG_LLM_API_KEY":"unit-test-not-a-real-key"}.items():monkeypatch.setenv(name,value)
    monkeypatch.delenv("RAG_LLM_CALLS_ENABLED",raising=False)
    return monkeypatch

def test_key_alone_never_authorizes_provider_call(gemini):
    gemini.setattr(httpx,"Client",lambda **kwargs: (_ for _ in ()).throw(AssertionError("Network must not be opened")))
    with pytest.raises(RagFailure) as error:LlmProvider().generate("pha chè",[],[])
    assert error.value.code=="RAG_CALLS_DISABLED"

def transport(gemini,handler):
    original=httpx.Client
    gemini.setenv("RAG_LLM_CALLS_ENABLED","true")
    gemini.setattr(httpx,"Client",lambda **kwargs:original(transport=httpx.MockTransport(handler),**kwargs))

def test_gemini_compatible_payload_uses_selected_model_without_key_in_json(gemini):
    seen=[]
    def handler(request):
        seen.append(request)
        return httpx.Response(200,json={"choices":[{"message":{"content":json.dumps({"paragraphs":[]})}}]})
    transport(gemini,handler)
    assert LlmProvider().generate("cách pha chè",[],[])=={"paragraphs":[]}
    assert len(seen)==1
    body=json.loads(seen[0].content)
    assert body["model"]=="gemini-3.5-flash-lite"
    assert "reasoning_effort" not in body
    assert "temperature" not in body
    assert body["response_format"]=={"type":"json_object"}
    assert "unit-test-not-a-real-key" not in seen[0].content.decode()
    assert seen[0].headers["authorization"]=="Bearer unit-test-not-a-real-key"

@pytest.mark.parametrize("status,code",[(401,"RAG_PROVIDER_AUTH_REJECTED"),(403,"RAG_PROVIDER_AUTH_REJECTED"),(404,"RAG_MODEL_UNAVAILABLE"),(429,"RAG_RATE_LIMITED"),(503,"RAG_PROVIDER_UNAVAILABLE")])
def test_rejected_requests_do_not_retry_or_switch_model(gemini,status,code):
    seen=[]
    def handler(request):seen.append(request);return httpx.Response(status,json={"error":"private provider detail"})
    transport(gemini,handler)
    with pytest.raises(RagFailure) as error:LlmProvider().generate("chè",[],[])
    assert error.value.code==code
    assert len(seen)==1
    assert "private provider detail" not in str(error.value)

def test_gemini_key_cannot_be_sent_to_other_endpoint(gemini):
    gemini.setenv("RAG_LLM_CALLS_ENABLED","true")
    gemini.setenv("RAG_LLM_URL","https://example.invalid/chat/completions")
    with pytest.raises(RagFailure) as error:LlmProvider().generate("chè",[],[])
    assert error.value.code=="RAG_PROVIDER_CONFIG_INVALID"

def test_timeout_is_sanitized(gemini):
    def handler(request):raise httpx.ReadTimeout("private timeout detail")
    transport(gemini,handler)
    with pytest.raises(RagFailure) as error:LlmProvider().generate("chè",[],[])
    assert error.value.code=="RAG_TIMEOUT"
    assert "private timeout detail" not in str(error.value)

def test_legacy_model_is_explicit_without_calendar_based_block(gemini):
    gemini.setenv("RAG_LLM_MODEL","gemini-2.5-flash")
    seen=[]
    def handler(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200,json={"choices":[{"message":{"content":json.dumps({"paragraphs":[]})}}]})
    transport(gemini,handler)
    LlmProvider().generate("chè",[],[])
    assert seen[0]["model"]=="gemini-2.5-flash"
    assert seen[0]["reasoning_effort"]=="none"

def test_unapproved_model_never_silently_replaces_selected_model(gemini):
    gemini.setenv("RAG_LLM_MODEL","gemini-unapproved-model")
    gemini.setenv("RAG_LLM_CALLS_ENABLED","true")
    with pytest.raises(RagFailure) as error:LlmProvider().generate("chè",[],[])
    assert error.value.code=="RAG_PROVIDER_CONFIG_INVALID"
