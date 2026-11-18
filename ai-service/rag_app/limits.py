"""Dispatch budgets for one RAG process."""
from collections import deque
from contextlib import contextmanager
from datetime import datetime, timezone
from threading import Lock
from time import monotonic


class DispatchRejected(Exception):
    def __init__(self, code, diagnostic_code):
        super().__init__(code)
        self.diagnostic_code = diagnostic_code


class DispatchLimits:
    def __init__(self, *, run_limit=None, clock=monotonic, utc_day=None):
        self.run_limit = run_limit
        self.clock = clock
        self.utc_day = utc_day or (lambda: datetime.now(timezone.utc).date())
        self.lock = Lock()
        self.minute = deque()
        self.conversations = {}
        self.day = None
        self.daily_count = 0
        self.run_count = 0
        self.active = False

    def describe(self):
        return {
            "perMinute": 5,
            "perUtcDay": 30,
            "perConversation": 8,
            "concurrent": 1,
            "runLimit": self.run_limit,
        }

    @contextmanager
    def dispatch(self, conversation):
        with self.lock:
            if self.active:
                raise DispatchRejected("RAG_BUSY", "RAG_CONCURRENT_LIMIT")
            now, day = self.clock(), self.utc_day()
            while self.minute and now - self.minute[0] >= 60:
                self.minute.popleft()
            if day != self.day:
                self.day = day
                self.daily_count = 0
            # An idle conversation expires after 24 hours. UTC midnight only
            # resets the application daily budget, not an ongoing conversation.
            self.conversations = {
                key: entry for key, entry in self.conversations.items() if now - entry[1] < 86400
            }
            conversation_count = self.conversations.get(conversation, (0, now))[0]
            for exceeded, diagnostic in (
                (len(self.minute) >= 5, "RAG_MINUTE_LIMIT"),
                (self.daily_count >= 30, "RAG_DAY_LIMIT"),
                (conversation_count >= 8, "RAG_CONVERSATION_LIMIT"),
                (self.run_limit is not None and self.run_count >= self.run_limit, "RAG_RUN_LIMIT"),
            ):
                if exceeded:
                    raise DispatchRejected("RAG_DEMO_LIMIT_REACHED", diagnostic)

            self.minute.append(now)
            self.daily_count += 1
            self.conversations[conversation] = (conversation_count + 1, now)
            self.run_count += 1
            self.active = True

        try:
            yield
        finally:
            with self.lock:
                self.active = False
