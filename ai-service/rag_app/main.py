import asyncio, logging, os, re, threading
from pathlib import Path
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from .retrieval import VectorStore, MODEL
from .provider import LlmProvider, RagFailure

logger = logging.getLogger("teasmart.rag.answer")


class Request(BaseModel):
    model_config=ConfigDict(extra="forbid", strict=True)
    question: str=Field(min_length=1,max_length=1000)
    history: list[str]=Field(default_factory=list,max_length=6)
    conversation: str | None = Field(default=None, pattern=r"^[0-9a-f]{32}$")
def private_safe(text):
    if re.search(r"Bearer\s+\S+|eyJ[\w-]+\.[\w-]+\.[\w-]+|AIza[\w-]{30,}",text,re.I): raise RagFailure("RAG_PRIVATE_INPUT",400)
    text=re.sub(r"[\w.+-]+@[\w.-]+\.[a-zA-Z]{2,}","[EMAIL]",text)
    text=re.sub(r"(?<!\w)(?:\+84|0)[0-9\s]{8,14}(?!\w)","[PHONE]",text)
    return re.sub(r"(?i)(api[_ -]?key|password|mat khau|mật khẩu)\s*[:=]\s*\S+",r"\1=[REDACTED]",text)
_BREWING_QUANTITY = re.compile(
    r"(?<!\w)(?P<first>\d+(?:[.,]\d+)?)(?:\s*(?:-|–|—|đến)\s*(?P<last>\d+(?:[.,]\d+)?))?"
    r"\s*(?P<unit>độ(?:\s*c)?(?!\w)|°\s*c|giây\b|phút\b|giờ\b|tiếng\b)",
    re.I,
)


def brewing_quantities(text):
    quantities = set()
    for match in _BREWING_QUANTITY.finditer(text):
        unit = match["unit"].lower()
        if unit.startswith(("độ", "°")):
            unit = "celsius"
        elif unit == "tiếng":
            unit = "giờ"
        quantities.add((match["first"].replace(",", "."), (match["last"] or "").replace(",", "."), unit))
    return quantities


def validate_output(result, passages):
    if not isinstance(result,dict) or set(result)!={"paragraphs"} or not isinstance(result["paragraphs"],list) or len(result["paragraphs"])>3: raise RagFailure("RAG_INVALID_OUTPUT",502)
    allowed={p["id"]:p for p in passages}; paragraphs=[]; sources=[]
    for item in result["paragraphs"]:
        if not isinstance(item,dict) or set(item)!={"text","sourceId","quote"}: raise RagFailure("RAG_INVALID_OUTPUT",502)
        text, sid, quote=item["text"],item["sourceId"],item["quote"]
        if not all(isinstance(v,str) for v in (text,sid,quote)) or sid not in allowed or not 8<=len(quote)<=450 or quote not in allowed[sid]["text"] or not text.strip() or len(text)>800: raise RagFailure("RAG_UNGROUNDED_OUTPUT",502)
        if re.search(r"https?://|<[^>]+>|\d[\d.,]*\s*(?:đồng|vnd|vnđ|₫|gói)|bán chạy|đã bán|tồn kho|doanh thu",text,re.I): raise RagFailure("RAG_BUSINESS_CLAIM_REJECTED",502)
        if not brewing_quantities(text).issubset(brewing_quantities(quote)):
            raise RagFailure("RAG_UNGROUNDED_OUTPUT",502)
        p=allowed[sid];paragraphs.append(text);sources.append({k:p[k] for k in ("id","title","url","publisher","accessedAt")})
    return {"method":"RAG_LLM_V1" if paragraphs else "RAG_NO_EVIDENCE","reply":"\n\n".join(paragraphs) if paragraphs else "Chưa có đủ tài liệu đã kiểm chứng để trả lời câu hỏi này.","sources":list({s["id"]:s for s in sources}.values()),"embeddingMethod":MODEL,"llmUsed":True,"evidenceStatus":"CITED_EVIDENCE" if paragraphs else "MODEL_ABSTAINED"}
class LimitBody:
    def __init__(self,app): self.app=app
    async def __call__(self,scope,receive,send):
        if scope["type"]!="http" or scope["method"]!="POST": return await self.app(scope,receive,send)
        messages=[];size=0
        while True:
            try: item=await asyncio.wait_for(receive(),5)
            except TimeoutError: return await JSONResponse({"code":"RAG_REQUEST_TIMEOUT"},status_code=408)(scope,receive,send)
            if item["type"]=="http.disconnect": return
            size+=len(item.get("body",b""))
            if size>16384: return await JSONResponse({"code":"RAG_REQUEST_TOO_LARGE"},status_code=413)(scope,receive,send)
            messages.append(item)
            if not item.get("more_body",False): break
        async def replay(): return messages.pop(0) if messages else await receive()
        await self.app(scope,replay,send)
def create_app(provider=None,store=None):
    provider=provider or LlmProvider();store=store or VectorStore(Path(os.getenv("RAG_INDEX_DIR",str(Path.home()/".teasmart/chatbot-rag")))/"knowledge.sqlite3",Path(__file__).parent/"knowledge/sources.json")
    gate=threading.BoundedSemaphore(2)
    @asynccontextmanager
    async def lifespan(app): store.rebuild();yield
    app=FastAPI(title="TeaSmart Chatbot RAG",debug=False,lifespan=lifespan);app.add_middleware(LimitBody)
    @app.exception_handler(RagFailure)
    async def failure(request,error): return JSONResponse({"code":error.code},status_code=error.status)
    @app.exception_handler(RequestValidationError)
    async def invalid(request,error): return JSONResponse({"code":"RAG_INVALID_REQUEST"},status_code=400)
    @app.exception_handler(Exception)
    async def unexpected(request,error): return JSONResponse({"code":"RAG_INTERNAL_ERROR"},status_code=500)
    @app.get("/health")
    def health(): return {"retrieval":"READY","llmConfigured":provider.configured,"embeddingMethod":MODEL,"provider":getattr(provider,"provider","test-double"),"providerCallsEnabled":getattr(provider,"calls_enabled",False),"model":getattr(provider,"model",None) if provider.configured else None,"ragVersion":"grounded-partial-v2","demoLimits":provider.limits.describe() if isinstance(provider,LlmProvider) else None,"providerDiagnosticsVersion":getattr(provider,"diagnostics_version",None),"knowledgeDocuments":getattr(store,"document_count",None),"knowledgeRevision":getattr(store,"knowledge_revision",None)}
    @app.post("/answer")
    def answer(body:Request):
        if not body.question.strip() or any(len(h)>1000 for h in body.history): raise RagFailure("RAG_INVALID_REQUEST",400)
        if not gate.acquire(blocking=False):
            logger.warning("rag_answer_failure code=RAG_BUSY phase=request_gate")
            raise RagFailure("RAG_BUSY",503)
        try:
            # Spring resolves the follow-up before calling Python. Never append raw
            # history to retrieval or send it upstream (legacy callers may still supply it).
            question = private_safe(body.question)
            for item in body.history:
                private_safe(item)
            passages = store.search(question)
            if not passages:
                logger.info("rag_answer_evidence status=NO_RETRIEVAL")
                return {"method":"RAG_NO_EVIDENCE","reply":"Mình chưa tìm thấy tài liệu phù hợp để trả lời câu này.","sources":[],"embeddingMethod":MODEL,"llmUsed":False,"evidenceStatus":"NO_RETRIEVAL"}
            generated = provider.generate(question, [], passages, conversation=body.conversation)
            try:
                result = validate_output(generated, passages)
            except RagFailure as error:
                # Fixed validation code only. Never log model output or the question.
                logger.warning("rag_answer_failure code=%s phase=grounding", error.code)
                raise
            logger.info("rag_answer_evidence status=%s", result["evidenceStatus"])
            return result
        finally: gate.release()
    return app
app=create_app()
