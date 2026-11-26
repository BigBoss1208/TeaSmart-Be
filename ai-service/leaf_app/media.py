"""Validate uploaded media and sample one frame per video timeline interval."""

import time
from collections.abc import Callable
from dataclasses import dataclass
from fractions import Fraction
from typing import BinaryIO

import av
from PIL import Image, ImageOps, UnidentifiedImageError

IMAGE_LIMIT = 10 * 1024 * 1024
VIDEO_LIMIT = 30_000_000
VIDEO_SECONDS = 15
MAX_SAMPLES = 8
IMAGE_PIXEL_LIMIT = 16_000_000
VIDEO_PIXEL_LIMIT = 4096 * 2160
VIDEO_DECODE_LIMIT = 960
VIDEO_DECODE_SECONDS = 20
IMAGE_FORMATS = {
    "image/jpeg": ("JPEG", b"\xff\xd8\xff"),
    "image/png": ("PNG", b"\x89PNG\r\n\x1a\n"),
}
MEDIA_LIMITS = {mime: IMAGE_LIMIT for mime in IMAGE_FORMATS} | {"video/mp4": VIDEO_LIMIT}
MP4_BRANDS = {
    b"isom", b"mp41", b"mp42", b"avc1", b"dash", b"cmfc", b"cmfs", b"M4V ", b"M4A ",
} | {f"iso{version}".encode("ascii") for version in range(1, 10)}


class LeafError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


@dataclass
class VideoSamples:
    duration_seconds: float
    timestamps: list[float]
    frames: list


def check_pixels(width: int, height: int, limit: int) -> None:
    if width <= 0 or height <= 0 or width * height > limit:
        raise LeafError(413, "MEDIA_DIMENSIONS_EXCEEDED", "Kích thước khung ảnh vượt giới hạn.")


def prepare_image(upload: BinaryIO, mime: str, prepare: Callable):
    expected_format, signature = IMAGE_FORMATS[mime]
    prefix = upload.read(len(signature))
    upload.seek(0)
    if prefix != signature:
        raise LeafError(415, "MEDIA_TYPE_MISMATCH", "Nội dung tệp không khớp định dạng ảnh đã khai báo.")
    try:
        with Image.open(upload, formats=[expected_format]) as source:
            check_pixels(source.width, source.height, IMAGE_PIXEL_LIMIT)
            if getattr(source, "is_animated", False):
                raise LeafError(415, "ANIMATED_IMAGE_UNSUPPORTED", "Hãy gửi ảnh tĩnh JPG hoặc PNG.")
            source.load()
            image = ImageOps.exif_transpose(source) if source.getexif().get(274, 1) != 1 else source
            return prepare(image if image.mode == "RGB" else image.convert("RGB"))
    except Image.DecompressionBombError as exc:
        raise LeafError(413, "MEDIA_DIMENSIONS_EXCEEDED", "Kích thước ảnh vượt giới hạn.") from exc
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError) as exc:
        raise LeafError(422, "INVALID_IMAGE", "Không thể giải mã ảnh JPG hoặc PNG này.") from exc


def has_mp4_header(upload: BinaryIO) -> bool:
    # MP4 has an ISO-BMFF file type box, optionally following a leading padding box.
    header = upload.read(4096)
    upload.seek(0)
    index = 0
    while index + 8 <= len(header):
        size = int.from_bytes(header[index:index + 4], "big")
        kind = header[index + 4:index + 8]
        if kind == b"ftyp":
            if size < 16 or size % 4 != 0 or index + size > len(header):
                return False
            major = header[index + 8:index + 12]
            compatible = {header[at:at + 4] for at in range(index + 16, index + size, 4)}
            return major != b"qt  " and (major in MP4_BRANDS or bool(compatible & MP4_BRANDS))
        if kind not in (b"free", b"skip", b"wide") or size < 8:
            break
        index += size
    return False


def video_timing(container, stream) -> Fraction:
    durations = []
    if container.duration is not None:
        durations.append(Fraction(container.duration, av.time_base))
    if stream.duration is not None and stream.time_base is not None:
        durations.append(stream.duration * stream.time_base)
    duration = max(durations, default=Fraction(0))
    if duration <= 0:
        raise LeafError(422, "INVALID_VIDEO_DURATION", "Không xác định được thời lượng video.")
    if duration > VIDEO_SECONDS:
        raise LeafError(422, "VIDEO_TOO_LONG", "Video chỉ được dài tối đa 15 giây.")
    return duration


def collect_video_frames(container, stream, duration: Fraction, prepare: Callable, deadline: float) -> VideoSamples:
    codec = stream.codec_context
    if codec.width and codec.height:
        check_pixels(codec.width, codec.height, VIDEO_PIXEL_LIMIT)
    codec.thread_count = 1
    timestamps = []
    tensors = []
    base = stream.start_time * stream.time_base if stream.start_time is not None and stream.time_base else None
    previous = None
    # Only the candidate for the current interval and the prepared tensors are retained.
    pending = None

    def save_candidate() -> None:
        _, offset, frame = pending
        image = frame.to_image()
        if frame.rotation:
            image = image.rotate(frame.rotation, expand=True)
        tensors.append(prepare(image))
        timestamps.append(round(float(offset), 6))

    for count, frame in enumerate(container.decode(stream), 1):
        if count > VIDEO_DECODE_LIMIT or time.monotonic() > deadline:
            raise LeafError(422, "VIDEO_DECODE_LIMIT", "Video vượt ngân sách giải mã. Hãy giảm độ phân giải hoặc FPS.")
        if frame.is_corrupt:
            raise LeafError(422, "INVALID_VIDEO", "Video có khung hình bị hỏng.")
        check_pixels(frame.width, frame.height, VIDEO_PIXEL_LIMIT)
        if frame.pts is None or frame.time_base is None:
            continue

        timestamp = frame.pts * frame.time_base
        if base is None:
            base = timestamp
        offset = timestamp - base
        if offset < 0:
            continue  # Decoder preroll before the presentation timeline.
        if previous is not None:
            if offset < previous:
                raise LeafError(422, "INVALID_VIDEO", "Dòng thời gian của video không hợp lệ.")
            if offset == previous:
                continue
        previous = offset
        end = offset + max(frame.duration or 0, 0) * frame.time_base
        if offset >= VIDEO_SECONDS or end > VIDEO_SECONDS:
            raise LeafError(422, "VIDEO_TOO_LONG", "Video chỉ được dài tối đa 15 giây.")
        if end > duration + Fraction(1, 10):
            raise LeafError(422, "INVALID_VIDEO_DURATION", "Thời lượng khai báo không khớp với dòng thời gian.")

        slot = min(int(offset * MAX_SAMPLES / duration), MAX_SAMPLES - 1)
        center = duration * Fraction(2 * slot + 1, 2 * MAX_SAMPLES)
        if pending is not None and slot != pending[0]:
            save_candidate()
            pending = None
        if pending is None or abs(offset - center) < abs(pending[1] - center):
            pending = (slot, offset, frame)

    if pending is None:
        raise LeafError(422, "NO_VIDEO_FRAMES", "Video không có khung hình hợp lệ để phân tích.")
    save_candidate()
    return VideoSamples(float(duration), timestamps, tensors)


def prepare_video(upload: BinaryIO, prepare: Callable) -> VideoSamples:
    if not has_mp4_header(upload):
        raise LeafError(415, "MEDIA_TYPE_MISMATCH", "Nội dung tệp không phải video MP4.")
    deadline = time.monotonic() + VIDEO_DECODE_SECONDS
    try:
        with av.open(
            upload, mode="r", format="mp4",
            options={
                "enable_drefs": "0", "use_absolute_path": "0", "protocol_whitelist": "pipe",
                "max_pixels": str(VIDEO_PIXEL_LIMIT),
            },
            timeout=(5.0, 5.0),
        ) as container:
            streams = [stream for stream in container.streams.video if not (stream.disposition & av.stream.Disposition.attached_pic)]
            if not streams:
                raise LeafError(422, "NO_VIDEO_FRAMES", "Video không có luồng hình ảnh.")
            stream = streams[0]
            duration = video_timing(container, stream)
            return collect_video_frames(container, stream, duration, prepare, deadline)
    except (av.error.FFmpegError, OSError, ValueError, OverflowError) as exc:
        raise LeafError(422, "INVALID_VIDEO", "Không thể giải mã video MP4 này.") from exc
