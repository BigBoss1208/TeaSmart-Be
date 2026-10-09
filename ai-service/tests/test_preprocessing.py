import asyncio
import pytest
from app.config import Settings
from app.sentiment import segment_preserving_symbols, SentimentRunner, ModelFailure


class SegmenterDouble:
    def word_segment(self, text):
        assert all(ord(c) <= 0xFFFF for c in text)
        return [text]


@pytest.mark.parametrize("text", ["Chè thơm 😊", "Không ngon 👨‍👩‍👧‍👦", "Che 👍🏽", "Chè ❤️"])
def test_preserve_graphemes(text):
    result = segment_preserving_symbols(text, SegmenterDouble())
    assert result.replace(" ", "") == text.replace(" ", "")


def test_runner_not_ready(tmp_path):
    async def check():
        runner = SentimentRunner(Settings(tmp_path))
        with pytest.raises(ModelFailure, match="MODEL_NOT_READY"):
            await runner.analyze("Chè thơm")
    asyncio.run(check())


def test_worker_timeout_releases_lock_and_marks_not_ready(tmp_path):
    class ProcessDouble:
        alive = True
        def is_alive(self): return self.alive
        def terminate(self): self.alive = False
        def join(self, timeout): pass
    class ConnectionDouble:
        def send(self, text): pass
        def poll(self, timeout): return False
        def close(self): pass
    async def check():
        runner = SentimentRunner(Settings(tmp_path, inference_timeout=0.01))
        runner._process, runner._connection = ProcessDouble(), ConnectionDouble()
        runner.ready = True
        with pytest.raises(ModelFailure, match="MODEL_TIMEOUT"):
            await runner.analyze("Chè")
        assert not runner.ready and not runner._lock.locked()
    asyncio.run(check())
