"""Internal FastAPI endpoint for TeaSmart leaf uploads."""

import asyncio
import logging
import math
import re
import tempfile
from contextlib import asynccontextmanager
from statistics import fmean
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from .media import IMAGE_FORMATS, MEDIA_LIMITS, LeafError, prepare_image, prepare_video

LOGGER = logging.getLogger("uvicorn.error")
UPLOAD_TIMEOUT_SECONDS = 25
REFERENCE_NOTE = "Kết quả mô hình chỉ để tham khảo và không thay thế đánh giá chuyên môn."


class ClassProbability(BaseModel):
    class_index: int
    label: str
    probability: float


class FramePrediction(BaseModel):
    timestamp_seconds: float
    class_index: int
    label: str
    prediction_score: float


class VideoAgreement(BaseModel):
    duration_seconds: float
    sampled_frames: int
    agreeing_frames: int
    agreement_ratio: float
    frames: list[FramePrediction]


class Prediction(BaseModel):
    media_type: Literal["image", "video"]
    model_id: str
    class_index: int
    label: str
    prediction_score: float
    class_probabilities: list[ClassProbability]
    video: VideoAgreement | None
    warnings: list[str]
    notice: str


def load_predictor():
    # Import native Torch only at startup so readiness can report a controlled failure.
    from .inference import load_model

    return load_model()


def prediction_result(model, probabilities: list[list[float]], video=None) -> Prediction:
    labels = model.class_names
    expected = len(video.timestamps) if video is not None else 1
    if len(probabilities) != expected or len(labels) != 7 or any(
        len(row) != len(labels)
        or any(not math.isfinite(value) or not 0 <= value <= 1 for value in row)
        or not math.isclose(sum(row), 1.0, abs_tol=1e-5)
        for row in probabilities
    ):
        raise RuntimeError("Unexpected probabilities.")
    averaged = [fmean(column) for column in zip(*probabilities, strict=True)]
    winner = max(range(len(labels)), key=averaged.__getitem__)
    agreement = None
    warnings = []
    if video is not None:
        frames = []
        for timestamp, probability in zip(video.timestamps, probabilities, strict=True):
            frame_winner = max(range(len(labels)), key=probability.__getitem__)
            frames.append(FramePrediction(timestamp_seconds=timestamp, class_index=frame_winner,
                                          label=labels[frame_winner], prediction_score=probability[frame_winner]))
        agreeing = sum(frame.class_index == winner for frame in frames)
        if agreeing < len(frames):
            warnings.append("INCONSISTENT_VIDEO_PREDICTIONS")
        if len(frames) == 1:
            warnings.append("INSUFFICIENT_FRAMES_FOR_AGREEMENT")
        agreement = VideoAgreement(duration_seconds=video.duration_seconds, sampled_frames=len(frames),
                                   agreeing_frames=agreeing, agreement_ratio=agreeing / len(frames), frames=frames)
    return Prediction(
        media_type="video" if video is not None else "image",
        model_id=model.model_id,
        class_index=winner,
        label=labels[winner],
        prediction_score=averaged[winner],
        class_probabilities=[ClassProbability(class_index=index, label=label, probability=averaged[index])
                             for index, label in enumerate(labels)],
        video=agreement,
        warnings=warnings,
        notice=REFERENCE_NOTE,
    )


def analyze_upload(model, upload, mime: str) -> Prediction:
    video = None
    if mime in IMAGE_FORMATS:
        frames = [prepare_image(upload, mime, model.prepare)]
    else:
        video = prepare_video(upload, model.prepare)
        frames = video.frames
    try:
        return prediction_result(model, model.predict(frames), video)
    except (RuntimeError, MemoryError) as exc:
        LOGGER.warning("Leaf inference failed.", exc_info=True)
        raise LeafError(503, "INFERENCE_UNAVAILABLE", "Mô hình tạm thời không thể phân tích tệp.") from exc


def read_upload_headers(request: Request) -> tuple[str, int]:
    mime = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if mime not in MEDIA_LIMITS or request.headers.get("content-encoding", "identity").lower() != "identity":
        raise LeafError(415, "UNSUPPORTED_MEDIA_TYPE", "Chỉ chấp nhận nội dung JPG, JPEG, PNG hoặc MP4.")
    limit = MEDIA_LIMITS[mime]
    declared = request.headers.get("content-length")
    if declared is not None:
        if not re.fullmatch(r"[0-9]{1,20}", declared):
            raise LeafError(400, "INVALID_CONTENT_LENGTH", "Kích thước tệp khai báo không hợp lệ.")
        amount = int(declared)
        if amount == 0:
            raise LeafError(400, "EMPTY_UPLOAD", "Tệp tải lên rỗng.")
        if amount > limit:
            raise LeafError(413, "MEDIA_TOO_LARGE", "Tệp vượt giới hạn dung lượng.")
    return mime, limit


async def stream_upload(request: Request, upload, limit: int) -> None:
    length = 0
    try:
        async with asyncio.timeout(UPLOAD_TIMEOUT_SECONDS):
            async for chunk in request.stream():
                length += len(chunk)
                if length > limit:
                    raise LeafError(413, "MEDIA_TOO_LARGE", "Tệp vượt giới hạn dung lượng.")
                upload.write(chunk)
    except TimeoutError as exc:
        raise LeafError(408, "UPLOAD_TIMEOUT", "Quá thời gian chờ tải tệp.") from exc
    except ClientDisconnect as exc:
        raise LeafError(400, "UPLOAD_INTERRUPTED", "Quá trình tải tệp bị gián đoạn.") from exc
    if not length:
        raise LeafError(400, "EMPTY_UPLOAD", "Tệp tải lên rỗng.")
    upload.seek(0)


def create_app(model_loader=load_predictor) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.model = None
        app.state.upload_lock = asyncio.Lock()
        try:
            app.state.model = await run_in_threadpool(model_loader)
        except Exception:
            LOGGER.exception("Leaf checkpoint could not be initialized.")
        yield
        app.state.model = None

    app = FastAPI(title="TeaSmart Leaf Inference", version="1.0", lifespan=lifespan)

    @app.exception_handler(LeafError)
    async def leaf_error(_request: Request, error: LeafError):
        headers = {"Retry-After": "2"} if error.status == 429 else None
        return JSONResponse({"code": error.code, "message": str(error)}, status_code=error.status, headers=headers)

    @app.get("/health")
    async def health():
        model = app.state.model
        if model is None:
            return JSONResponse({"status": "not_ready", "code": "MODEL_NOT_READY"}, status_code=503)
        return {"status": "ready", "model_id": model.model_id,
                "classes": len(model.class_names), "device": model.device}

    @app.post(
        "/predict",
        response_model=Prediction,
        openapi_extra={"requestBody": {"required": True, "content": {
            mime: {"schema": {"type": "string", "format": "binary"}} for mime in MEDIA_LIMITS
        }}},
    )
    async def predict(request: Request):
        model = app.state.model
        if model is None:
            raise LeafError(503, "MODEL_NOT_READY", "Mô hình chưa sẵn sàng.")
        mime, limit = read_upload_headers(request)
        lock = app.state.upload_lock
        if lock.locked():
            raise LeafError(429, "LEAF_BUSY", "Dịch vụ đang xử lý tệp khác. Vui lòng thử lại.")
        async with lock:
            try:
                # No user filename, persistent object, or publicly addressable upload.
                with tempfile.TemporaryFile(mode="w+b") as upload:
                    await stream_upload(request, upload, limit)
                    return await run_in_threadpool(analyze_upload, model, upload, mime)
            except OSError as exc:
                LOGGER.warning("Leaf temporary upload could not be processed.", exc_info=True)
                raise LeafError(503, "TEMPORARY_UPLOAD_UNAVAILABLE",
                                "Dịch vụ tạm thời không thể đọc tệp tải lên.") from exc

    return app


app = create_app()
