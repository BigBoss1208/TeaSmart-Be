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


def test_retrieved_passages_reach_provider_and_paragraphs_survive_parser(gemini):
    passage={"id":"local:0","title":"Local brewing","text":"Warm the teapot before brewing."}
    result={"paragraphs":[{"text":"Warm the teapot.","sourceId":"local:0","quote":"Warm the teapot"}]}
    def handler(request):
        body=json.loads(request.content)
        context=json.loads(body["messages"][1]["content"])
        assert context["passages"]==[passage]
        return httpx.Response(200,json={"choices":[{"message":{"content":json.dumps(result)}}]})
    transport(gemini,handler)
    assert LlmProvider().generate("How to brew?",[],[passage])==result

@pytest.mark.parametrize("envelope",[{}, {"choices":[]}, {"choices":[{"message":{"content":"not json"}}]}])
def test_malformed_provider_envelope_is_not_reported_as_no_evidence(gemini,envelope):
    transport(gemini,lambda request:httpx.Response(200,json=envelope))
    with pytest.raises(RagFailure) as error:LlmProvider().generate("tea",[],[])
    assert error.value.code=="RAG_INVALID_OUTPUT" and error.value.status==502


@pytest.mark.parametrize("status,public",[(401,"RAG_PROVIDER_AUTH_REJECTED"),(403,"RAG_PROVIDER_AUTH_REJECTED"),(404,"RAG_MODEL_UNAVAILABLE"),(429,"RAG_RATE_LIMITED"),(503,"RAG_PROVIDER_UNAVAILABLE"),(500,"RAG_PROVIDER_UNAVAILABLE"),(302,"RAG_PROVIDER_UNAVAILABLE")])
def test_upstream_status_is_recorded_without_body_or_headers(gemini,caplog,status,public):
    seen=[]
    def handler(request):
        seen.append(request)
        return httpx.Response(status,json={"error":"private-body-fixture"},headers={"secret-header":"private-header-fixture"})
    transport(gemini,handler)
    with pytest.raises(RagFailure) as caught:LlmProvider().generate("private-question-fixture",[],[])
    error=caught.value
    assert error.code==public and error.status==503
    assert error.diagnostic_code==f"RAG_UPSTREAM_HTTP_{status}" and error.upstream_status==status
    assert len(seen)==1
    assert f"diagnostic_code=RAG_UPSTREAM_HTTP_{status}" in caplog.text
    assert f"upstream_status={status}" in caplog.text
    for secret in ("private-body-fixture","private-header-fixture","private-question-fixture","unit-test-not-a-real-key","Authorization"):
        assert secret not in caplog.text and secret not in str(error)

@pytest.mark.parametrize("kind,diagnostic,public,status",[
    ("dns","RAG_DNS_ERROR","RAG_PROVIDER_UNAVAILABLE",503),
    ("tls","RAG_TLS_ERROR","RAG_PROVIDER_UNAVAILABLE",503),
    ("connect","RAG_CONNECT_ERROR","RAG_PROVIDER_UNAVAILABLE",503),
    ("network","RAG_NETWORK_ERROR","RAG_PROVIDER_UNAVAILABLE",503),
    ("protocol","RAG_HTTP_PROTOCOL_ERROR","RAG_PROVIDER_UNAVAILABLE",503),
    ("transport","RAG_HTTP_TRANSPORT_ERROR","RAG_PROVIDER_UNAVAILABLE",503),
    ("connect_timeout","RAG_TIMEOUT_CONNECT","RAG_TIMEOUT",504),
    ("read_timeout","RAG_TIMEOUT_READ","RAG_TIMEOUT",504),
    ("write_timeout","RAG_TIMEOUT_WRITE","RAG_TIMEOUT",504),
    ("pool_timeout","RAG_TIMEOUT_POOL","RAG_TIMEOUT",504),
])
def test_transport_failures_have_evidence_based_diagnostics_and_no_retry(gemini,caplog,kind,diagnostic,public,status):
    import socket,ssl
    seen=[]
    types={"dns":httpx.ConnectError,"tls":httpx.ConnectError,"connect":httpx.ConnectError,"network":httpx.ReadError,"protocol":httpx.RemoteProtocolError,"transport":httpx.DecodingError,"connect_timeout":httpx.ConnectTimeout,"read_timeout":httpx.ReadTimeout,"write_timeout":httpx.WriteTimeout,"pool_timeout":httpx.PoolTimeout}
    def handler(request):
        seen.append(request)
        error=types[kind]("private-exception-fixture https://user:secret@example.invalid")
        if kind=="dns":error.__cause__=socket.gaierror(-2,"private-dns-fixture")
        if kind=="tls":error.__cause__=ssl.SSLError("private-tls-fixture")
        raise error
    transport(gemini,handler)
    with pytest.raises(RagFailure) as caught:LlmProvider().generate("private-question-fixture",[],[])
    error=caught.value
    assert (error.code,error.status,error.diagnostic_code,error.upstream_status)==(public,status,diagnostic,None)
    assert len(seen)==1 and diagnostic in caplog.text
    for secret in ("private-exception-fixture","private-dns-fixture","private-tls-fixture","private-question-fixture","user:secret","unit-test-not-a-real-key"):
        assert secret not in caplog.text and secret not in str(error)

@pytest.mark.parametrize("body",[b"not-json",b'{"choices":[]}',b'{"choices":[{"message":{"content":"not-json"}}]}'])
def test_invalid_response_has_separate_diagnostic(gemini,caplog,body):
    transport(gemini,lambda request:httpx.Response(200,content=body))
    with pytest.raises(RagFailure) as caught:LlmProvider().generate("tea",[],[])
    error=caught.value
    assert (error.code,error.status,error.diagnostic_code,error.upstream_status)==("RAG_INVALID_OUTPUT",502,"RAG_PROVIDER_RESPONSE_INVALID",200)
    assert "RAG_PROVIDER_RESPONSE_INVALID" in caplog.text

def test_internal_diagnostics_are_not_serialized_in_public_response(gemini):
    from fastapi.testclient import TestClient
    from rag_app.main import create_app
    class Store:
        def rebuild(self):pass
        def search(self,query):return [{"id":"local:0","title":"Brewing","text":"Warm the teapot."}]
    class Provider:
        configured=True
        def generate(self,*args,**kwargs):raise RagFailure("RAG_PROVIDER_UNAVAILABLE",503,diagnostic_code="RAG_UPSTREAM_HTTP_503",upstream_status=503)
    with TestClient(create_app(Provider(),Store())) as client:
        response=client.post("/answer",json={"question":"tea"})
    assert response.status_code==503 and response.json()=={"code":"RAG_PROVIDER_UNAVAILABLE"}


def test_stream_failure_keeps_observed_http_status(gemini,caplog):
    class BrokenStream(httpx.SyncByteStream):
        def __iter__(self):raise httpx.ReadError("private-stream-detail")
    transport(gemini,lambda request:httpx.Response(200,stream=BrokenStream()))
    with pytest.raises(RagFailure) as caught:LlmProvider().generate("tea",[],[])
    assert caught.value.diagnostic_code=="RAG_NETWORK_ERROR" and caught.value.upstream_status==200
    assert "private-stream-detail" not in caplog.text

def test_oversized_response_is_distinct_from_transport_failure(gemini,caplog):
    transport(gemini,lambda request:httpx.Response(200,content=b"x"*65537))
    with pytest.raises(RagFailure) as caught:LlmProvider().generate("tea",[],[])
    assert caught.value.code=="RAG_INVALID_OUTPUT" and caught.value.diagnostic_code=="RAG_PROVIDER_RESPONSE_TOO_LARGE"
    assert caught.value.upstream_status==200


def test_health_exposes_loaded_diagnostic_version_without_provider_call(gemini):
    from fastapi.testclient import TestClient
    from rag_app.main import create_app
    class Store:
        def rebuild(self):pass
    gemini.setattr(httpx,"Client",lambda **kwargs: (_ for _ in ()).throw(AssertionError("Provider network forbidden")))
    with TestClient(create_app(LlmProvider(),Store())) as client:
        health=client.get("/health").json()
    assert health["providerDiagnosticsVersion"]=="provider-errors-v1"
    assert health["providerCallsEnabled"] is False
