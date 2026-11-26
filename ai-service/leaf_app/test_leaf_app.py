"""Standalone unit and HTTP tests; the optional CUDA smoke reads Train images only."""

import http.client
import io
import itertools
import json
import math
import os
import socket
import threading
import time
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import patch

import av
import uvicorn
from PIL import Image

from .main import app, create_app
from .media import IMAGE_LIMIT, VIDEO_LIMIT


@contextmanager
def serving(application, port=0):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", port))
        listener.listen()
        server = uvicorn.Server(uvicorn.Config(application, log_level="critical", access_log=False))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 65
            while not server.started and thread.is_alive() and time.monotonic() < deadline:
                time.sleep(0.05)
            if not server.started:
                raise RuntimeError("Leaf HTTP test server did not start.")
            yield listener.getsockname()[1]
        finally:
            server.should_exit = True
            thread.join(20)
            if thread.is_alive():
                raise RuntimeError("Leaf HTTP test server did not stop.")


def request(port, body=None, mime=None, *, path="/predict", **headers):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    try:
        if mime:
            headers["Content-Type"] = mime
        conn.request("GET" if path == "/health" else "POST", path, body=body, headers=headers,
                     encode_chunked=True)
        response = conn.getresponse()
        return response.status, json.loads(response.read())
    finally:
        conn.close()


def image_bytes(image=None, image_format="PNG") -> bytes:
    image = image or Image.new("RGB", (32, 32), (32, 136, 48))
    with io.BytesIO() as buffer:
        image.save(buffer, format=image_format)
        return buffer.getvalue()


def mp4_bytes(images, fps=8) -> bytes:
    with io.BytesIO() as buffer:
        with av.open(buffer, mode="w", format="mp4") as output:
            # H.264 is also suitable for preview in the future React client.
            stream = output.add_stream("libx264", rate=fps)
            stream.width, stream.height = 112, 112
            stream.pix_fmt = "yuv420p"
            stream.codec_context.thread_count = 1
            for image in images:
                frame = av.VideoFrame.from_image(image.convert("RGB").resize((112, 112)))
                output.mux(stream.encode(frame))
            output.mux(stream.encode(None))
        return buffer.getvalue()


def audio_only_mp4() -> bytes:
    with io.BytesIO() as buffer:
        with av.open(buffer, mode="w", format="mp4") as output:
            stream = output.add_stream("aac", rate=48000)
            stream.codec_context.layout = "mono"
            for _ in range(4):
                frame = av.AudioFrame(format="fltp", layout="mono", samples=1024)
                frame.sample_rate = 48000
                for plane in frame.planes:
                    plane.update(bytes(plane.buffer_size))
                output.mux(stream.encode(frame))
            output.mux(stream.encode(None))
        return buffer.getvalue()


class FixturePredictor:
    model_id = "unit-test"
    class_names = tuple(f"Class {index}" for index in range(7))
    device = "cpu"

    @staticmethod
    def prepare(image):
        return image.resize((32, 32))

    def predict(self, frames):
        return (
            [[0.7, 0.25, *([0.01] * 5)]] * ((len(frames) + 1) // 2)
            + [[0.2, 0.75, *([0.01] * 5)]] * (len(frames) // 2)
        )


class UploadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server = serving(create_app(FixturePredictor))
        cls.port = server.__enter__()
        cls.addClassCleanup(server.__exit__, None, None, None)
        cls.picture = image_bytes()
        cls.video = mp4_bytes([Image.new("RGB", (112, 112), "green")] * 16)

    def check_error(self, code, status, body, mime, **headers):
        result, error = request(self.port, body, mime, **headers)
        self.assertEqual((result, error["code"]), (status, code))

    def test_health(self):
        self.assertEqual(request(self.port, path="/health"), (200, {
            "status": "ready", "model_id": "unit-test", "classes": 7, "device": "cpu",
        }))

    def test_jpeg_and_png(self):
        for mime, data in (("image/png", self.picture), ("image/jpeg", image_bytes(image_format="JPEG"))):
            with self.subTest(mime=mime):
                status, result = request(self.port, data, mime)
                self.assertEqual(status, 200)
                self.assertEqual(result["media_type"], "image")
                self.assertEqual(result["class_index"], 0)
                self.assertEqual(len(result["class_probabilities"]), 7)
                self.assertAlmostEqual(result["prediction_score"], 0.7)
                self.assertIsNone(result["video"])
                self.assertEqual(result["warnings"], [])

    def test_video_distribution_and_disagreement(self):
        status, result = request(self.port, self.video, "video/mp4")
        self.assertEqual(status, 200, result)
        self.assertEqual(result["media_type"], "video")
        self.assertEqual(result["class_index"], 1)
        self.assertAlmostEqual(result["prediction_score"], 0.5)
        self.assertIn("INCONSISTENT_VIDEO_PREDICTIONS", result["warnings"])
        video = result["video"]
        self.assertEqual((video["sampled_frames"], video["agreeing_frames"], video["agreement_ratio"]), (8, 4, 0.5))
        timestamps = [frame["timestamp_seconds"] for frame in video["frames"]]
        self.assertEqual(len(set(timestamps)), 8)
        self.assertEqual(timestamps, sorted(timestamps))
        self.assertLess(timestamps[-1], video["duration_seconds"])

    def test_empty_and_unsupported_mime(self):
        self.check_error("EMPTY_UPLOAD", 400, b"", "image/png")
        self.check_error("UNSUPPORTED_MEDIA_TYPE", 415, self.picture, "text/plain")
        self.check_error("UNSUPPORTED_MEDIA_TYPE", 415, self.picture, "image/png", **{"Content-Encoding": "gzip"})

    def test_mime_does_not_match_bytes(self):
        self.check_error("MEDIA_TYPE_MISMATCH", 415, self.picture, "image/jpeg")
        self.check_error("MEDIA_TYPE_MISMATCH", 415, self.picture, "video/mp4")

    def test_other_iso_bmff_brand_is_not_mp4(self):
        size = int.from_bytes(self.video[:4], "big")
        self.assertEqual(self.video[4:8], b"ftyp")
        header = self.video[:8] + b"heic" + self.video[12:16] + b"heic" * ((size - 16) // 4)
        self.check_error("MEDIA_TYPE_MISMATCH", 415, header + self.video[size:], "video/mp4")

    def test_image_pixel_budget(self):
        image = Image.new("1", (4001, 4000))
        self.check_error("MEDIA_DIMENSIONS_EXCEEDED", 413, image_bytes(image), "image/png")

    def test_video_decode_budget(self):
        with patch("leaf_app.media.VIDEO_DECODE_LIMIT", 4):
            self.check_error("VIDEO_DECODE_LIMIT", 422, self.video, "video/mp4")

    def test_only_one_frame_is_reported(self):
        frame = Image.new("RGB", (112, 112), "green")
        status, result = request(self.port, mp4_bytes([frame], fps=1), "video/mp4")
        self.assertEqual(status, 200, result)
        self.assertEqual(result["video"]["sampled_frames"], 1)
        self.assertEqual(result["warnings"], ["INSUFFICIENT_FRAMES_FOR_AGREEMENT"])

    def test_declared_oversize_per_media(self):
        self.check_error("MEDIA_TOO_LARGE", 413, b"", "image/png", **{"Content-Length": str(IMAGE_LIMIT + 1)})
        self.check_error("MEDIA_TOO_LARGE", 413, b"", "video/mp4", **{"Content-Length": str(VIDEO_LIMIT + 1)})

    def test_actual_bytes_counted_for_chunked_upload(self):
        chunk = b"\0" * (1024 * 1024)
        body = itertools.chain(itertools.repeat(chunk, IMAGE_LIMIT // len(chunk)), [b"\0"])
        self.check_error("MEDIA_TOO_LARGE", 413, body, "image/jpeg")

    def test_corrupt_image_and_mp4(self):
        self.check_error("INVALID_IMAGE", 422, self.picture[:16], "image/png")
        self.check_error("INVALID_VIDEO", 422, self.video[:32], "video/mp4")

    def test_video_duration_limit(self):
        frame = Image.new("RGB", (112, 112), "green")
        boundary = mp4_bytes([frame] * 15, fps=1)
        status, result = request(self.port, boundary, "video/mp4")
        self.assertEqual(status, 200, result)
        self.assertAlmostEqual(result["video"]["duration_seconds"], 15.0)
        too_long = mp4_bytes([frame] * 16, fps=1)
        self.check_error("VIDEO_TOO_LONG", 422, too_long, "video/mp4")

    def test_audio_only_mp4(self):
        self.check_error("NO_VIDEO_FRAMES", 422, audio_only_mp4(), "video/mp4")


class StartupAndConcurrencyTests(unittest.TestCase):
    def test_not_ready(self):
        def failed_loader():
            raise RuntimeError("checkpoint unavailable")

        with serving(create_app(failed_loader)) as port:
            self.assertEqual(request(port, path="/health"), (503, {"status": "not_ready", "code": "MODEL_NOT_READY"}))
            status, body = request(port, image_bytes(), "image/png")
            self.assertEqual((status, body["code"]), (503, "MODEL_NOT_READY"))

    def test_only_one_upload_while_health_stays_responsive(self):
        processing = threading.Event()
        release = threading.Event()

        class SlowPredictor(FixturePredictor):
            def predict(self, frames):
                processing.set()
                if not release.wait(10):
                    raise RuntimeError("Test did not release predictor.")
                return super().predict(frames)

        with serving(create_app(SlowPredictor)) as port:
            picture = image_bytes()
            responses = []
            first = threading.Thread(target=lambda: responses.append(request(port, picture, "image/png")))
            first.start()
            try:
                self.assertTrue(processing.wait(5))
                status, result = request(port, picture, "image/png")
                self.assertEqual((status, result["code"]), (429, "LEAF_BUSY"))
                self.assertEqual(request(port, path="/health")[0], 200)
            finally:
                release.set()
                first.join(10)
            self.assertEqual(responses[0][0], 200)


@unittest.skipUnless(os.getenv("LEAF_REAL_SMOKE") == "1", "Real epoch 20 smoke not requested")
class RealModelSmoke(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        server = serving(app)
        cls.port = server.__enter__()
        cls.addClassCleanup(server.__exit__, None, None, None)
        train = Path(os.environ["LEAF_SMOKE_TRAIN_DIR"]).resolve(strict=True)
        if train.name != "train" or train.parent.name != "tea-leaf-clean-v1":
            raise ValueError("Smoke requires the clean Train split.")
        cls.healthy = next(iter(sorted((train / "7. Healthy leaf").glob("*.jpg"))))
        cls.blight = next(iter(sorted((train / "2. Brown Blight").glob("*.jpg"))))

    def check_prediction(self, status, result, media_type):
        self.assertEqual(status, 200, result)
        self.assertEqual((result["model_id"], result["media_type"]), ("mobilenetv2-epoch20", media_type))
        probabilities = result["class_probabilities"]
        self.assertEqual([row["class_index"] for row in probabilities], list(range(7)))
        self.assertTrue(math.isclose(sum(row["probability"] for row in probabilities), 1.0, abs_tol=1e-6))
        self.assertEqual(result["prediction_score"], probabilities[result["class_index"]]["probability"])

    def test_real_health(self):
        status, result = request(self.port, path="/health")
        self.assertEqual((status, result.get("classes")), (200, 7), result)
        expected_devices = {"cpu": {"cpu"}, "cuda": {"cuda:0"}}
        self.assertIn(result.get("device"), expected_devices.get(os.getenv("LEAF_DEVICE"), {"cpu", "cuda:0"}))
        print("Real health:", result)

    def test_real_train_photo(self):
        status, result = request(self.port, self.healthy.read_bytes(), "image/jpeg")
        self.check_prediction(status, result, "image")
        print("Real JPEG train photo:", result["label"], "score", round(result["prediction_score"], 4))

    def test_real_short_mp4_from_train_photos(self):
        with Image.open(self.healthy) as healthy, Image.open(self.blight) as blight:
            video = mp4_bytes([healthy] * 8 + [blight] * 8)
        status, result = request(self.port, video, "video/mp4")
        self.check_prediction(status, result, "video")
        self.assertEqual(result["video"]["sampled_frames"], 8)
        self.assertIn(result["video"]["agreeing_frames"], range(9))
        print("Real MP4 from train photos:", result["label"],
              "agreement", result["video"]["agreeing_frames"], "/", result["video"]["sampled_frames"],
              "warnings", result["warnings"])
