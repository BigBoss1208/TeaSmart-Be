package vn.teasmart.backend.controller;
import jakarta.validation.Valid;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.RagChatService;import vn.teasmart.backend.dto.request.RagChatRequest;import vn.teasmart.backend.dto.response.RagChatResponse;import vn.teasmart.backend.exception.GlobalExceptionHandler.ErrorResponse;
@RestController @RequestMapping("/api/chatbot/rag")
public class RagChatController {
 private final RagChatService service;public RagChatController(RagChatService service){this.service=service;}
 @PostMapping public RagChatResponse answer(@Valid @RequestBody RagChatRequest body){return service.answer(body);}
 @ExceptionHandler(RagChatService.Failure.class)public ResponseEntity<ErrorResponse> unavailable(RagChatService.Failure e){return ResponseEntity.status(e.status).body(new ErrorResponse(e.code,e.getMessage()));}
 @ExceptionHandler(IllegalArgumentException.class)public ResponseEntity<ErrorResponse> invalid(){return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PREFERENCES","Invalid chat preferences."));}
}
