"""Read the fixed epoch-20 artifact and perform batched image inference."""

import hashlib
import json
import os
from pathlib import Path

import torch
from PIL import Image
from torch import nn
from torchvision import transforms
from torchvision.models import mobilenet_v2

MODEL_ID = "mobilenetv2-epoch20"
CHECKPOINT_SHA256 = "6c3ed706fee56e481d8820c1b74a207ab98b53c4615b5b55a8b381f593d5d10e"
CLASS_NAMES = (
    "1. Tea algal leaf spot",
    "2. Brown Blight",
    "3. Gray Blight",
    "4. Helopeltis",
    "5. Red spider",
    "6. Green mirid bug",
    "7. Healthy leaf",
)
CLASS_TO_IDX = {label: index for index, label in enumerate(CLASS_NAMES)}
IMAGE_SIZE = 224
IMAGE_MEAN = (0.485, 0.456, 0.406)
IMAGE_STD = (0.229, 0.224, 0.225)


class LeafPredictor:
    model_id = MODEL_ID
    class_names = CLASS_NAMES

    def __init__(self, model: nn.Module, device: torch.device, *, amp: bool):
        self.device = str(device)
        self._device = device
        self._model = model.to(device).eval()
        self._amp = amp and device.type == "cuda"
        self.prepare = transforms.Compose([
            transforms.Resize(
                (IMAGE_SIZE, IMAGE_SIZE),
                interpolation=transforms.InterpolationMode.BILINEAR,
                antialias=True,
            ),
            transforms.ToTensor(),
            transforms.Normalize(IMAGE_MEAN, IMAGE_STD),
        ])

    @torch.inference_mode()
    def predict(self, frames: list[torch.Tensor]) -> list[list[float]]:
        batch = torch.stack(frames).to(self._device)
        with torch.amp.autocast("cuda", dtype=torch.float16, enabled=self._amp):
            logits = self._model(batch)
        if logits.shape != (len(frames), len(self.class_names)) or not bool(logits.isfinite().all()):
            raise RuntimeError("Unexpected MobileNetV2 output.")
        return logits.float().softmax(dim=1).cpu().tolist()


def load_model() -> LeafPredictor:
    configured_model_dir = os.environ.get("LEAF_MODEL_DIR")
    model_dir = Path(configured_model_dir) if configured_model_dir else Path(__file__).resolve().parents[1] / "leaf-model"
    mapping = json.loads((model_dir / "class_to_idx.json").read_text(encoding="utf-8"))
    spec = json.loads((model_dir / "config.json").read_text(encoding="utf-8"))
    settings = spec["config"]
    if (
        mapping != CLASS_TO_IDX or any(type(index) is not int for index in mapping.values())
        or spec["class_to_idx"] != mapping or spec["model"] != "mobilenet_v2"
        or spec["checkpoint_format"] != 1
        or settings["image_size"] != IMAGE_SIZE
        or tuple(settings["imagenet_mean"]) != IMAGE_MEAN
        or tuple(settings["imagenet_std"]) != IMAGE_STD
        or type(settings["amp"]) is not bool
    ):
        raise ValueError("Model metadata does not match the verified Leaf experiment.")

    with (model_dir / "best_mobilenetv2.pth").open("rb") as checkpoint_file:
        if hashlib.file_digest(checkpoint_file, "sha256").hexdigest() != CHECKPOINT_SHA256:
            raise ValueError("Unexpected Leaf checkpoint SHA-256.")
        checkpoint_file.seek(0)
        checkpoint = torch.load(checkpoint_file, map_location="cpu", weights_only=True)
    if (
        (checkpoint["epoch"], checkpoint["best_epoch"], checkpoint["format"]) != (20, 20, 1)
        or checkpoint["class_to_idx"] != mapping
        or checkpoint["train_valid_sha256"] != spec["train_valid_sha256"]
    ):
        raise ValueError("Expected the Validation-selected epoch 20 checkpoint.")

    device_type = os.environ.get("LEAF_DEVICE", "auto").lower()
    if device_type == "auto":
        device_type = "cuda" if torch.cuda.is_available() else "cpu"
    if device_type not in ("cpu", "cuda"):
        raise ValueError("LEAF_DEVICE must be auto, cpu or cuda.")
    if device_type == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("Leaf CUDA was requested but is unavailable.")
    torch.set_num_threads(2)
    model = mobilenet_v2(weights=None)
    model.classifier[1] = nn.Linear(model.classifier[1].in_features, len(mapping))
    model.load_state_dict(checkpoint["model_state_dict"], strict=True)
    del checkpoint
    predictor = LeafPredictor(model, torch.device("cuda:0" if device_type == "cuda" else "cpu"),
                              amp=settings["amp"])
    # Fail readiness if this runtime cannot execute the selected model.
    predictor.predict([predictor.prepare(Image.new("RGB", (IMAGE_SIZE, IMAGE_SIZE)))])
    return predictor
