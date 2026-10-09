from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

Sentiment = Literal["POSITIVE", "NEUTRAL", "NEGATIVE"]
Flag = Literal["ADVERTISING_LINK", "ADVERTISING_LANGUAGE", "REPETITIVE_CONTENT",
               "POSSIBLE_INSULT", "RATING_SENTIMENT_MISMATCH"]


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    comment: str | None = Field(default=None, max_length=2000)
    rating: int = Field(ge=1, le=5)


class AnalyzeResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    sentimentLabel: Sentiment | None
    confidence: None = None
    needsReview: bool | None
    moderationFlags: list[Flag]
    reasons: list[str]
    analysisMethod: Literal["MODEL", "RULE_BASED"]
    modelVersion: str
    processingStatus: Literal["COMPLETED", "SKIPPED"]
    errorCode: str | None = None
