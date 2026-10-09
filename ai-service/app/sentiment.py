"""One isolated CPU worker; bounded calls, no downloads during inference."""
import asyncio
import contextlib
import json
import multiprocessing
import os
import shutil
import unicodedata
from pathlib import Path
import regex
from .config import MODEL_REVISION, SEGMENTER_REVISION

LABELS = {0: "NEGATIVE", 1: "POSITIVE", 2: "NEUTRAL"}
RAW_LABELS = {0: "NEG", 1: "POS", 2: "NEU"}

def segment_preserving_symbols(text, segmenter):
    # pyjnius 1.7 can truncate non-BMP characters sent to Java String.
    # Keep symbol graphemes in their original order, outside the Java bridge.
    pieces, buffer = [], []
    def flush():
        if buffer:
            pieces.extend(segmenter.word_segment("".join(buffer)))
            buffer.clear()
    for grapheme in regex.findall(r"\X", unicodedata.normalize("NFC", text)):
        if any(ord(c) > 0xFFFF or unicodedata.category(c) == "So"
               or c in ("\u200d", "\ufe0f") for c in grapheme):
            flush()
            pieces.append(grapheme)
        else:
            buffer.append(grapheme)
    flush()
    return " ".join(pieces)

class ModelFailure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)

def _worker(connection, settings):
    with open(os.devnull, "w") as sink, contextlib.redirect_stdout(sink), contextlib.redirect_stderr(sink):
        try:
            # pyjnius needs JAVA_HOME even when java.exe is already on PATH.
            if not os.environ.get("JAVA_HOME"):
                java = shutil.which("java")
                if java:
                    home = Path(java).resolve().parent.parent
                    if any((home / folder / "server" / "jvm.dll").is_file() for folder in ("bin", "lib")):
                        os.environ["JAVA_HOME"] = str(home)
            import torch
            import py_vncorenlp
            from transformers import AutoTokenizer, AutoModelForSequenceClassification
            manifest = json.loads((settings.cache_root / "manifest.json").read_text(encoding="utf8"))
            if manifest["modelRevision"] != MODEL_REVISION or manifest["segmenterRevision"] != SEGMENTER_REVISION:
                raise ValueError("Manifest mismatch")
            torch.set_num_threads(2)
            segmenter = py_vncorenlp.VnCoreNLP(annotators=["wseg"],
                save_dir=str(settings.segmenter_dir), max_heap_size="-Xmx256m")
            tokenizer = AutoTokenizer.from_pretrained(settings.model_dir, use_fast=False,
                local_files_only=True, trust_remote_code=False)
            model = AutoModelForSequenceClassification.from_pretrained(settings.model_dir,
                local_files_only=True, trust_remote_code=False, weights_only=True, low_cpu_mem_usage=True)
            if {int(k): v for k, v in model.config.id2label.items()} != RAW_LABELS or tokenizer.model_max_length != 256:
                raise ValueError("Unexpected label/tokenizer contract")
            model.eval()
            maximum = min(tokenizer.model_max_length,
                          model.config.max_position_embeddings - model.config.pad_token_id - 1)
            connection.send({"ready": True, "maxTokens": maximum})
        except Exception:
            connection.send({"ready": False, "errorCode": "MODEL_NOT_READY"})
            return
        while True:
            try:
                text = connection.recv()
            except EOFError:
                return
            if text is None:
                return
            try:
                normalized = unicodedata.normalize("NFC", text)
                segmented = segment_preserving_symbols(normalized, segmenter)
                if any(c not in segmented for c in normalized if unicodedata.category(c) == "So"):
                    raise ValueError("Segmentation lost a symbol")
                inputs = tokenizer(segmented, return_tensors="pt", truncation=False)
                count = int(inputs["input_ids"].shape[1])
                if count > maximum:
                    connection.send({"sentimentLabel": None, "errorCode": "INPUT_TOO_LONG", "tokenCount": count})
                    continue
                with torch.inference_mode():
                    logits = model(**inputs).logits
                if tuple(logits.shape) != (1, 3) or not torch.isfinite(logits).all().item():
                    raise ValueError("Invalid output")
                connection.send({"sentimentLabel": LABELS[int(logits.argmax(dim=-1).item())],
                                 "errorCode": None, "tokenCount": count})
            except Exception:
                connection.send({"errorCode": "MODEL_OUTPUT_INVALID"})

class SentimentRunner:
    def __init__(self, settings):
        self.settings = settings
        self.ready = False
        self.max_tokens = None
        self._process = None
        self._connection = None
        self._lock = asyncio.Lock()

    async def start(self):
        ctx = multiprocessing.get_context("spawn")
        parent, child = ctx.Pipe()
        self._connection = parent
        self._process = ctx.Process(target=_worker, args=(child, self.settings), daemon=True)
        self._process.start()
        child.close()
        try:
            result = await asyncio.to_thread(self._receive, self.settings.startup_timeout)
            self.ready = result.get("ready") is True
            self.max_tokens = result.get("maxTokens")
            if not self.ready:
                self.close()
        except Exception:
            self.close()

    def _receive(self, timeout):
        if not self._connection.poll(timeout):
            raise ModelFailure("MODEL_TIMEOUT")
        return self._connection.recv()

    def is_ready(self):
        return self.ready and self._process is not None and self._process.is_alive()

    async def analyze(self, comment):
        if not self.is_ready():
            self.ready = False
            raise ModelFailure("MODEL_NOT_READY")
        try:
            await asyncio.wait_for(self._lock.acquire(), timeout=self.settings.queue_timeout)
        except TimeoutError:
            raise ModelFailure("MODEL_BUSY") from None
        try:
            if not self.is_ready():
                raise ModelFailure("MODEL_NOT_READY")
            self._connection.send(comment)
            result = await asyncio.to_thread(self._receive, self.settings.inference_timeout)
            if not isinstance(result, dict):
                raise ModelFailure("MODEL_OUTPUT_INVALID")
            if result.get("errorCode") == "MODEL_OUTPUT_INVALID":
                raise ModelFailure("MODEL_OUTPUT_INVALID")
            if result.get("errorCode") == "INPUT_TOO_LONG":
                return result
            if result.get("sentimentLabel") not in LABELS.values() or result.get("errorCode") is not None:
                raise ModelFailure("MODEL_OUTPUT_INVALID")
            return result
        except asyncio.CancelledError:
            # A cancelled request must not leave a response for the next caller.
            self.close()
            raise
        except (EOFError, BrokenPipeError, OSError):
            self.close()
            raise ModelFailure("MODEL_NOT_READY") from None
        except ModelFailure as error:
            if error.code == "MODEL_TIMEOUT":
                self.close()
            raise
        finally:
            self._lock.release()

    def close(self):
        self.ready = False
        if self._process is not None and self._process.is_alive():
            self._process.terminate()
            self._process.join(timeout=5)
            if self._process.is_alive():
                self._process.kill()
                self._process.join(timeout=2)
        if self._connection is not None:
            self._connection.close()
