"""Explicit one-time download. Runtime never downloads models."""
import hashlib
import json
import os
import urllib.request
from pathlib import Path
from app.config import Settings, MODEL_ID, MODEL_REVISION, SEGMENTER_REVISION


def main():
    os.environ["HF_HUB_DISABLE_XET"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    from huggingface_hub import snapshot_download
    settings = Settings.from_env()
    settings.cache_root.mkdir(parents=True, exist_ok=True)
    snapshot_download(repo_id=MODEL_ID, revision=MODEL_REVISION, token=False,
        local_dir=str(settings.model_dir), max_workers=2,
        allow_patterns=["*.json", "vocab.txt", "bpe.codes", "pytorch_model.bin", "README.md"])
    resources = ["VnCoreNLP-1.2.jar", "models/wordsegmenter/vi-vocab",
                 "models/wordsegmenter/wordsegmenter.rdr", "LICENSE.md"]
    checksums = {}
    for name in resources:
        path = settings.segmenter_dir / name
        path.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://raw.githubusercontent.com/vncorenlp/VnCoreNLP/{SEGMENTER_REVISION}/{name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        path.write_bytes(data)
        checksums[name] = hashlib.sha256(data).hexdigest()
    config = json.loads((settings.model_dir / "config.json").read_text(encoding="utf8"))
    assert config["id2label"] == {"0": "NEG", "1": "POS", "2": "NEU"}
    assert json.loads((settings.model_dir / "tokenizer_config.json").read_text())["model_max_length"] == 256
    manifest = {"modelId": MODEL_ID, "modelRevision": MODEL_REVISION,
                "segmenterRevision": SEGMENTER_REVISION, "segmenterSha256": checksums}
    (settings.cache_root / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf8")
    print("Pinned model/tokenizer and word segmenter downloaded; manifest verified.")


if __name__ == "__main__":
    main()
