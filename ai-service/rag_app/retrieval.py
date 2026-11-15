"""Versioned lexical embeddings + SQLite vector store, no model download or MySQL access."""
import hashlib, json, math, re, sqlite3, unicodedata
from pathlib import Path
MODEL = "VI_LEXICAL_HASH_2048_V1"
def normalized(text):
    return "".join(c for c in unicodedata.normalize("NFD", text.lower().replace("đ", "d")) if unicodedata.category(c) != "Mn")
def embed(text):
    words = re.findall(r"[a-z0-9]+", normalized(text))
    features = words + [" ".join(words[i:i+2]) for i in range(len(words)-1)]
    values = {}
    for token in features:
        idx = int.from_bytes(hashlib.sha256(token.encode()).digest()[:4], "big") % 2048
        values[idx] = values.get(idx, 0) + 1
    length = math.sqrt(sum(v*v for v in values.values())) or 1
    return {str(k): v/length for k,v in values.items()}
def chunks(text, size=450, overlap=70):
    text = unicodedata.normalize("NFC", text.strip())
    result=[]; start=0
    while start < len(text):
        end=min(len(text), start+size)
        if end < len(text):
            boundary=text.rfind(" ", start+size//2, end)
            if boundary>start: end=boundary
        result.append(text[start:end].strip())
        if end==len(text): break
        start=max(start+1, end-overlap)
    return result
class VectorStore:
    def __init__(self, path, sources): self.path=Path(path); self.sources=Path(sources)
    def rebuild(self):
        documents=json.loads(self.sources.read_text(encoding="utf-8"))
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path) as db:
            db.execute("CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, source_id TEXT, title TEXT, url TEXT, publisher TEXT, accessed_at TEXT, text TEXT, embedding TEXT, model TEXT)")
            db.execute("DELETE FROM chunks")
            for doc in documents:
                for i,text in enumerate(chunks(doc["text"])):
                    db.execute("INSERT INTO chunks VALUES (?,?,?,?,?,?,?,?,?)",(f"{doc['id']}:{i}",doc["id"],doc["title"],doc["url"],doc["publisher"],doc["accessedAt"],text,json.dumps(embed(doc["title"]+" "+text)),MODEL))
        return len(documents)
    def search(self, query, limit=4):
        q=embed(query); result=[]
        with sqlite3.connect(self.path) as db:
            for row in db.execute("SELECT id,source_id,title,url,publisher,accessed_at,text,embedding FROM chunks WHERE model=?",(MODEL,)):
                vector=json.loads(row[7]); score=sum(value*vector.get(key,0) for key,value in q.items())
                if score>=0.08: result.append({"id":row[0],"sourceId":row[1],"title":row[2],"url":row[3],"publisher":row[4],"accessedAt":row[5],"text":row[6],"score":score})
        return sorted(result,key=lambda item:(-item["score"],item["id"]))[:limit]
