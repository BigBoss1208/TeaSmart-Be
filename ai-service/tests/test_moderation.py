import pytest
from app.moderation import inspect

@pytest.mark.parametrize("text,flag", [
    ("Mua ngay tại https://example.invalid", "ADVERTISING_LINK"),
    ("Liên hệ zalo để mua ngay!", "ADVERTISING_LANGUAGE"),
    ("mua chè " * 8, "REPETITIVE_CONTENT"),
    ("đồ ngu", "POSSIBLE_INSULT"),
])
def test_advisory_flags(text, flag):
    flags, reasons = inspect(text, 3, "NEUTRAL")
    assert flag in flags and len(flags) == len(reasons)
    assert len(flags) == len(set(flags))

@pytest.mark.parametrize("rating,sentiment", [(5, "NEGATIVE"), (1, "POSITIVE")])
def test_extreme_mismatch(rating, sentiment):
    assert "RATING_SENTIMENT_MISMATCH" in inspect("Chè.", rating, sentiment)[0]

@pytest.mark.parametrize("text", [
    "Chè không ngon.", "Che binh thuong", "Không thơm nhưng giao hàng tốt.",
    "Chè thơm 😊", "Ổn.", "đồ ngựa", "Tôi đã mua chè ở cửa hàng.",
])
def test_no_automatic_negative_or_normal_flags(text):
    assert inspect(text, 1, "NEGATIVE")[0] == []

def test_bounded_repetition():
    flags, _ = inspect("a " * 1000, 3, None)
    assert "REPETITIVE_CONTENT" in flags
