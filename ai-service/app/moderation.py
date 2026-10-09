"""Per-comment advisory flags; never automatic moderation."""
import re
import unicodedata
from collections import Counter

LINK = re.compile(r"(?:https?://|www\.)", re.IGNORECASE)
WORDS = re.compile(r"\w+", re.UNICODE)
ADVERTISING = ("mua ngay", "liên hệ zalo", "inbox nhận ưu đãi")
INSULTS = ("đồ ngu", "ngu ngốc", "đồ khốn")
REASONS = {
    "ADVERTISING_LINK": "Contains a link; manually check advertising context.",
    "ADVERTISING_LANGUAGE": "Contains a phrase commonly used for advertising.",
    "REPETITIVE_CONTENT": "Contains unusually repeated words or phrases.",
    "POSSIBLE_INSULT": "Contains an expression that may be insulting; check context.",
    "RATING_SENTIMENT_MISMATCH": "Extreme rating conflicts with model sentiment; check context.",
}

def inspect(comment: str, rating: int, sentiment: str | None):
    text = " ".join(unicodedata.normalize("NFC", comment).casefold().split())
    flags = []
    if LINK.search(text):
        flags.append("ADVERTISING_LINK")
    if any(re.search(r"(?<!\w)" + re.escape(p) + r"(?!\w)", text) for p in ADVERTISING):
        flags.append("ADVERTISING_LANGUAGE")
    words = WORDS.findall(text)
    repeated_word = len(words) >= 12 and max(Counter(words).values(), default=0) >= max(8, len(words) * 0.6)
    repeated_phrase = any(max(Counter(tuple(words[i:i+n]) for i in range(len(words)-n+1)).values(), default=0) >= 4
                          for n in (2, 3))
    if repeated_word or repeated_phrase:
        flags.append("REPETITIVE_CONTENT")
    if any(re.search(r"(?<!\w)" + re.escape(p) + r"(?!\w)", text) for p in INSULTS):
        flags.append("POSSIBLE_INSULT")
    if (rating == 5 and sentiment == "NEGATIVE") or (rating == 1 and sentiment == "POSITIVE"):
        flags.append("RATING_SENTIMENT_MISMATCH")
    return flags, [REASONS[flag] for flag in flags]
