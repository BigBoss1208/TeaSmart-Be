package vn.teasmart.backend.dto.request;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
public record RagChatRequest(@NotBlank @Size(max=1000) String message,
    @Valid TeaPreferenceRequest preferences, @Valid TeaPreferenceRequest context,
    @Size(max=6) List<@NotBlank @Size(max=1000) String> history, Boolean allowLlm, @Positive Long productId) {
    public RagChatRequest(String message, TeaPreferenceRequest preferences, TeaPreferenceRequest context, List<String> history) { this(message, preferences, context, history, null); }
    public RagChatRequest(String message, TeaPreferenceRequest preferences, TeaPreferenceRequest context, List<String> history, Boolean allowLlm) { this(message, preferences, context, history, allowLlm, null); }
    @JsonAnySetter public void rejectUnknownField(String name,Object value){throw new IllegalArgumentException("Unsupported chat field.");}
}
