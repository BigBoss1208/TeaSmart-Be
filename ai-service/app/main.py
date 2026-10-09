import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from .config import Settings, MODEL_VERSION
from .schemas import AnalyzeRequest, AnalyzeResponse
from .sentiment import SentimentRunner, ModelFailure
from .moderation import inspect

class BodyLimit:
    def __init__(self, app, limit):
        self.app, self.limit = app, limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] != "/analyze":
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        if scope["method"] == "POST" and headers.get(b"content-type", b"").split(b";")[0].strip().lower() != b"application/json":
            return await JSONResponse({"errorCode": "UNSUPPORTED_MEDIA_TYPE"}, status_code=415)(scope, receive, send)
        messages, size = [], 0
        while True:
            try:
                message = await asyncio.wait_for(receive(), timeout=5.0)
            except TimeoutError:
                return await JSONResponse({"errorCode": "REQUEST_TIMEOUT"}, status_code=408)(scope, receive, send)
            if message["type"] == "http.disconnect":
                return
            size += len(message.get("body", b""))
            if size > self.limit:
                return await JSONResponse({"errorCode": "REQUEST_TOO_LARGE"}, status_code=413)(scope, receive, send)
            messages.append(message)
            if not message.get("more_body", False):
                break
        async def replay():
            if messages:
                return messages.pop(0)
            return await receive()
        await self.app(scope, replay, send)

def create_app(runner=None, settings=None):
    settings = settings or Settings.from_env()
    engine = runner or SentimentRunner(settings)

    @asynccontextmanager
    async def lifespan(app):
        await engine.start()
        try:
            yield
        finally:
            engine.close()

    app = FastAPI(title="TeaSmart Review AI", debug=False, lifespan=lifespan)
    app.add_middleware(BodyLimit, limit=settings.max_body_bytes)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, error):
        return JSONResponse({"errorCode": "INVALID_REQUEST"}, status_code=422)

    @app.exception_handler(Exception)
    async def unexpected_error(request, error):
        return JSONResponse({"errorCode": "INTERNAL_ERROR"}, status_code=500)

    @app.get("/health")
    async def health():
        ready = engine.is_ready()
        return JSONResponse({"status": "READY" if ready else "NOT_READY",
                             "modelReady": ready, "modelVersion": MODEL_VERSION,
                             "errorCode": None if ready else "MODEL_NOT_READY"},
                            status_code=200 if ready else 503)

    @app.post("/analyze", response_model=AnalyzeResponse)
    async def analyze(body: AnalyzeRequest):
        if body.comment is None or not body.comment.strip():
            return AnalyzeResponse(sentimentLabel=None, needsReview=None, moderationFlags=[],
                reasons=[], analysisMethod="RULE_BASED", modelVersion=MODEL_VERSION, processingStatus="SKIPPED")
        try:
            result = await engine.analyze(body.comment)
        except ModelFailure as error:
            status = {"MODEL_NOT_READY": 503, "MODEL_BUSY": 503,
                      "MODEL_TIMEOUT": 504, "MODEL_OUTPUT_INVALID": 502}.get(error.code, 502)
            return JSONResponse({"errorCode": error.code}, status_code=status)
        if result.get("errorCode") == "INPUT_TOO_LONG":
            return AnalyzeResponse(sentimentLabel=None, needsReview=None, moderationFlags=[],
                reasons=[], analysisMethod="MODEL", modelVersion=MODEL_VERSION,
                processingStatus="SKIPPED", errorCode="INPUT_TOO_LONG")
        label = result.get("sentimentLabel")
        if label not in ("POSITIVE", "NEUTRAL", "NEGATIVE"):
            return JSONResponse({"errorCode": "MODEL_OUTPUT_INVALID"}, status_code=502)
        flags, reasons = inspect(body.comment, body.rating, label)
        return AnalyzeResponse(sentimentLabel=label, needsReview=bool(flags), moderationFlags=flags,
            reasons=reasons, analysisMethod="MODEL", modelVersion=MODEL_VERSION, processingStatus="COMPLETED")
    return app

app = create_app()
