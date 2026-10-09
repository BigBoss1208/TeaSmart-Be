import os
from dataclasses import dataclass
from pathlib import Path

MODEL_ID = "wonrax/phobert-base-vietnamese-sentiment"
MODEL_REVISION = "9076a5896971b5d551588fe8a51c722c89731d36"
SEGMENTER_REVISION = "62bbc58fe5d113c898eae112656be97dcf50b3a0"
PREPROCESSING_VERSION = "vncorenlp-wseg-nfc-preserve-symbols-v1"
RULE_VERSION = "rules-v1"
MODEL_VERSION = f"wonrax@{MODEL_REVISION};wseg@{SEGMENTER_REVISION};nfc-symbols-v1;{RULE_VERSION}"


@dataclass(frozen=True)
class Settings:
    cache_root: Path
    startup_timeout: float = 90.0
    inference_timeout: float = 10.0
    queue_timeout: float = 2.0
    max_body_bytes: int = 16384

    @classmethod
    def from_env(cls):
        root = Path(os.environ.get("AI_MODEL_CACHE", str(Path.home() / ".teasmart" / "review-ai"))).resolve()
        values = [float(os.environ.get(name, default)) for name, default in [
            ("AI_STARTUP_TIMEOUT_SECONDS", "90"),
            ("AI_INFERENCE_TIMEOUT_SECONDS", "10"),
            ("AI_QUEUE_TIMEOUT_SECONDS", "2")]]
        if not all(0 < value <= 300 for value in values):
            raise ValueError("Invalid AI timeout configuration")
        return cls(root, *values)

    @property
    def model_dir(self):
        return self.cache_root / "sentiment" / MODEL_REVISION

    @property
    def segmenter_dir(self):
        return self.cache_root / "vncorenlp" / SEGMENTER_REVISION
