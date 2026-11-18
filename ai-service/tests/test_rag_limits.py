import json
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import httpx
import pytest

from rag_app.limits import DispatchLimits, DispatchRejected
from rag_app.provider import LlmProvider, RagFailure


class Clock:
    now = 0
    day = 1

    def limits(self, **kwargs):
        return DispatchLimits(clock=lambda: self.now, utc_day=lambda: self.day, **kwargs)


def dispatch(limits, conversation):
    with limits.dispatch(conversation):
        pass


def test_app_rolling_minute_and_utc_day_budgets():
    clock = Clock()
    limits = clock.limits()
    for batch in range(6):
        clock.now = 60 * batch
        for session in range(5):
            dispatch(limits, session)
        with pytest.raises(DispatchRejected, match="RAG_DEMO_LIMIT_REACHED") as error:
            dispatch(limits, "other")
        assert error.value.diagnostic_code == "RAG_MINUTE_LIMIT"
    clock.now += 60
    with pytest.raises(DispatchRejected, match="RAG_DEMO_LIMIT_REACHED") as error:
        dispatch(limits, "other")
    assert error.value.diagnostic_code == "RAG_DAY_LIMIT"
    clock.day += 1
    dispatch(limits, "other")


def test_conversation_cap_survives_utc_midnight_and_other_conversation_can_continue():
    clock = Clock()
    limits = clock.limits()
    for turn in range(8):
        clock.now = turn * 60
        dispatch(limits, "same")
    clock.day += 1
    clock.now += 60
    with pytest.raises(DispatchRejected, match="RAG_DEMO_LIMIT_REACHED") as error:
        dispatch(limits, "same")
    assert error.value.diagnostic_code == "RAG_CONVERSATION_LIMIT"
    dispatch(limits, "different")


def test_dispatch_is_atomic_and_busy_does_not_consume_a_turn():
    limits = Clock().limits(run_limit=2)
    started, finish = Event(), Event()

    def in_flight():
        with limits.dispatch("first"):
            started.set()
            assert finish.wait(5)

    with ThreadPoolExecutor(max_workers=1) as pool:
        running = pool.submit(in_flight)
        try:
            assert started.wait(5)
            with pytest.raises(DispatchRejected, match="RAG_BUSY"):
                dispatch(limits, "other")
        finally:
            finish.set()
        running.result()
    dispatch(limits, "other")
    with pytest.raises(DispatchRejected, match="RAG_DEMO_LIMIT_REACHED"):
        dispatch(limits, "third")


def test_timeout_is_counted_right_before_the_only_http_attempt(monkeypatch):
    monkeypatch.setenv("RAG_LLM_URL","https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
    monkeypatch.setenv("RAG_LLM_MODEL","gemini-3.5-flash-lite")
    monkeypatch.setenv("RAG_LLM_PROVIDER","gemini")
    monkeypatch.setenv("RAG_LLM_API_KEY","fixture-secret")
    monkeypatch.setenv("RAG_LLM_CALLS_ENABLED","true")
    monkeypatch.setenv("RAG_LLM_RUN_LIMIT","1")
    original = httpx.Client
    seen = []
    def timeout(request):
        seen.append(request)
        raise httpx.ReadTimeout("fixture")
    monkeypatch.setattr(httpx,"Client",lambda **kwargs: original(transport=httpx.MockTransport(timeout),**kwargs))

    provider = LlmProvider()
    with pytest.raises(RagFailure) as error:
        provider.generate("Cách pha chè?", [], [], conversation="scope")
    assert (error.value.status, error.value.code) == (504,"RAG_TIMEOUT")
    with pytest.raises(RagFailure) as error:
        provider.generate("Cách bảo quản chè?", [], [], conversation="another scope")
    assert (error.value.status, error.value.code) == (429,"RAG_DEMO_LIMIT_REACHED")
    assert error.value.diagnostic_code == "RAG_RUN_LIMIT"
    assert len(seen) == 1
    payload = json.loads(seen[0].content)
    assert payload["max_tokens"] == 900
    assert "scope" not in seen[0].content.decode()
    assert "fixture-secret" not in seen[0].content.decode()
